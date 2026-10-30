package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Immutable declarations and instruction tree. The label map associates internal instruction IDs
 * with optional source names; {@link GirValidator} checks their validity.
 */
public record GirProgram(
    int version,
    List<Shape> shapes,
    List<InitialObject> objects,
    List<Root> roots,
    List<Instruction> body,
    Map<Integer, String> labels,
    Set<String> requirements) {
  public GirProgram {
    shapes = List.copyOf(shapes);
    objects = List.copyOf(objects);
    roots = List.copyOf(roots);
    body = List.copyOf(body);
    labels = Map.copyOf(labels);
    requirements = Set.copyOf(requirements);
  }

  public GirProgram(
      int version,
      List<Shape> shapes,
      List<InitialObject> objects,
      List<Root> roots,
      List<Instruction> body,
      Map<Integer, String> labels) {
    this(version, shapes, objects, roots, body, labels, Set.of());
  }

  public GirProgram(
      int version,
      List<Shape> shapes,
      List<InitialObject> objects,
      List<Root> roots,
      List<Instruction> body) {
    this(version, shapes, objects, roots, body, Map.of());
  }

  /**
   * Returns a fresh sequential preorder traversal of the instruction tree, not an execution trace.
   * Conditional and loop bodies occur once; parallel bodies follow worker declaration order.
   * Workers themselves are not instructions. Lexical scopes and runtime choices are not modeled.
   */
  public Stream<Instruction> instructions() {
    var pending = new ArrayDeque<Iterator<Instruction>>();
    pending.push(body.iterator());
    var traversal =
        new Spliterators.AbstractSpliterator<Instruction>(
            Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
          @Override
          public boolean tryAdvance(Consumer<? super Instruction> action) {
            Objects.requireNonNull(action);
            while (!pending.isEmpty()) {
              if (!pending.peek().hasNext()) {
                pending.pop();
                continue;
              }
              var instruction = pending.peek().next();
              switch (instruction) {
                case Instruction.Scope scope -> pending.push(scope.body().iterator());
                case Instruction.Repeat repeat -> pending.push(repeat.body().iterator());
                case Instruction.Acquire acquire -> pending.push(acquire.body().iterator());
                case Instruction.IfPresent present -> pending.push(present.body().iterator());
                case Instruction.Parallel parallel -> {
                  for (int index = parallel.workers().size() - 1; index >= 0; index--)
                    pending.push(parallel.workers().get(index).body().iterator());
                }
                case Instruction.Operation ignored -> {}
              }
              action.accept(instruction);
              return true;
            }
            return false;
          }
        };
    return StreamSupport.stream(traversal, false);
  }

  /** Declarative contents whose reference targets may be declared later in the file. */
  public record InitialObject(
      long id, String shape, List<Value.Reference> references, List<Long> data) {
    public InitialObject {
      Objects.requireNonNull(shape);
      references = List.copyOf(references);
      data = List.copyOf(data);
    }
  }

  /** The initial value of a named shared root slot. */
  public record Root(String name, Value.Reference value) {
    public Root {
      Objects.requireNonNull(name);
      Objects.requireNonNull(value);
    }
  }
}
