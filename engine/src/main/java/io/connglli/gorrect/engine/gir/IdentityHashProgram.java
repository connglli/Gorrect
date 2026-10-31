package io.connglli.gorrect.engine.gir;

import java.util.List;

/** Source-level support boundary for opaque identity-hash observations. */
public final class IdentityHashProgram {
  private IdentityHashProgram() {}

  /** Includes skipped blocks, so support does not depend on observed branch choices. */
  public static boolean usesIdentityHash(GirProgram program) {
    return program.instructions().anyMatch(Instruction.IdentityHash.class::isInstance);
  }

  /** Reference histories and concurrent operations need independent hash observation contracts. */
  public static List<String> unsupported(GirProgram program) {
    if (!usesIdentityHash(program)) return List.of();
    return ReferenceProgram.usesReferences(program)
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
        ? List.of("Identity hashes currently require a strong sequential program")
        : List.of();
  }
}
