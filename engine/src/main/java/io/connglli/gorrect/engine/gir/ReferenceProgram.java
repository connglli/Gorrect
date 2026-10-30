package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;

/** Detects programs needing a conditional reference history rather than a fixed oracle result. */
public final class ReferenceProgram {
  private ReferenceProgram() {}

  public static boolean hasParallel(GirProgram program) {
    return program.instructions().anyMatch(Instruction.Parallel.class::isInstance);
  }

  public static boolean usesReferences(GirProgram program) {
    return program.objects().stream()
            .flatMap(object -> object.references().stream())
            .anyMatch(value -> value.kind() != Value.ReferenceKind.STRONG)
        || program.instructions().anyMatch(ReferenceProgram::usesReferences);
  }

  private static boolean usesReferences(Instruction instruction) {
    return switch (instruction) {
      case Instruction.Acquire ignored -> true;
      case Instruction.ClearReference ignored -> true;
      case Instruction.ReferenceState ignored -> true;
      case Instruction.PollQueue ignored -> true;
      case Instruction.WriteReference op -> op.kind() != Value.ReferenceKind.STRONG;
      default -> false;
    };
  }
}
