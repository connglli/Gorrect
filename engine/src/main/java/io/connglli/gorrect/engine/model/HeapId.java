package io.connglli.gorrect.engine.model;

/**
 * Tagged heap identity; ordinary allocations and reference incarnations never share a namespace.
 */
public record HeapId(ObjectId object, WrapperId wrapper) implements Comparable<HeapId> {
  public HeapId {
    if ((object == null) == (wrapper == null))
      throw new IllegalArgumentException("Heap identity requires exactly one namespace");
  }

  public static HeapId object(ObjectId id) {
    return id == null ? null : new HeapId(id, null);
  }

  public static HeapId wrapper(WrapperId id) {
    return id == null ? null : new HeapId(null, id);
  }

  @Override
  public int compareTo(HeapId other) {
    if (object != null) return other.object == null ? -1 : object.compareTo(other.object);
    return other.object != null ? 1 : wrapper.compareTo(other.wrapper);
  }
}
