package io.connglli.gorrect.engine.model;

import java.util.Objects;

/**
 * Logical layout in slots. The counts describe fields for objects; an array uses its matching count
 * as its length.
 */
public record Shape(String name, Kind kind, int references, int data) {
  public enum Kind {
    OBJECT,
    REFERENCE_ARRAY,
    DATA_ARRAY
  }

  public Shape {
    Objects.requireNonNull(name);
    Objects.requireNonNull(kind);
    if (references < 0
        || data < 0
        || (kind == Kind.REFERENCE_ARRAY && data != 0)
        || (kind == Kind.DATA_ARRAY && references != 0)) {
      throw new IllegalArgumentException("Invalid shape dimensions");
    }
  }

  public long slots() {
    return (long) references + data;
  }
}
