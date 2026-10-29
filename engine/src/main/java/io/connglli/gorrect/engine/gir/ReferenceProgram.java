package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;
import java.util.List;

/** Detects programs needing a conditional reference history rather than a fixed oracle result. */
public final class ReferenceProgram {
  private ReferenceProgram() {}

  public static boolean hasParallel(GirProgram program) {
    return hasParallel(program.body());
  }

  private static boolean hasParallel(List<Instruction> body) {
    for (var instruction : body) {
      if (instruction instanceof Instruction.Parallel) return true;
      if (instruction instanceof Instruction.IfPresent present && hasParallel(present.body()))
        return true;
      if (instruction instanceof Instruction.Scope scope && hasParallel(scope.body())) return true;
      if (instruction instanceof Instruction.Repeat repeat && hasParallel(repeat.body()))
        return true;
      if (instruction instanceof Instruction.Acquire acquire && hasParallel(acquire.body()))
        return true;
    }
    return false;
  }

  public static boolean usesReferences(GirProgram program) {
    return program.objects().stream()
            .flatMap(object -> object.references().stream())
            .anyMatch(value -> value.kind() != Value.ReferenceKind.STRONG)
        || usesReferences(program.body());
  }

  private static boolean usesReferences(List<Instruction> body) {
    for (var instruction : body) {
      boolean found =
          switch (instruction) {
            case Instruction.Acquire ignored -> true;
            case Instruction.ClearReference ignored -> true;
            case Instruction.ReferenceState ignored -> true;
            case Instruction.PollQueue ignored -> true;
            case Instruction.WriteReference op -> op.kind() != Value.ReferenceKind.STRONG;
            case Instruction.IfPresent present -> usesReferences(present.body());
            case Instruction.Scope scope -> usesReferences(scope.body());
            case Instruction.Repeat repeat -> usesReferences(repeat.body());
            case Instruction.Parallel parallel ->
                parallel.workers().stream().anyMatch(worker -> usesReferences(worker.body()));
            default -> false;
          };
      if (found) return true;
    }
    return false;
  }
}
