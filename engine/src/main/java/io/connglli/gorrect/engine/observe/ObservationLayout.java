package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.Shape;
import java.util.List;
import java.util.Map;

/** Emitted field descriptions only; this contains no predicted targets or payload values. */
public record ObservationLayout(List<ObjectType> objects, List<Shape> arrays, Roots roots) {
  public ObservationLayout {
    objects = List.copyOf(objects);
    arrays = List.copyOf(arrays);
  }

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
