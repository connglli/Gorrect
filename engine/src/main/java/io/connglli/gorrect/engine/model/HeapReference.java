package io.connglli.gorrect.engine.model;

import java.util.Objects;

/**
 * Canonical no-queue wrapper vertex. History state determines whether its construction edge clears.
 */
public record HeapReference(Shape shape, HeapId referent) {
  public HeapReference {
    Objects.requireNonNull(shape);
    if (!shape.wrapper())
      throw new IllegalArgumentException("Reference vertex requires wrapper type");
  }
}
