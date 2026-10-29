package io.connglli.gorrect.engine.model;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable heap and reference roots. Named root slots remain present when null; live handles are
 * indexed by their declaring instruction IDs. Objects may include unreachable garbage.
 */
public record Graph(
    Map<ObjectId, HeapObject> objects,
    Map<String, Value.Reference> roots,
    Map<Integer, Value.Reference> handles) {
  public Graph {
    objects = Map.copyOf(objects);
    roots = Map.copyOf(roots);
    handles = Map.copyOf(handles);
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
    var queue = new ArrayDeque<ObjectId>();
    roots.values().forEach(value -> enqueue(value, queue));
    handles.values().forEach(value -> enqueue(value, queue));
    var required = new HashMap<ObjectId, HeapObject>();
    while (!queue.isEmpty()) {
      var id = queue.removeFirst();
      if (required.containsKey(id)) continue;
      var object = objects.get(id);
      if (object == null) throw new IllegalArgumentException("Missing model object " + id);
      if (object.references().size() != object.shape().references()
          || object.data().size() != object.shape().data()) {
        throw new IllegalArgumentException("Invalid model slots for " + id);
      }
      required.put(id, object);
      object
          .references()
          .forEach(
              value -> {
                if (includeReferences && value.kind() != Value.ReferenceKind.STRONG) {
                  if (value.target() != null) queue.addLast(value.target());
                } else enqueue(value, queue);
              });
    }
    return new Graph(required, roots, handles);
  }

  private static void enqueue(Value.Reference value, ArrayDeque<ObjectId> queue) {
    if (value.kind() != Value.ReferenceKind.STRONG) {
      throw new IllegalArgumentException("Expected model requires strong references");
    }
    if (value.target() != null) queue.addLast(value.target());
  }
}
