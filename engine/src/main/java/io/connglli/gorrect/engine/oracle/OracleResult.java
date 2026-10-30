package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;

/**
 * Immutable graph/control description whose meaning follows its producer. {@link Oracle} supplies
 * exact graph and scalar obligations. {@link HistoryPlan} supplies storage/control bounds with
 * placeholder values; those values must never be used as predictions. Matched histories supply
 * validated events and shared payload state, not one selected clearing graph.
 */
public record OracleResult(
    Graph finalGraph, List<Checkpoint> checkpoints, long steps, List<ExecutionTrace.Event> events) {
  public OracleResult {
    checkpoints = List.copyOf(checkpoints);
    events = List.copyOf(events);
  }

  /**
   * A checkpoint visit. The operation ID maps to an optional source label; occurrence starts at one
   * for each instruction. Graph has the producer-specific meaning described by {@link
   * OracleResult}.
   */
  public record Checkpoint(int operation, long occurrence, Graph graph) {}
}
