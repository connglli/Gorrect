package io.connglli.gorrect.engine.gir;

import java.util.List;

/** Source-level boundary for the initial strong, sequential array-clone contract. */
public final class CloneProgram {
  private CloneProgram() {}

  /** Includes skipped blocks: support cannot depend on which path happened to execute. */
  public static boolean usesClones(GirProgram program) {
    return program.instructions().anyMatch(Instruction.Clone.class::isInstance);
  }

  /** Reference-copy cohorts and bulk concurrent accesses need independent contracts. */
  public static List<String> unsupported(GirProgram program) {
    if (!usesClones(program)) return List.of();
    return ReferenceProgram.usesReferences(program)
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
        ? List.of("Array cloning currently requires a strong sequential program")
        : List.of();
  }
}
