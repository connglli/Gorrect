package io.connglli.gorrect.engine.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable shape and contents. Slot lengths remain unchecked so malformed observations can be
 * compared and diagnosed.
 */
public record HeapObject(Shape shape, List<Value.Reference> references, List<Long> data) {
  public HeapObject {
    Objects.requireNonNull(shape);
    references = List.copyOf(references);
    data = List.copyOf(data);
  }
}
