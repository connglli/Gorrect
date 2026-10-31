package io.connglli.gorrect.engine.gir;

import java.util.List;

/** Source-level boundary for normal strong sequential array range copies. */
public final class ArrayCopyProgram {
  private ArrayCopyProgram() {}

  /** Includes skipped blocks; support cannot depend on the recorded execution path. */
  public static boolean usesCopies(GirProgram program) {
    return program.instructions().anyMatch(Instruction.CopyArray.class::isInstance);
  }

  /** Reference-copy cohorts and bulk concurrent footprints require separate contracts. */
  public static List<String> unsupported(GirProgram program) {
    if (!usesCopies(program)) return List.of();
    return ReferenceProgram.usesReferences(program)
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
        ? List.of("Array copying currently requires a strong sequential program")
        : List.of();
  }
}
