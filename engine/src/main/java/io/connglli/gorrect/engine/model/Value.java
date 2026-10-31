package io.connglli.gorrect.engine.model;

import java.util.Objects;

/** Oracle values, with references expressed as logical object identities. */
public sealed interface Value {
  enum ReferenceKind {
    STRONG,
    SOFT,
    WEAK
  }

  /**
   * Ordinary and wrapper target components are mutually exclusive; use heapTarget for generic
   * nullness/identity. The wrapper component names a legacy soft/weak edge incarnation, not a
   * strong pointer target. Both target components absent mean null, preserving the edge kind.
   */
  record Reference(ReferenceKind kind, ObjectId target, WrapperId wrapper, WrapperId wrapperTarget)
      implements Value {
    public static final Reference NULL = new Reference(ReferenceKind.STRONG, null);

    public Reference {
      Objects.requireNonNull(kind);
      if (target != null && wrapperTarget != null)
        throw new IllegalArgumentException("Reference has two target namespaces");
    }

    public Reference(ReferenceKind kind, ObjectId target) {
      this(kind, target, null, null);
    }

    public Reference(ReferenceKind kind, ObjectId target, WrapperId wrapper) {
      this(kind, target, wrapper, null);
    }

    /** Logical target without conflating an absent object target with a wrapper pointer. */
    public HeapId heapTarget() {
      return wrapperTarget == null ? HeapId.object(target) : HeapId.wrapper(wrapperTarget);
    }

    public static Reference toWrapper(WrapperId id) {
      return id == null ? NULL : new Reference(ReferenceKind.STRONG, null, null, id);
    }

    public static Reference strongHeap(HeapId id) {
      return id == null
          ? NULL
          : id.object() != null ? strong(id.object()) : toWrapper(id.wrapper());
    }

    public static Reference strong(ObjectId target) {
      return target == null ? NULL : new Reference(ReferenceKind.STRONG, target);
    }
  }

  /** A signed 64-bit data value or local scalar. */
  record Scalar(long value) implements Value {}
}
