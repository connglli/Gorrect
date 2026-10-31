package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.QueuePlan;
import java.util.List;
import java.util.Map;

/** Structural graph/local roles and metadata selection, with no predicted targets or payloads. */
public record ObservationLayout(
    List<ObjectType> objects,
    List<Shape> arrays,
    Roots roots,
    List<ReferenceType> references,
    List<LocalBinding> bindings,
    String bindingSelection,
    String bindingMetadata,
    QueuePlan queuePlan) {
  public static final String REQUIRED = "required";
  public static final String LEXICAL_CANDIDATES = "lexical-candidates";

  public ObservationLayout {
    objects = List.copyOf(objects);
    arrays = List.copyOf(arrays);
    references = references == null ? List.of() : List.copyOf(references);
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    bindingMetadata = bindingMetadata == null ? "jvmti" : bindingMetadata;
    if (!bindingMetadata.equals("jvmti") && !bindingMetadata.equals("retained-class"))
      throw new IllegalArgumentException("Unknown binding metadata provider");
    if (bindingSelection != null
        && !bindingSelection.equals(REQUIRED)
        && !bindingSelection.equals(LEXICAL_CANDIDATES))
      throw new IllegalArgumentException("Unknown binding-selection rule");
  }

  /** Legacy layouts carry no queue selection. */
  public ObservationLayout(
      List<ObjectType> objects,
      List<Shape> arrays,
      Roots roots,
      List<ReferenceType> references,
      List<LocalBinding> bindings,
      String bindingSelection,
      String bindingMetadata) {
    this(objects, arrays, roots, references, bindings, bindingSelection, bindingMetadata, null);
  }

  /** Legacy layouts select VM tables; provider selection is explicit in new retained layouts. */
  public ObservationLayout(
      List<ObjectType> objects,
      List<Shape> arrays,
      Roots roots,
      List<ReferenceType> references,
      List<LocalBinding> bindings,
      String bindingSelection) {
    this(objects, arrays, roots, references, bindings, bindingSelection, "jvmti");
  }

  /** Missing selection metadata denotes the original required-binding emission rule. */
  public ObservationLayout(
      List<ObjectType> objects,
      List<Shape> arrays,
      Roots roots,
      List<ReferenceType> references,
      List<LocalBinding> bindings) {
    this(objects, arrays, roots, references, bindings, null);
  }

  /** Lexical candidates require separate future-use selection before physical-root matching. */
  public boolean lexicalCandidates() {
    return LEXICAL_CANDIDATES.equals(bindingSelection);
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

  /**
   * Actual wrapper roles; inherited fields use captured JVMTI indices. A nominal shape selects a
   * first-class no-queue vertex; null preserves the legacy opaque slot-wrapper rule.
   */
  public record ReferenceType(
      String signature,
      Value.ReferenceKind kind,
      String worker,
      String site,
      String occurrence,
      String slot,
      String referent,
      Shape shape) {
    public ReferenceType(
        String signature,
        Value.ReferenceKind kind,
        String worker,
        String site,
        String occurrence,
        String slot,
        String referent) {
      this(signature, kind, worker, site, occurrence, slot, referent, null);
    }
  }

  /**
   * Maps a JVM class signature to logical slots. Reference and data field names are in logical slot
   * order; the shape's data kinds require matching JVM field descriptors and primitive records.
   * Worker, site, and occurrence name the final {@code int}, {@code int}, and {@code long} identity
   * fields. Field indices come from captured VM metadata.
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
