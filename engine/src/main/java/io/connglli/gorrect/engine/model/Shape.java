package io.connglli.gorrect.engine.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Logical layout in separate reference and data slots. Object payload kinds follow data-slot order;
 * arrays retain reference or long elements. A null kind list denotes the legacy all-long layout.
 * Explicit kind lists are immutable, must match the data count, and permit int only for objects.
 * Counts describe slots, not physical offsets, object sizes, or padding.
 */
public record Shape(String name, Kind kind, int references, int data, List<DataKind> dataKinds) {
  /** Object payload widths; scalar bindings remain signed 64-bit values. */
  public enum DataKind {
    INT,
    LONG;

    /** Applies Java narrowing and sign extension for an int field, preserving long fields. */
    public long normalize(long value) {
      return switch (this) {
        case INT -> (long) (int) value;
        case LONG -> value;
      };
    }
  }

  /** Legacy declarations and layouts contain only long payloads. */
  public Shape(String name, Kind kind, int references, int data) {
    this(name, kind, references, data, null);
  }

  /** Declares an explicit, non-null object payload sequence with its count inferred. */
  public Shape(String name, Kind kind, int references, List<DataKind> dataKinds) {
    this(name, kind, references, dataKinds.size(), dataKinds);
  }

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

    var longs = Collections.nCopies(data, DataKind.LONG);
    if (dataKinds == null) {
      dataKinds = longs;
    } else {
      if (dataKinds.size() != data) throw new IllegalArgumentException("Invalid payload count");

      // Reusing a default shape's kind list must not materialize every array element.
      if (longs.equals(dataKinds)) dataKinds = longs;
      else {
        if (kind != Kind.OBJECT) throw new IllegalArgumentException("Invalid payload kinds");
        dataKinds = List.copyOf(dataKinds);
      }
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
