package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
