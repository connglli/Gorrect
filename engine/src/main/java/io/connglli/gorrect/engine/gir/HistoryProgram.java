package io.connglli.gorrect.engine.gir;

import java.util.List;

/**
 * Selects bounded scheduling only for programs needing contended or concurrent reference semantics.
 */
public final class HistoryProgram {
  private HistoryProgram() {}

  public static boolean usesConcurrentHistory(GirProgram program) {
    return shared(program.body())
        || ReferenceProgram.usesReferences(program) && ReferenceProgram.hasParallel(program);
  }

  private static boolean shared(List<Instruction> body) {
    for (var instruction : body) {
      boolean found =
          switch (instruction) {
            case Instruction.CompareSet ignored -> true;
            case Instruction.Exchange ignored -> true;
            case Instruction.ReadRoot op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.WriteRoot op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.ReadReference op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.WriteReference op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.ReadData op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.WriteData op -> op.mode() == MemoryMode.VOLATILE;
            case Instruction.Scope scope -> shared(scope.body());
            case Instruction.Repeat repeat -> shared(repeat.body());
            case Instruction.Acquire acquire -> shared(acquire.body());
            case Instruction.IfPresent present -> shared(present.body());
            case Instruction.Parallel parallel ->
                parallel.workers().stream().anyMatch(worker -> shared(worker.body()));
            default -> false;
          };
      if (found) return true;
    }
    return false;
  }
}
