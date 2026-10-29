package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;

/**
 * Immutable graph obligations, checkpoint visits, and expected scalar events in execution order.
 */
public record OracleResult(
    Graph finalGraph, List<Checkpoint> checkpoints, long steps, List<ExecutionTrace.Event> events) {
  public OracleResult {
    checkpoints = List.copyOf(checkpoints);
    events = List.copyOf(events);
  }

  /**
   * A checkpoint visit. The operation ID maps to an optional source label; occurrence starts at one
   * for each instruction.
   */
  public record Checkpoint(int operation, long occurrence, Graph graph) {}
}
