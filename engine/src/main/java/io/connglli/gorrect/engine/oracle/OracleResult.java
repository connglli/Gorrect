package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import java.util.List;

/** Immutable final obligations and checkpoint visits in execution order. */
public record OracleResult(Graph finalGraph, List<Checkpoint> checkpoints, long steps) {
  public OracleResult {
    checkpoints = List.copyOf(checkpoints);
  }

  /**
   * A checkpoint visit. The operation ID maps to an optional source label; occurrence starts at one
   * for each instruction.
   */
  public record Checkpoint(int operation, long occurrence, Graph graph) {}
}
