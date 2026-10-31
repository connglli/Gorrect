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
    DATA_ARRAY,
    SOFT_REFERENCE,
    WEAK_REFERENCE
  }

  public Shape {
    Objects.requireNonNull(name);
    Objects.requireNonNull(kind);
    if (references < 0
        || data < 0
        || (kind == Kind.REFERENCE_ARRAY && data != 0)
        || (kind == Kind.DATA_ARRAY && references != 0)
        || ((kind == Kind.SOFT_REFERENCE || kind == Kind.WEAK_REFERENCE)
            && (references != 0 || data != 0))) {
      throw new IllegalArgumentException("Invalid shape dimensions");
    }
  }

  public boolean wrapper() {
    return kind == Kind.SOFT_REFERENCE || kind == Kind.WEAK_REFERENCE;
  }

  public boolean array() {
    return kind == Kind.REFERENCE_ARRAY || kind == Kind.DATA_ARRAY;
  }

  public Value.ReferenceKind strength() {
    if (!wrapper()) throw new IllegalArgumentException("Not a wrapper shape");
    return kind == Kind.SOFT_REFERENCE ? Value.ReferenceKind.SOFT : Value.ReferenceKind.WEAK;
  }

  public long slots() {
    return wrapper() ? 1 : (long) references + data;
  }
}
