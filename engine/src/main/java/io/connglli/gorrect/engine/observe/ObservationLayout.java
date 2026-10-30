package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.List;
import java.util.Map;

/** Emitted field descriptions only; this contains no predicted targets or payload values. */
public record ObservationLayout(
    List<ObjectType> objects,
    List<Shape> arrays,
    Roots roots,
    List<ReferenceType> references,
    List<LocalBinding> bindings) {
  public ObservationLayout {
    objects = List.copyOf(objects);
    arrays = List.copyOf(arrays);
    references = references == null ? List.of() : List.copyOf(references);
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
  }

  public ObservationLayout(
      List<ObjectType> objects, List<Shape> arrays, Roots roots, List<ReferenceType> references) {
    this(objects, arrays, roots, references, List.of());
  }

  public ObservationLayout(List<ObjectType> objects, List<Shape> arrays, Roots roots) {
    this(objects, arrays, roots, List.of());
  }

  /**
   * Structural mapping for a GIR declaration and worker (zero for main). Method descriptor and
   * local type use JVM descriptors; variable is the compiler local's name. Runtime values and stack
   * slots are never predicted here.
   */
  public record LocalBinding(
      int declaration,
      int worker,
      String method,
      String descriptor,
      String variable,
      String type) {}

  /** Actual wrapper metadata roles; inherited fields use captured JVMTI indices. */
  public record ReferenceType(
      String signature,
      Value.ReferenceKind kind,
      String worker,
      String site,
      String occurrence,
      String slot,
      String referent) {}

  /**
   * Maps a JVM class signature to logical slots. Reference and data field names are in logical slot
   * order; worker, site, and occurrence name the final {@code int}, {@code int}, and {@code long}
   * identity fields. Field indices come from captured VM metadata.
   */
  public record ObjectType(
      String signature,
      Shape shape,
      List<String> references,
      List<String> data,
      String worker,
      String site,
      String occurrence) {
    public ObjectType {
      references = List.copyOf(references);
      data = List.copyOf(data);
    }
  }

  /** Maps actual static field names to GIR root names. */
  public record Roots(String signature, Map<String, String> fields) {
    public Roots {
      fields = Map.copyOf(fields);
    }
  }
}
