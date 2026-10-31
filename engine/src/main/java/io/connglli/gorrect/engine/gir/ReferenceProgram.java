package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Detects programs needing a conditional reference history rather than a fixed oracle result. */
public final class ReferenceProgram {
  private ReferenceProgram() {}

  public static boolean hasParallel(GirProgram program) {
    return program.instructions().anyMatch(Instruction.Parallel.class::isInstance);
  }

  public static boolean usesReferences(GirProgram program) {
    return usesFirstClass(program)
        || program.objects().stream()
            .flatMap(object -> object.references().stream())
            .anyMatch(value -> value.kind() != Value.ReferenceKind.STRONG)
        || program.instructions().anyMatch(ReferenceProgram::usesReferences);
  }

  public static boolean usesFirstClass(GirProgram program) {
    return program.shapes().stream().anyMatch(Shape::wrapper);
  }

  /** A conservative location/type proof over all syntax; a saved choice cannot bypass it. */
  public static List<String> firstClassGaps(GirProgram program) {
    if (!usesFirstClass(program)) return List.of();
    var gaps = new ArrayList<String>();
    var shapes = new HashMap<String, Shape>();
    program.shapes().forEach(shape -> shapes.put(shape.name(), shape));
    var types = GirValidator.referenceTypes(program);
    var registered = new HashSet<String>();
    var instructions = program.instructions().toList();
    for (var object : program.objects())
      for (int i = 0; i < object.references().size(); i++)
        if (object.references().get(i).kind() != Value.ReferenceKind.STRONG)
          registered.add(object.shape() + ":" + i);
    for (var instruction : instructions)
      if (instruction instanceof Instruction.WriteReference op
          && op.kind() != Value.ReferenceKind.STRONG) {
        registered.add(types.owners().get(op.id()).name() + ":" + op.index());
        var value = types.values().get(op.id());
        if (value != null && value.wrapper())
          gaps.add("Legacy slot wrappers require ordinary referent operands");
      }
    for (var instruction : instructions) {
      if (instruction instanceof Instruction.ReadReference op
          && shapes.get(op.shape()).wrapper()
          && registered.contains(types.owners().get(op.id()).name() + ":" + op.index()))
        gaps.add("Registered legacy wrapper exposure is unsupported");
      if (instruction instanceof Instruction.Acquire op
          && !op.receiver()
          && shapes.get(op.shape()).wrapper())
        gaps.add("Legacy slot acquisition cannot expose wrapper referents");
    }
    if (HistoryProgram.usesConcurrentHistory(program))
      gaps.add("First-class wrapper atomic/concurrent histories are unsupported");
    return List.copyOf(gaps);
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
