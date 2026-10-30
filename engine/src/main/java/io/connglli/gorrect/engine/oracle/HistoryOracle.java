package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates choices before they influence graph interpretation, then preserves all legal clearings.
 */
public final class HistoryOracle {
  /**
   * One checkpoint's logical projection. Empty optional means capture coverage is unusable, not
   * that the observed heap is empty; problems explain that gap. Present graphs still need checking.
   */
  public record Observation(Optional<Graph> graph, List<String> problems) {
    public Observation {
      problems = List.copyOf(problems);
    }
  }

  /**
   * Verdict and diagnostics for a matched history. Execution is non-null only after a legal path's
   * control/results match; incomplete graph observations can still leave its verdict inconclusive.
   * It retains shared payload and validated events rather than one selected clearing graph.
   * InvalidWorkload identifies a rejected precondition reached through validated choices;
   * diagnosing a failed run additionally requires the entire recorded prefix to be validated.
   */
  public record Result(
      ExactVerifier.Verdict verdict,
      List<String> problems,
      OracleResult execution,
      boolean invalidWorkload) {
    public Result {
      problems = List.copyOf(problems);
    }
  }

  private final GraphMachine machine;
  private final ReferenceFrontier frontier;
  private final ExecutionTrace trace;
  private final ReferenceHistory history;
  private final List<Observation> observations;
  private final List<OracleResult.Checkpoint> checkpoints = new ArrayList<>();
  private final List<String> problems = new ArrayList<>();
  private int position, referencePosition, checkpoint;
  private boolean incomplete;
  private boolean prefix;

  private HistoryOracle(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<Observation> observations,
      HistoryLimits limits) {
    machine = new GraphMachine(program, OracleLimits.DEFAULT);
    frontier = new ReferenceFrontier(limits);
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
                          if (value.kind() != Value.ReferenceKind.STRONG) frontier.register(value);
                        }));
    this.trace = trace;
    this.history = history;
    this.observations = observations;
  }

  /**
   * Validates a complete recorded case, checking choices before they affect graph interpretation.
   * Contended/concurrent-reference programs delegate to {@link ConcurrentHistory#check}. Inputs
   * remain unchanged. Incomplete evidence or exhausted search yields INCONCLUSIVE; impossible
   * complete observations yield VIOLATED.
   */
  public static Result check(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<Observation> observations,
      HistoryLimits limits) {
    if (HistoryProgram.usesConcurrentHistory(program))
      return ConcurrentHistory.check(program, trace, history, observations, limits);
    return check(program, trace, history, observations, limits, false);
  }

  /**
   * Diagnoses a sequential conditional runtime failure from its recorded prefix. InvalidWorkload
   * becomes true only when the entire prefix is legal and reaches the rejected precondition;
   * missing, impossible, or inconclusive prefixes do not establish that classification.
   */
  public static Result checkFailure(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<Observation> observations,
      HistoryLimits limits) {
    return check(program, trace, history, observations, limits, true);
  }

  /** Orphan identity records indicate missing cross-channel evidence, not a heap refutation. */
  static boolean linked(ExecutionTrace trace, ReferenceHistory history) {
    var workers = trace.byWorker();
    for (var event : history.events())
      if (event.sequence() <= 0
          || event.sequence() > workers.getOrDefault(event.worker(), List.of()).size())
        return false;
    return true;
  }

  private static Result check(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<Observation> observations,
      HistoryLimits limits,
      boolean prefix) {
    GirValidator.validate(program);
    if ((!prefix && (!trace.complete() || !history.complete()))
        || trace.run() != history.run()
        || !linked(trace, history))
      return new Result(
          ExactVerifier.Verdict.INCONCLUSIVE,
          List.of("Reference history is incomplete or belongs to another run"),
          null,
          false);

    HistoryOracle checker = null;

    try {
      checker = new HistoryOracle(program, trace, history, observations, limits);
      checker.prefix = prefix;
      checker.execute(program.body());

      if (prefix) throw new EndOfPrefix();
      checker.frontier.expand(checker.machine.graph());
      checker.machine.leaveScope();
      checker.frontier.expand(checker.machine.graph());
      checker.snapshot(0, 1);
      require(checker.position == trace.events().size(), "Unexpected scalar trace suffix");
      require(
          checker.referencePosition == history.events().size(),
          "Unexpected reference history suffix");
      if (checker.checkpoint != observations.size()) {
        checker.incomplete = true;
        checker.problems.add("Missing or extra graph observations for the validated path");
      }

      var execution =
          new OracleResult(
              checker.machine.graph(),
              checker.checkpoints,
              checker.machine.steps(),
              checker.machine.events());

      return new Result(
          checker.incomplete ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.SATISFIED,
          checker.problems,
          execution,
          false);
    } catch (EndOfPrefix end) {

      return new Result(
          ExactVerifier.Verdict.INCONCLUSIVE,
          List.of("Recorded prefix does not establish an invalid workload"),
          null,
          false);
    } catch (HistoryLimitException | OracleLimitException limit) {

      return new Result(
          ExactVerifier.Verdict.INCONCLUSIVE, List.of(limit.getMessage()), null, false);
    } catch (GirException invalid) {
      if (prefix
          && (checker == null
              || checker.position != trace.events().size()
              || checker.referencePosition != history.events().size()
              || checker.incomplete
              || checker.checkpoint != observations.size()))
        return new Result(
            ExactVerifier.Verdict.INCONCLUSIVE,
            List.of("Invalid precondition has unvalidated prefix evidence"),
            null,
            false);

      return new Result(
          ExactVerifier.Verdict.INCONCLUSIVE,
          List.of("Invalid workload on a legal recorded path: " + invalid.getMessage()),
          null,
          true);
    } catch (Violation invalid) {

      return new Result(ExactVerifier.Verdict.VIOLATED, List.of(invalid.getMessage()), null, false);
    }
  }

  private void execute(List<Instruction> body) {
    for (var instruction : body) {
      frontier.expand(machine.graph());

      switch (instruction) {
        case Instruction.IfPresent present -> {
          boolean value = machine.present(present.name());
          machine.marker(present.id(), ExecutionTrace.Kind.BRANCH, value ? 1 : 0);
          expect();
          if (value) scoped(present.body());
        }

        case Instruction.Parallel ignored ->
            throw new GirException("Parallel reference histories are unsupported");
        case Instruction.Scope scope -> {
          machine.step(scope.id(), false);
          expect();
          scoped(scope.body());
        }

        case Instruction.Repeat repeat -> {
          machine.step(repeat.id(), false);
          expect();
          for (long index = 0; index < repeat.count(); index++) {
            frontier.expand(machine.graph());
            machine.step(repeat.id(), true);
            expect();
            scoped(repeat.body());
          }
        }

        case Instruction.Acquire acquire -> acquire(acquire);
        case Instruction.Checkpoint point -> {
          machine.marker(point.id(), ExecutionTrace.Kind.OPERATION, 0);
          expect();
          long visit = machine.visit(point.id());
          snapshot(point.id(), visit);
          checkpoints.add(new OracleResult.Checkpoint(point.id(), visit, machine.graph()));
        }

        case Instruction.ReferenceState state -> {
          var value = wrapped(state.owner(), state.index());
          long result = choice(state.id(), ExecutionTrace.Kind.REFERENCE_STATE);
          var event = take(ReferenceHistory.Kind.STATE, position + 1);
          subject(event, value);
          require(
              frontier.observeCleared(value.wrapper(), result == 1),
              "Impossible clearing observation at operation " + state.id());
          machine.referenceState(state, result == 1);
          expect();
        }

        case Instruction.PollQueue poll -> {
          long result = choice(poll.id(), ExecutionTrace.Kind.QUEUE_POLL);
          var event = take(ReferenceHistory.Kind.QUEUE, position + 1);
          require(
              (event.wrapper() != null) == (result == 1),
              "Queue result disagrees with its recorded identity");
          var id = id(event.wrapper());
          require(
              id == null || frontier.known(id), "Queue delivered an unknown reference incarnation");
          if (id != null)
            require(
                strength(frontier.instance(id).kind()) == event.strength(),
                "Queue strength differs from the incarnation");
          require(
              frontier.poll(id),
              "Impossible or duplicate queue delivery at operation " + poll.id());
          machine.marker(poll.id(), ExecutionTrace.Kind.QUEUE_POLL, result);
          expect();
        }

        case Instruction.ClearReference clear -> {
          var value = wrapped(clear.owner(), clear.index());
          machine.apply(clear);
          expect();
          subject(take(ReferenceHistory.Kind.CLEAR, position), value);
          frontier.programClear(value.wrapper());
        }

        case Instruction.Operation operation -> {
          machine.apply(operation);
          expect();
          if (operation instanceof Instruction.WriteReference write
              && write.kind() != Value.ReferenceKind.STRONG) {
            var value = machine.referenceSlot(write.owner(), write.index());
            var event = take(ReferenceHistory.Kind.INSTALL, position);
            subject(event, value);
            require(
                Objects.equals(value.target(), target(event.target())),
                "Reference store changed its referent identity");
            frontier.register(value);
          }
        }
      }
    }
  }

  private void scoped(List<Instruction> body) {
    machine.enterScope();
    try {
      execute(body);
      frontier.expand(machine.graph());
    } finally {
      machine.leaveScope();
    }
  }

  private void acquire(Instruction.Acquire operation) {
    var value = wrapped(operation.owner(), operation.index());
    long result = choice(operation.id(), ExecutionTrace.Kind.ACQUIRE);
    var event = take(ReferenceHistory.Kind.ACQUIRE, position + 1);
    subject(event, value);
    ObjectId target = target(event.target());
    require(
        (target != null) == (result == 1),
        "Acquisition branch disagrees with its recorded referent");
    require(
        frontier.acquire(value.wrapper(), target),
        "Impossible acquisition at operation " + operation.id());
    if (target != null) machine.bindAcquired(operation, target);
    machine.marker(operation.id(), ExecutionTrace.Kind.ACQUIRE, result);
    expect();
    if (target != null) {
      try {
        execute(operation.body());
        frontier.expand(machine.graph());
      } finally {
        machine.leaveScope();
      }
      machine.marker(operation.id(), ExecutionTrace.Kind.ACQUIRE_END, 0);
      expect();
    }
  }

  private Value.Reference wrapped(String owner, int index) {
    var value = machine.referenceSlot(owner, index);
    if (value.kind() == Value.ReferenceKind.STRONG || value.wrapper() == null)
      throw new GirException("Reference operation requires a soft/weak wrapper");
    return value;
  }

  private void snapshot(int operation, long occurrence) {
    machine.chargeSnapshot(machine.graph().accessible());
    if (checkpoint >= observations.size()) {
      checkpoint++;
      incomplete = true;
      problems.add("Graph observation is missing");
      return;
    }
    var observation = observations.get(checkpoint++);
    if (observation.graph().isEmpty()) {
      incomplete = true;
      problems.addAll(observation.problems());
      return;
    }
    var mismatch = frontier.match(machine.graph(), observation.graph().orElseThrow());
    require(mismatch.isEmpty(), "Checkpoint " + operation + "/" + occurrence + ": " + mismatch);
  }

  private void expect() {
    if (prefix && position == trace.events().size()) throw new EndOfPrefix();
    require(position < trace.events().size(), "Scalar trace is truncated");
    require(
        machine.lastEvent().equals(trace.events().get(position)),
        "Scalar/control trace differs at event " + (position + 1));
    position++;
  }

  private long choice(int operation, ExecutionTrace.Kind kind) {
    if (prefix && position == trace.events().size()) throw new EndOfPrefix();
    require(position < trace.events().size(), "Reference choice is missing");
    var event = trace.events().get(position);
    require(
        event.operation() == operation
            && event.kind() == kind
            && event.worker() == 0
            && event.group() == 0
            && event.round() == 0
            && event.phase() == 0
            && (event.value() == 0 || event.value() == 1),
        "Invalid reference choice at operation " + operation);
    return event.value();
  }

  private ReferenceHistory.Event take(ReferenceHistory.Kind kind, int sequence) {
    if (prefix && referencePosition == history.events().size()) throw new EndOfPrefix();
    require(referencePosition < history.events().size(), "Reference observation is missing");
    var event = history.events().get(referencePosition++);
    require(
        event.worker() == 0 && event.sequence() == sequence && event.kind() == kind,
        "Reference observation order or kind differs from the scalar trace");
    return event;
  }

  private static void subject(ReferenceHistory.Event event, Value.Reference value) {
    require(
        value.wrapper().equals(id(event.wrapper())) && strength(value.kind()) == event.strength(),
        "Wrong reference incarnation or strength");
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
    if (!valid) throw new Violation(message);
  }

  private static final class Violation extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Violation(String message) {
      super(message);
    }
  }

  private static final class EndOfPrefix extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
