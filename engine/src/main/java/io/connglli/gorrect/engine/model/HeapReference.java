package io.connglli.gorrect.engine.model;

import java.util.Objects;

/**
 * Canonical wrapper vertex. History state determines clearing; resource state owns registration.
 */
public record HeapReference(Shape shape, HeapId referent) {
  public HeapReference {
    Objects.requireNonNull(shape);
    if (!shape.wrapper())
      throw new IllegalArgumentException("Reference vertex requires wrapper type");
  }
}
