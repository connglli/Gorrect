package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded sequentially consistent (SC) histories with an all-schedules race audit.
 *
 * <p>Race freedom must hold in every SC execution before SC can serve as the oracle. Checking only
 * the recorded path could reject legal behavior of a racy program.
 */
public final class ConcurrentHistory {
  // Explicit continuations make scope and handle-lifetime changes schedulable between workers.
  private sealed interface Task {}

  private record Execute(Instruction instruction) implements Task {}

  private record Iterate(Instruction.Repeat instruction, long remaining) implements Task {}

  /** Pops a scope; a nonzero acquisition ID also requires its exit marker. */
  private record Leave(int acquisition) implements Task {}

  private record Start(int group) implements Task {}

  private record End(int group) implements Task {}

  private record Phase(int barrier) implements Task {}

  private record Join(Instruction.Parallel group) implements Task {}

  private record Finish() implements Task {}

  private record Position(int scalar, int reference) {}

  private record JoinKey(
      Object machine,
      Object frontier,
      List<Task> tasks,
      Map<Integer, Position> cursors,
      int checkpoint,
      boolean incomplete) {}

  private static final class ThreadState {
    final GraphMachine.Context context;
    final ArrayDeque<Task> tasks = new ArrayDeque<>();
    int phase;
    // The caller waits for a join; workers wait at phase barriers.
    boolean blocked, ended;

    ThreadState(GraphMachine.Context context) {
      this.context = context;
    }

    ThreadState(ThreadState source) {
      context = GraphMachine.copyContext(source.context);
      tasks.addAll(source.tasks);
      phase = source.phase;
      blocked = source.blocked;
      ended = source.ended;
    }

    void body(List<Instruction> body) {
      for (int index = body.size() - 1; index >= 0; index--)
        tasks.push(new Execute(body.get(index)));
    }
  }

  private static final class Cursor {
    int scalar, reference;

    Cursor() {}

    Cursor(Cursor source) {
      scalar = source.scalar;
      reference = source.reference;
    }
  }

  /** A candidate-local mismatch, distinct from invalid workloads and unsupported memory models. */
  private static final class NoMatch extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NoMatch(String message) {
      super(message);
    }
  }

  private final HistoryBudget budget;
  private final boolean audit;
  private final Map<Integer, List<ExecutionTrace.Event>> traces;
  private final Map<Integer, List<ReferenceHistory.Event>> identities = new HashMap<>();
  private final List<HistoryOracle.Observation> observations;
  private String mismatch = "No legal schedule matches the recorded history";

  private ConcurrentHistory(
      HistoryBudget budget,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<HistoryOracle.Observation> observations) {
    this.budget = budget;
    audit = trace == null;
    traces = audit ? Map.of() : trace.byWorker();
    this.observations = observations;
    if (history != null)
      for (var event : history.events())
        identities.computeIfAbsent(event.worker(), ignored -> new ArrayList<>()).add(event);
  }

  /**
   * Proves that every bounded SC path satisfies dynamic preconditions and the ordinary-access race
   * check, independently of observed results. Returns only after complete exploration succeeds.
   *
   * @throws GirException if static contracts or a possible executed precondition are invalid
   * @throws UnsupportedHistoryException if the profile cannot prove ordinary-access race freedom
   * @throws HistoryLimitException if the search budget cannot complete the proof
   * @throws OracleLimitException if model work exceeds its bounds
   */
  public static void audit(GirProgram program, HistoryLimits limits) {
    GirValidator.validate(program);
    new ConcurrentHistory(new HistoryBudget(limits), null, null, List.of()).explore(program);
  }

  /**
   * Repeats the all-paths audit, then matches worker-local results, synchronization, reference
   * choices, and snapshots to a legal schedule. Audit and matching share one cumulative budget.
   * Missing coverage or exhausted bounds yield INCONCLUSIVE; an unmatched complete history yields
   * VIOLATED. File order between workers imposes no scheduling edge.
   *
   * <p>Inputs are not mutated. Invalid workloads and unsupported race profiles still throw their
   * preflight exceptions; a saved trace cannot bypass those checks.
   */
  public static HistoryOracle.Result check(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<HistoryOracle.Observation> observations,
      HistoryLimits limits) {
    GirValidator.validate(program);
    if (!trace.complete()
        || !history.complete()
        || trace.run() != history.run()
        || !HistoryOracle.linked(trace, history))
      return new HistoryOracle.Result(
          ExactVerifier.Verdict.INCONCLUSIVE,
          List.of("Incomplete or mixed-run history"),
          null,
          false);

    try {
      var budget = new HistoryBudget(limits);
      // Share the verification budget across proof and matching, not just across their forks.
      new ConcurrentHistory(budget, null, null, List.of()).explore(program);

      var search = new ConcurrentHistory(budget, trace, history, observations);
      var found = search.explore(program);

      if (found == null)
        return new HistoryOracle.Result(
            ExactVerifier.Verdict.VIOLATED, List.of(search.mismatch), null, false);

      var problems = new ArrayList<String>();
      if (observations.size() != found.checkpoint)
        problems.add("Missing or extra graph observations for the validated path");
      for (var observation : observations)
        if (observation.graph().isEmpty()) problems.addAll(observation.problems());

      return new HistoryOracle.Result(
          found.incomplete ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.SATISFIED,
          problems,
          new OracleResult(found.graph(), found.checkpoints, found.machine.steps(), trace.events()),
          false);
    } catch (HistoryLimitException | OracleLimitException limit) {

      return new HistoryOracle.Result(
          ExactVerifier.Verdict.INCONCLUSIVE, List.of(limit.getMessage()), null, false);
    }
  }

  private State explore(GirProgram program) {
    var pending = new ArrayDeque<State>();
    pending.push(new State(program));
    boolean completed = false;
    var joins = new HashSet<JoinKey>();
    // A scheduler state can hold several clearing histories; memo storage retains those too.
    long pendingStates = 1, memoStates = 0;

    while (!pending.isEmpty()) {
      budget.states(pendingStates + memoStates);
      var state = pending.pop();
      pendingStates -= state.frontier.size();

      if (state.finished) {
        if (audit) {
          completed = true;
          continue;
        }
        try {
          state.complete();
          return state;
        } catch (NoMatch invalid) {
          mismatch = invalid.getMessage();
        }
        continue;
      }

      for (var entry : state.threads.entrySet()) {
        if (entry.getValue().blocked || entry.getValue().ended) continue;
        budget.spend(1);
        var next = new State(state);
        try {
          next.machine.activate(
              next.threads.get(entry.getKey()).context, next.threads.get(entry.getKey()).phase);
          // GC may run before any task, including scope exit, while all live handles still protect.
          next.frontier.expand(next.graph());

          for (var successor : next.advance(entry.getKey())) {
            successor.settle();
            // Joining orders the whole preceding group before future work, so race clocks are
            // empty here. Equal heap/frontier/continuation states have the same possible futures.
            if (successor.joined) {
              if (!joins.add(successor.joinKey())) continue;
              memoStates += successor.frontier.size();
            }
            pending.push(successor);
            pendingStates += successor.frontier.size();
            budget.states(pendingStates + memoStates);
          }
        } catch (NoMatch invalid) {
          mismatch = invalid.getMessage();
        }
      }
    }
    if (audit && !completed) throw new GirException("No complete supported execution");
    return null;
  }

  private final class State {
    final GraphMachine machine;
    final ReferenceFrontier frontier;
    final Map<Integer, ThreadState> threads = new LinkedHashMap<>();
    final Map<Integer, Cursor> cursors = new HashMap<>();
    final List<OracleResult.Checkpoint> checkpoints = new ArrayList<>();
    PhaseRace races;
    Instruction.Parallel group;
    int checkpoint;
    boolean finished, incomplete, joined;

    State(GirProgram program) {
      machine = new GraphMachine(program, OracleLimits.DEFAULT);
      frontier = new ReferenceFrontier(budget);
      races = new PhaseRace(budget);
      machine
          .graph()
          .objects()
          .values()
          .forEach(
              object ->
                  object
                      .references()
                      .forEach(
                          value -> {
                            if (value.kind() != Value.ReferenceKind.STRONG)
                              frontier.register(value);
                          }));
      var main = new ThreadState(machine.context());
      threads.put(0, main);
      main.tasks.push(new Finish());
      main.tasks.push(new Leave(0));
      main.body(program.body());
    }

    State(State source) {
      budget.spend(source.machine.forkCells());
      for (var thread : source.threads.values())
        budget.spend(GraphMachine.contextCells(thread.context) + thread.tasks.size());
      machine = new GraphMachine(source.machine);
      frontier = new ReferenceFrontier(source.frontier);
      races = new PhaseRace(source.races);
      source.threads.forEach((worker, thread) -> threads.put(worker, new ThreadState(thread)));
      source.cursors.forEach((worker, cursor) -> cursors.put(worker, new Cursor(cursor)));
      checkpoints.addAll(source.checkpoints);
      group = source.group;
      checkpoint = source.checkpoint;
      finished = source.finished;
      incomplete = source.incomplete;
      // joined describes only the last transition; copying it would memoize later, non-join steps.
    }

    Graph graph() {
      return machine.graph(threads.values().stream().map(thread -> thread.context).toList());
    }

    JoinKey joinKey() {
      budget.spend(machine.keyCells() + cursors.size() + threads.get(0).tasks.size());
      var positions = new HashMap<Integer, Position>();
      cursors.forEach(
          (worker, cursor) -> positions.put(worker, new Position(cursor.scalar, cursor.reference)));
      return new JoinKey(
          machine.key(),
          frontier.key(),
          List.copyOf(threads.get(0).tasks),
          Map.copyOf(positions),
          checkpoint,
          incomplete);
    }

    Cursor cursor(int worker) {
      return cursors.computeIfAbsent(worker, ignored -> new Cursor());
    }

    void emit(int worker) {
      var cursor = cursor(worker);
      if (!audit) {
        var events = traces.getOrDefault(worker, List.of());
        require(
            cursor.scalar < events.size() && machine.lastEvent().equals(events.get(cursor.scalar)),
            "Operation or result differs for worker "
                + worker
                + " at event "
                + (cursor.scalar + 1));
      }
      cursor.scalar++;
    }

    ReferenceHistory.Event identity(int worker, ReferenceHistory.Kind kind) {
      var cursor = cursor(worker);
      var events = identities.getOrDefault(worker, List.of());
      require(
          cursor.reference < events.size(), "Missing identity observation for worker " + worker);
      var event = events.get(cursor.reference);
      require(
          event.kind() == kind && event.worker() == worker,
          "Identity observation kind/worker differs");
      return event;
    }

    void identity(
        int worker, ReferenceHistory.Kind kind, Value.Reference wrapper, ObjectId target) {
      if (audit) return;
      var event = identity(worker, kind);
      require(
          event.sequence() == cursor(worker).scalar
              && Objects.equals(target, target(event.target())),
          "Reference result or trace position differs for worker " + worker);
      if (wrapper == null)
        require(
            event.wrapper() == null && event.strength() == 0,
            "Strong read carried wrapper metadata");
      else
        require(
            wrapper.wrapper().equals(id(event.wrapper()))
                && strength(wrapper.kind()) == event.strength(),
            "Reference incarnation differs for worker " + worker);
      cursor(worker).reference++;
    }

    List<State> advance(int worker) {
      var thread = threads.get(worker);
      require(!thread.tasks.isEmpty(), "Missing thread continuation");
      var task = thread.tasks.pop();

      switch (task) {
        case Execute execute -> {
          var instruction = execute.instruction();
          switch (instruction) {
            case Instruction.Acquire acquire -> {
              return acquire(worker, acquire);
            }

            case Instruction.ReferenceState state -> {
              return state(worker, state);
            }

            case Instruction.IfPresent present -> {
              boolean value = machine.present(present.name());
              machine.marker(present.id(), ExecutionTrace.Kind.BRANCH, value ? 1 : 0);
              emit(worker);
              if (value) {
                machine.enterScope();
                thread.tasks.push(new Leave(0));
                thread.body(present.body());
              }
            }

            case Instruction.Scope scope -> {
              machine.step(scope.id(), false);
              emit(worker);
              machine.enterScope();
              thread.tasks.push(new Leave(0));
              thread.body(scope.body());
            }

            case Instruction.Repeat repeat -> {
              machine.step(repeat.id(), false);
              emit(worker);
              thread.tasks.push(new Iterate(repeat, repeat.count()));
            }

            case Instruction.Parallel parallel -> {
              machine.step(parallel.id(), false);
              emit(worker);
              long round = machine.visit(parallel.id());
              group = parallel;
              thread.blocked = true;
              thread.tasks.push(new Join(parallel));
              races = new PhaseRace(budget);
              for (var declaration : parallel.workers()) {
                var child =
                    new ThreadState(machine.workerContext(declaration.id(), parallel.id(), round));
                child.tasks.push(new End(parallel.id()));
                child.body(declaration.body());
                child.tasks.push(new Start(parallel.id()));
                threads.put(declaration.id(), child);
              }
            }

            case Instruction.Checkpoint point -> {
              machine.marker(point.id(), ExecutionTrace.Kind.OPERATION, 0);
              emit(worker);
              long visit = machine.visit(point.id());
              capture();
              checkpoints.add(new OracleResult.Checkpoint(point.id(), visit, graph()));
            }

            case Instruction.PollQueue poll -> poll(worker, poll);
            case Instruction.Operation operation -> operation(worker, operation);
          }
        }

        case Iterate iterate -> {
          if (iterate.remaining() > 0) {
            machine.step(iterate.instruction().id(), true);
            emit(worker);
            machine.enterScope();
            thread.tasks.push(new Iterate(iterate.instruction(), iterate.remaining() - 1));
            thread.tasks.push(new Leave(0));
            thread.body(iterate.instruction().body());
          }
        }

        case Leave leave -> {
          machine.leaveScope();
          if (leave.acquisition() != 0) {
            machine.marker(leave.acquisition(), ExecutionTrace.Kind.ACQUIRE_END, 0);
            emit(worker);
          }
        }

        case Start start -> {
          machine.marker(start.group(), ExecutionTrace.Kind.WORKER_START, 0);
          emit(worker);
        }

        case End end -> {
          machine.leaveScope();
          machine.marker(end.group(), ExecutionTrace.Kind.WORKER_END, 0);
          emit(worker);
          thread.ended = true;
        }

        case Phase phase -> {
          machine.marker(phase.barrier(), ExecutionTrace.Kind.PHASE_START, 0);
          emit(worker);
        }

        case Join join -> {
          for (var declaration : join.group().workers()) {
            machine.marker(join.group().id(), ExecutionTrace.Kind.JOIN, declaration.id());
            emit(worker);
          }
          joined = true;
        }

        case Finish ignored -> {
          capture();
          finished = true;
        }
      }
      return List.of(this);
    }

    void operation(int worker, Instruction.Operation operation) {
      var access = machine.memory(operation);
      Value.Reference wrapped =
          operation instanceof Instruction.ClearReference clear
              ? wrapper(clear.owner(), clear.index())
              : null;
      machine.apply(operation);
      if (operation instanceof Instruction.ReadRoot read) machine.referenceReadMarker(read.name());
      else if (operation instanceof Instruction.ReadReference read)
        machine.referenceReadMarker(read.name());
      emit(worker);
      races.access(worker, access);
      if (operation instanceof Instruction.ClearReference) {
        identity(worker, ReferenceHistory.Kind.CLEAR, wrapped, null);
        frontier.programClear(wrapped.wrapper());
      }
      if (operation instanceof Instruction.WriteReference write
          && write.kind() != Value.ReferenceKind.STRONG) {
        var value = machine.referenceSlot(write.owner(), write.index());
        identity(worker, ReferenceHistory.Kind.INSTALL, value, value.target());
        frontier.register(value);
      }
      String result =
          switch (operation) {
            case Instruction.ReadRoot read -> read.name();
            case Instruction.ReadReference read -> read.name();
            case Instruction.Exchange exchange when exchange.location().reference() ->
                exchange.name();
            default -> null;
          };
      if (result != null)
        identity(
            worker,
            ReferenceHistory.Kind.STRONG_READ,
            null,
            ((Value.Reference) machine.bound(result)).target());
      if (operation instanceof Instruction.Barrier barrier) {
        threads.get(worker).blocked = true;
        threads.get(worker).tasks.push(new Phase(barrier.id()));
      }
    }

    Value.Reference wrapper(String owner, int index) {
      var value = machine.referenceSlot(owner, index);
      if (value.kind() == Value.ReferenceKind.STRONG)
        throw new GirException("Reference operation requires a wrapper");
      return value;
    }

    List<State> acquire(int worker, Instruction.Acquire acquire) {
      var value = wrapper(acquire.owner(), acquire.index());
      var choices =
          audit
              ? value.target() == null
                  ? Collections.<ObjectId>singletonList(null)
                  : Arrays.asList(value.target(), null)
              : Collections.singletonList(
                  target(identity(worker, ReferenceHistory.Kind.ACQUIRE).target()));
      var results = new ArrayList<State>();
      for (var target : choices) {
        var next = new State(this);
        var thread = next.threads.get(worker);
        next.machine.activate(thread.context, thread.phase);
        // A recorded target must be legal before it becomes a strong root or controls the body.
        if (!next.frontier.acquire(value.wrapper(), target)) continue;
        next.races.access(worker, next.machine.referenceAccess(acquire.owner(), acquire.index()));
        if (target != null) next.machine.bindAcquired(acquire, target);
        next.machine.marker(acquire.id(), ExecutionTrace.Kind.ACQUIRE, target == null ? 0 : 1);
        next.emit(worker);
        next.identity(worker, ReferenceHistory.Kind.ACQUIRE, value, target);
        if (target != null) {
          thread.tasks.push(new Leave(acquire.id()));
          thread.body(acquire.body());
        }
        results.add(next);
      }
      return results;
    }

    List<State> state(int worker, Instruction.ReferenceState state) {
      var value = wrapper(state.owner(), state.index());
      var results = new ArrayList<State>();
      boolean[] choices = new boolean[] {false, true};
      if (!audit) {
        var cursor = cursor(worker);
        var events = traces.getOrDefault(worker, List.of());
        require(cursor.scalar < events.size(), "Missing clearing-state read");
        long valueRead = events.get(cursor.scalar).value();
        require(valueRead == 0 || valueRead == 1, "Invalid clearing-state value");
        choices = new boolean[] {valueRead == 1};
      }
      for (boolean cleared : choices) {
        var next = new State(this);
        var thread = next.threads.get(worker);
        next.machine.activate(thread.context, thread.phase);
        if (!next.frontier.observeCleared(value.wrapper(), cleared)) continue;
        next.races.access(worker, next.machine.referenceAccess(state.owner(), state.index()));
        next.machine.referenceState(state, cleared);
        next.emit(worker);
        next.identity(worker, ReferenceHistory.Kind.STATE, value, null);
        results.add(next);
      }
      return results;
    }

    void poll(int worker, Instruction.PollQueue poll) {
      // Poll results cannot affect GIR control/data, so empty polls suffice for the race audit.
      // Matching still checks actual deliveries, including delay and duplicate-incarnation rules.
      WrapperId id = audit ? null : id(identity(worker, ReferenceHistory.Kind.QUEUE).wrapper());
      require(id == null || frontier.known(id), "Queue returned an unknown incarnation");
      require(frontier.poll(id), "Impossible or duplicate queue delivery");
      machine.marker(poll.id(), ExecutionTrace.Kind.QUEUE_POLL, id == null ? 0 : 1);
      emit(worker);
      Value.Reference wrapper =
          id == null ? null : new Value.Reference(frontier.instance(id).kind(), null, id);
      identity(worker, ReferenceHistory.Kind.QUEUE, wrapper, null);
    }

    void capture() {
      machine.chargeSnapshot(graph().accessible());
      if (audit) {
        checkpoint++;
        return;
      }
      if (checkpoint >= observations.size()) {
        checkpoint++;
        incomplete = true;
        return;
      }
      var observation = observations.get(checkpoint++);
      if (observation.graph().isEmpty()) {
        incomplete = true;
        return;
      }
      var mismatch = frontier.match(graph(), observation.graph().orElseThrow());
      require(mismatch.isEmpty(), "Checkpoint " + checkpoint + ": " + mismatch);
    }

    /** Completed joins/barriers order prior accesses before resumed work, allowing fresh clocks. */
    void settle() {
      if (group == null) return;
      var workers =
          group.workers().stream().map(declaration -> threads.get(declaration.id())).toList();
      if (workers.stream().allMatch(thread -> thread.ended)) {
        for (var declaration : group.workers()) threads.remove(declaration.id());
        threads.get(0).blocked = false;
        group = null;
        races = new PhaseRace(budget);
      } else if (workers.stream().allMatch(thread -> thread.blocked)) {
        // Static validation requires equal, top-level barriers, so these workers share a phase.
        for (var thread : workers) {
          thread.blocked = false;
          thread.phase++;
        }
        races = new PhaseRace(budget);
      }
    }

    void complete() {
      if (checkpoint != observations.size()) incomplete = true;
      for (var entry : traces.entrySet())
        require(
            cursor(entry.getKey()).scalar == entry.getValue().size(),
            "Unexpected scalar trace suffix");
      for (var entry : identities.entrySet())
        require(
            cursor(entry.getKey()).reference == entry.getValue().size(),
            "Unexpected reference history suffix");
    }
  }

  private static int strength(Value.ReferenceKind kind) {
    return kind == Value.ReferenceKind.SOFT ? 1 : 2;
  }

  private static WrapperId id(ReferenceHistory.Identity value) {
    return value == null
        ? null
        : new WrapperId(value.worker(), value.site(), value.occurrence(), value.slot());
  }

  private static ObjectId target(ReferenceHistory.Target value) {
    return value == null ? null : new ObjectId(value.worker(), value.site(), value.occurrence());
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new NoMatch(message);
  }
}
