package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syntax-only storage/control bounds. OracleResult carries quotas and checkpoint visits here; its
 * event values and graphs are placeholders, never verification predictions. Conditional bodies are
 * planned as taken to reserve their maximum storage.
 */
public final class HistoryPlan {
  private final GirProgram program;
  private final OracleLimits limits;
  private final List<ExecutionTrace.Event> events = new ArrayList<>();
  private final List<OracleResult.Checkpoint> checkpoints = new ArrayList<>();
  private final Map<Integer, Long> visits = new HashMap<>();
  private ArrayDeque<Map<String, Integer>> scopes = new ArrayDeque<>();
  private long objects, slots;
  private int worker, group, phase;
  private long round;
  private List<OracleResult.Checkpoint> workerCheckpoints;

  private HistoryPlan(GirProgram program, OracleLimits limits) {
    this.program = program;
    this.limits = limits;
  }

  public static OracleResult plan(GirProgram program, OracleLimits limits) {
    GirValidator.validate(program);
    var plan = new HistoryPlan(program, limits);
    new GraphMachine(program, limits); // Apply the same initial graph/wrapper bounds as execution.
    plan.objects =
        program.objects().size()
            + program.objects().stream()
                .flatMap(object -> object.references().stream())
                .filter(value -> value.kind() != Value.ReferenceKind.STRONG)
                .count();
    for (var object : program.objects()) plan.slots += plan.shape(object.shape()).slots();
    plan.block(program.body(), null, 0);
    return new OracleResult(
        new Graph(Map.of(), Map.of(), Map.of()), plan.checkpoints, plan.events.size(), plan.events);
  }

  private void event(int operation, ExecutionTrace.Kind kind) {
    if (events.size() >= limits.steps())
      throw new OracleLimitException("Conditional control/storage step bound exhausted");
    events.add(new ExecutionTrace.Event(operation, kind, 0, worker, group, round, phase));
  }

  private void block(List<Instruction> body, String acquired, int acquiredDeclaration) {
    scopes.push(new HashMap<>());
    if (acquired != null) scopes.peek().put(acquired, acquiredDeclaration);
    for (var instruction : body)
      switch (instruction) {
        case Instruction.Parallel parallel -> {
          event(parallel.id(), ExecutionTrace.Kind.OPERATION);
          long visit = visits.merge(parallel.id(), 1L, Long::sum);
          var callerScopes = scopes;
          var cuts = new ArrayList<List<OracleResult.Checkpoint>>();
          for (var declaration : parallel.workers()) {
            scopes = new ArrayDeque<>();
            worker = declaration.id();
            group = parallel.id();
            round = visit;
            phase = 0;
            workerCheckpoints = new ArrayList<>();
            event(parallel.id(), ExecutionTrace.Kind.WORKER_START);
            block(declaration.body(), null, 0);
            event(parallel.id(), ExecutionTrace.Kind.WORKER_END);
            cuts.add(workerCheckpoints);
          }
          workerCheckpoints = null;
          for (int index = 0; index < cuts.getFirst().size(); index++) {
            var canonical = cuts.getFirst().get(index);
            var handles = new HashMap<Integer, Value.Reference>();
            callerScopes.forEach(
                scope -> scope.values().forEach(id -> handles.put(id, Value.Reference.NULL)));
            for (var workerCuts : cuts) handles.putAll(workerCuts.get(index).graph().handles());
            checkpoints.add(
                new OracleResult.Checkpoint(
                    canonical.operation(),
                    canonical.occurrence(),
                    new Graph(Map.of(), Map.of(), handles)));
          }
          scopes = callerScopes;
          worker = 0;
          group = 0;
          round = 0;
          phase = 0;
          for (var declaration : parallel.workers()) event(parallel.id(), ExecutionTrace.Kind.JOIN);
        }
        case Instruction.IfPresent present -> {
          event(present.id(), ExecutionTrace.Kind.BRANCH);
          block(present.body(), null, 0);
        }
        case Instruction.Scope scope -> {
          event(scope.id(), ExecutionTrace.Kind.OPERATION);
          block(scope.body(), null, 0);
        }
        case Instruction.Repeat repeat -> {
          event(repeat.id(), ExecutionTrace.Kind.OPERATION);
          for (long index = 0; index < repeat.count(); index++) {
            event(repeat.id(), ExecutionTrace.Kind.ITERATION);
            block(repeat.body(), null, 0);
          }
        }
        case Instruction.Acquire acquire -> {
          event(acquire.id(), ExecutionTrace.Kind.ACQUIRE);
          block(acquire.body(), acquire.name(), acquire.id());
          event(acquire.id(), ExecutionTrace.Kind.ACQUIRE_END);
        }
        case Instruction.Operation operation -> {
          event(
              operation.id(),
              operation instanceof Instruction.Barrier
                  ? ExecutionTrace.Kind.BARRIER
                  : ExecutionTrace.Kind.OPERATION);
          if (operation instanceof Instruction.Barrier) {
            phase++;
            event(operation.id(), ExecutionTrace.Kind.PHASE_START);
          }
          if (operation instanceof Instruction.Allocate allocate) {
            if (++objects > limits.objects()
                || shape(allocate.shape()).slots() > limits.slots() - slots)
              throw new OracleLimitException("Conditional allocation bound exhausted");
            slots += shape(allocate.shape()).slots();
            scopes.peek().put(allocate.name(), allocate.id());
          } else if (operation instanceof Instruction.ReadRoot read)
            scopes.peek().put(read.name(), read.id());
          else if (operation instanceof Instruction.ReadReference read)
            scopes.peek().put(read.name(), read.id());
          else if (operation instanceof Instruction.Exchange exchange
              && exchange.location().reference()) scopes.peek().put(exchange.name(), exchange.id());
          else if (operation instanceof Instruction.Release release)
            scopes.peek().remove(release.name());
          else if (operation instanceof Instruction.WriteReference write
              && write.kind() != Value.ReferenceKind.STRONG
              && ++objects > limits.objects())
            throw new OracleLimitException("Conditional wrapper bound exhausted");
          else if (operation instanceof Instruction.Checkpoint checkpoint) {
            var handles = new HashMap<Integer, Value.Reference>();
            scopes.forEach(
                scope -> scope.values().forEach(id -> handles.put(id, Value.Reference.NULL)));
            (worker == 0 ? checkpoints : workerCheckpoints)
                .add(
                    new OracleResult.Checkpoint(
                        checkpoint.id(),
                        visits.merge(checkpoint.id(), 1L, Long::sum),
                        new Graph(Map.of(), Map.of(), handles)));
            if (worker != 0) {
              phase++;
              event(operation.id(), ExecutionTrace.Kind.PHASE_START);
            }
          }
        }
      }
    scopes.pop();
  }

  private Shape shape(String name) {
    return program.shapes().stream()
        .filter(shape -> shape.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
