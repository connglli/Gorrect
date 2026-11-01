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

  /** Named resources and explicit enqueue need the selected queue evidence/capture contract. */
  public static boolean usesQueues(GirProgram program) {
    return !program.queues().isEmpty()
        || program
            .instructions()
            .anyMatch(
                instruction ->
                    instruction instanceof Instruction.EnqueueReference
                        || instruction instanceof Instruction.PollQueue poll && poll.named());
  }

  /** Source selection for automatic delivery; a nonliteral operand may evaluate to null. */
  public static boolean usesAutomaticQueues(GirProgram program) {
    return program
        .instructions()
        .anyMatch(
            instruction ->
                instruction instanceof Instruction.AllocateReference allocation
                    && allocation.queue() != null
                    && !(allocation.value() instanceof Operand.Null));
  }

  /**
   * Proves wrapper lifetime without depending on VM pending-list ownership. A constructor binding
   * protects the wrapper until a qualifying top-level publication; that dedicated root has one
   * write and protects it through terminal capture. Other roots and aliases retain their contracts.
   * All syntax counts as a write, including skipped blocks and atomics.
   */
  static List<String> automaticQueueGaps(GirProgram program) {
    var gaps = new ArrayList<String>();
    var writes = new HashMap<String, List<Instruction>>();
    var topLevel = new HashSet<Integer>();
    program.body().forEach(instruction -> topLevel.add(instruction.id()));
    var initialNull = new HashSet<String>();
    program.roots().stream()
        .filter(root -> root.value().heapTarget() == null)
        .forEach(root -> initialNull.add(root.name()));
    var instructions = program.instructions().toList();
    for (var instruction : instructions) {
      String root =
          switch (instruction) {
            case Instruction.WriteRoot write -> write.root();
            case Instruction.CompareSet atomic
                when atomic.location().kind() == Location.Kind.ROOT ->
                atomic.location().owner();
            case Instruction.Exchange atomic when atomic.location().kind() == Location.Kind.ROOT ->
                atomic.location().owner();
            default -> null;
          };
      if (root != null) writes.computeIfAbsent(root, ignored -> new ArrayList<>()).add(instruction);
    }
    for (var instruction : instructions) {
      if (!(instruction instanceof Instruction.AllocateReference allocation)
          || allocation.queue() == null
          || allocation.value() instanceof Operand.Null) continue;
      boolean protectedLifetime = false;
      if (topLevel.contains(allocation.id())) {
        for (var publication : program.body()) {
          if (!(publication instanceof Instruction.WriteRoot write)
              || !(write.value() instanceof Operand.Name name)
              || !name.name().equals(allocation.name())
              || write.id() <= allocation.id()
              || !initialNull.contains(write.root())
              || writes.get(write.root()).size() != 1) continue;
          protectedLifetime =
              instructions.stream()
                  .noneMatch(
                      candidate ->
                          candidate instanceof Instruction.Release release
                              && release.name().equals(allocation.name())
                              && allocation.id() < release.id()
                              && release.id() < write.id());
          if (protectedLifetime) break;
        }
      }
      if (!protectedLifetime)
        gaps.add(
            "Registered nonliteral-null wrappers require top-level publication into a"
                + " dedicated initially-null root before release");
    }
    return List.copyOf(gaps);
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
    if (usesQueues(program) && hasParallel(program))
      gaps.add("Named queue histories require main-only execution");
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
      case Instruction.EnqueueReference ignored -> true;
      case Instruction.WriteReference op -> op.kind() != Value.ReferenceKind.STRONG;
      default -> false;
    };
  }
}
