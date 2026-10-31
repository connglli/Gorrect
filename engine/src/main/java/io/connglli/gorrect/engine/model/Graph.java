package io.connglli.gorrect.engine.model;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable heap and reference roots. Named root slots remain present when null; live handles are
 * indexed by their declaring instruction IDs. Ordinary allocations and canonical first-class
 * wrapper vertices use separate identity maps; either may include unreachable garbage.
 */
public record Graph(
    Map<ObjectId, HeapObject> objects,
    Map<String, Value.Reference> roots,
    Map<Integer, Value.Reference> handles,
    Map<WrapperId, HeapReference> wrappers) {
  public Graph {
    objects = Map.copyOf(objects);
    roots = Map.copyOf(roots);
    handles = Map.copyOf(handles);
    wrappers = Map.copyOf(wrappers);
  }

  public Graph(
      Map<ObjectId, HeapObject> objects,
      Map<String, Value.Reference> roots,
      Map<Integer, Value.Reference> handles) {
    this(objects, roots, handles, Map.of());
  }

  /**
   * Projects an expected model to its strong closure. Preserve observed records for comparison.
   *
   * @throws IllegalArgumentException if the required model has missing targets, wrong slot counts,
   *     or non-strong references
   */
  public Graph required() {
    return reachable(false);
  }

  /** Obligations after a history selects which soft/weak referents remain accessible. */
  public Graph accessible() {
    return reachable(true);
  }

  private Graph reachable(boolean includeReferences) {
    var queue = new ArrayDeque<HeapId>();
    roots.values().forEach(value -> enqueue(value, queue));
    handles.values().forEach(value -> enqueue(value, queue));
    var required = new HashMap<ObjectId, HeapObject>();
    var referenceObjects = new HashMap<WrapperId, HeapReference>();
    while (!queue.isEmpty()) {
      var id = queue.removeFirst();
      if (id.wrapper() != null) {
        if (referenceObjects.containsKey(id.wrapper())) continue;
        var wrapper = wrappers.get(id.wrapper());
        if (wrapper == null) throw new IllegalArgumentException("Missing model wrapper " + id);
        if (!includeReferences)
          throw new IllegalArgumentException("Expected model requires strong references");
        referenceObjects.put(id.wrapper(), wrapper);
        if (wrapper.referent() != null) queue.add(wrapper.referent());
        continue;
      }
      if (required.containsKey(id.object())) continue;
      var object = objects.get(id.object());
      if (object == null) throw new IllegalArgumentException("Missing model object " + id);
      if (object.references().size() != object.shape().references()
          || object.data().size() != object.shape().data())
        throw new IllegalArgumentException("Invalid model slots for " + id);
      required.put(id.object(), object);
      for (var value : object.references()) {
        if (includeReferences && value.kind() != Value.ReferenceKind.STRONG) {
          if (value.heapTarget() != null) queue.add(value.heapTarget());
        } else enqueue(value, queue);
      }
    }
    return new Graph(required, roots, handles, referenceObjects);
  }

  private static void enqueue(Value.Reference value, ArrayDeque<HeapId> queue) {
    if (value.kind() != Value.ReferenceKind.STRONG)
      throw new IllegalArgumentException("Expected model requires strong references");
    if (value.heapTarget() != null) queue.addLast(value.heapTarget());
  }
}
