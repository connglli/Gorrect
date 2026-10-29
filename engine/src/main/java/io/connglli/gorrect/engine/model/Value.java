package io.connglli.gorrect.engine.model;

import java.util.Objects;

/** Oracle values, with references expressed as logical object identities. */
public sealed interface Value {
  enum ReferenceKind {
    STRONG,
    SOFT,
    WEAK
  }

  /** A null target preserves the slot's kind, allowing verification to detect strength changes. */
  record Reference(ReferenceKind kind, ObjectId target) implements Value {
    public static final Reference NULL = new Reference(ReferenceKind.STRONG, null);

    public Reference {
      Objects.requireNonNull(kind);
    }

    public static Reference strong(ObjectId target) {
      return target == null ? NULL : new Reference(ReferenceKind.STRONG, target);
    }
  }

  /** A signed 64-bit data value or local scalar. */
  record Scalar(long value) implements Value {}
}
