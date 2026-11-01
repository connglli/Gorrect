package io.connglli.gorrect.engine.gir;

import java.util.List;

/** Whole-source support boundary for structured Java monitor blocks. */
public final class MonitorProgram {
  private MonitorProgram() {}

  /** Includes skipped arms and zero-iteration bodies. */
  public static boolean usesMonitors(GirProgram program) {
    return program.instructions().anyMatch(Instruction.Monitor.class::isInstance);
  }

  /** Reference and concurrent histories require independent monitor contracts. */
  public static List<String> unsupported(GirProgram program) {
    if (!usesMonitors(program)) return List.of();
    return ReferenceProgram.usesReferences(program)
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
        ? List.of("Monitors currently require a strong sequential program")
        : List.of();
  }
}
