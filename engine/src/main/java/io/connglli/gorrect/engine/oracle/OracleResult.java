package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;

/**
 * Immutable graph/control description whose meaning follows its producer. {@link Oracle} supplies
 * exact graph/scalar obligations and explicitly opaque identity-hash subjects. {@link HistoryPlan}
 * supplies storage/control bounds with placeholder values; those values must never be used as
 * predictions. Matched histories supply validated events and shared payload state, not one selected
 * clearing graph.
 */
public record OracleResult(
    Graph finalGraph,
    List<Checkpoint> checkpoints,
    long steps,
    List<ExecutionTrace.Event> events,
    List<IdentityHash> identityHashes) {
  /** Producers without opaque observations retain exact scalar semantics. */
  public OracleResult(
      Graph finalGraph,
      List<Checkpoint> checkpoints,
      long steps,
      List<ExecutionTrace.Event> events) {
    this(finalGraph, checkpoints, steps, events, List.of());
  }

  public OracleResult {
    checkpoints = List.copyOf(checkpoints);
    events = List.copyOf(events);
    identityHashes = List.copyOf(identityHashes);
  }

  /**
   * Model-derived subject at a zero-based position in the full event list; null denotes the null
   * operand. The event's value is a placeholder, never a predicted hash. Logical identities do not
   * retain target objects or add graph roots.
   */
  public record IdentityHash(int eventIndex, HeapId subject) {}

  /**
   * A checkpoint visit. The operation ID maps to an optional source label; occurrence starts at one
   * for each instruction. Graph has the producer-specific meaning described by {@link
   * OracleResult}.
   */
  public record Checkpoint(int operation, long occurrence, Graph graph) {}
}
