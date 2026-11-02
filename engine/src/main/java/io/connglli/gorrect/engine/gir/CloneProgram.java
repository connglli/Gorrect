package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Whole-source boundary for strong sequential shallow clones. */
public final class CloneProgram {
  private CloneProgram() {}

  /** Includes skipped blocks: support cannot depend on which path happened to execute. */
  public static boolean usesClones(GirProgram program) {
    return program.instructions().anyMatch(Instruction.Clone.class::isInstance);
  }

  /** Exact lexical owner types select Cloneable and mutable diagnostic roles, never payload. */
  public static Set<Shape> objectShapes(GirProgram program) {
    if (!usesClones(program)) return Set.of();
    return GirValidator.cloneShapes(program).values().stream()
        .filter(shape -> shape.kind() == Shape.Kind.OBJECT)
        .collect(Collectors.toUnmodifiableSet());
  }

  public static boolean usesObjectClones(GirProgram program) {
    return !objectShapes(program).isEmpty();
  }

  /** Bounds the single generated qualification method, including clones in skipped source. */
  static void validateBounds(Map<Integer, Shape> cloneShapes) {
    long bytes = 0;
    for (var shape : Set.copyOf(cloneShapes.values())) {
      if (shape.kind() != Shape.Kind.OBJECT) continue;
      // Each copied slot emits initialization, comparison, mutation and independence checks.
      // Reserve branch widening, constants and javac scaffolding below the JVM's 64 KiB limit.
      if (bytes > 60_000 - 256 || shape.slots() > (60_000 - bytes - 256) / 128)
        throw new GirException("Conservative object clone qualification code limit exceeded");
      bytes += 256 + shape.slots() * 128;
    }
  }

  /** Array-only compositions retain their independent contracts. */
  public static List<String> unsupported(GirProgram program) {
    if (!usesClones(program)) return List.of();
    if (usesObjectClones(program)
        && (NativeRootProgram.selected(program)
            || MethodProgram.selected(program)
            || !program.queues().isEmpty()
            || program.shapes().stream().anyMatch(Shape::wrapper)
            || program.instructions().anyMatch(instruction -> !plain(instruction))))
      return List.of(
          "Object cloning requires strong sequential plain execution without native roots or"
              + " methods");
    return ReferenceProgram.usesReferences(program)
            || ReferenceProgram.hasParallel(program)
            || HistoryProgram.usesConcurrentHistory(program)
        ? List.of("Cloning currently requires a strong sequential program")
        : List.of();
  }

  private static boolean plain(Instruction instruction) {
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
