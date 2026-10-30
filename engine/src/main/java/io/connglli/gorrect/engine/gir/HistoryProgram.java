package io.connglli.gorrect.engine.gir;

/**
 * Selects bounded scheduling only for programs needing contended or concurrent reference semantics.
 */
public final class HistoryProgram {
  private HistoryProgram() {}

  public static boolean usesConcurrentHistory(GirProgram program) {
    return program.instructions().anyMatch(HistoryProgram::shared)
        || hasWorkerCheckpoints(program)
        || ReferenceProgram.usesReferences(program) && ReferenceProgram.hasParallel(program);
  }

  /** Direct worker cuts require the all-schedules audit even without contended operations. */
  public static boolean hasWorkerCheckpoints(GirProgram program) {
    return program
        .instructions()
        .filter(instruction -> instruction instanceof Instruction.Parallel)
        .map(instruction -> (Instruction.Parallel) instruction)
        .anyMatch(HistoryProgram::hasWorkerCheckpoints);
  }

  public static boolean hasWorkerCheckpoints(Instruction.Parallel parallel) {
    return parallel.workers().stream()
        .flatMap(worker -> worker.body().stream())
        .anyMatch(instruction -> instruction instanceof Instruction.Checkpoint);
  }

  private static boolean shared(Instruction instruction) {
    return switch (instruction) {
      case Instruction.CompareSet ignored -> true;
      case Instruction.Exchange ignored -> true;
      case Instruction.ReadRoot op -> op.mode() == MemoryMode.VOLATILE;
      case Instruction.WriteRoot op -> op.mode() == MemoryMode.VOLATILE;
      case Instruction.ReadReference op -> op.mode() == MemoryMode.VOLATILE;
      case Instruction.WriteReference op -> op.mode() == MemoryMode.VOLATILE;
      case Instruction.ReadData op -> op.mode() == MemoryMode.VOLATILE;
      case Instruction.WriteData op -> op.mode() == MemoryMode.VOLATILE;
      default -> false;
    };
  }
}
