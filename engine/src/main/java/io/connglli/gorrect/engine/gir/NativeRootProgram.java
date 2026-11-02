package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;
import java.util.List;

/** Whole-source boundary for run-owned strong JNI slots, including skipped instruction bodies. */
public final class NativeRootProgram {
  private NativeRootProgram() {}

  public static List<String> roots(GirProgram program) {
    return program.roots().stream()
        .filter(root -> root.storage() == GirProgram.RootStorage.JNI_GLOBAL)
        .map(GirProgram.Root::name)
        .toList();
  }

  public static boolean selected(GirProgram program) {
    return !roots(program).isEmpty();
  }

  public static List<String> unsupported(GirProgram program) {
    if (!selected(program)) return List.of();
    return ReferenceProgram.usesReferences(program)
            || program.shapes().stream()
                .anyMatch(
                    shape ->
                        switch (shape.kind()) {
                          case OBJECT, REFERENCE_ARRAY, DATA_ARRAY -> false;
                          default -> true;
                        })
            || !program.queues().isEmpty()
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
            || program.instructions().anyMatch(instruction -> !supported(instruction))
        ? List.of("Native roots require a strong sequential program with plain accesses")
        : List.of();
  }

  private static boolean supported(Instruction instruction) {
    return switch (instruction) {
      case Instruction.Allocate ignored -> true;
      case Instruction.Clone ignored -> true;
      case Instruction.CopyArray ignored -> true;
      case Instruction.IdentityHash ignored -> true;
      case Instruction.SameObject ignored -> true;
      case Instruction.Release ignored -> true;
      case Instruction.Checkpoint ignored -> true;
      case Instruction.Collect ignored -> true;
      case Instruction.Scope ignored -> true;
      case Instruction.Repeat ignored -> true;
      case Instruction.IfPresent ignored -> true;
      case Instruction.IfNonzero ignored -> true;
      case Instruction.Monitor ignored -> true;
      case Instruction.ReadRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.ReadReference op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteReference op ->
          op.mode() == MemoryMode.PLAIN && op.kind() == Value.ReferenceKind.STRONG;
      case Instruction.ReadData op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteData op -> op.mode() == MemoryMode.PLAIN;
      default -> false;
    };
  }
}
