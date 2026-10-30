package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Capture quotas and lexical declaration coverage; history-plan graphs supply no predicted values.
 */
public final class CheckpointPlan {
  public record Visit(int operation, long occurrence) {}

  private final Map<Integer, Long> maxima = new HashMap<>();
  private final Map<Integer, Set<Integer>> declarations = new HashMap<>();
  private final List<Visit> visits;

  public CheckpointPlan(OracleResult plan) {
    var order = new ArrayList<Visit>();
    for (var point : plan.checkpoints()) {
      maxima.merge(point.operation(), point.occurrence(), Math::max);
      var keys = Set.copyOf(point.graph().handles().keySet());
      var previous = declarations.putIfAbsent(point.operation(), keys);
      if (previous != null && !previous.equals(keys))
        throw new IllegalArgumentException("Checkpoint declaration scope changes across visits");
      order.add(new Visit(point.operation(), point.occurrence()));
    }
    order.add(new Visit(0, 1));
    visits = List.copyOf(order);
  }

  /** Exact order for a deterministic oracle, or maximum visit order for a history storage plan. */
  public List<Visit> visits() {
    return visits;
  }

  public boolean permits(int operation, long occurrence) {
    return operation == 0
        ? occurrence == 1
        : occurrence > 0 && occurrence <= maxima.getOrDefault(operation, 0L);
  }

  /** The lexical scope of an executed site is fixed even when some visits are skipped. */
  public Set<Integer> declarations(int operation) {
    if (operation == 0) return Set.of();
    var result = declarations.get(operation);
    if (result == null) throw new IllegalArgumentException("Unknown checkpoint site");
    return result;
  }

  /**
   * Classifies recorded main-thread markers and appends the final capture. This establishes no
   * branch legality; the interpreter/history checker must still match every recorded event.
   */
  public static List<Visit> recorded(GirProgram program, ExecutionTrace trace) {
    var sites =
        program
            .instructions()
            .filter(instruction -> instruction instanceof Instruction.Checkpoint)
            .map(Instruction::id)
            .collect(Collectors.toSet());
    var counters = new HashMap<Integer, Long>();
    var result = new ArrayList<Visit>();
    for (var event : trace.byWorker().getOrDefault(0, List.of()))
      if (event.kind() == ExecutionTrace.Kind.OPERATION && sites.contains(event.operation()))
        result.add(new Visit(event.operation(), counters.merge(event.operation(), 1L, Long::sum)));
    result.add(new Visit(0, 1));
    return List.copyOf(result);
  }
}
