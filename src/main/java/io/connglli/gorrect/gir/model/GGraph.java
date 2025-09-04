/*
 * MIT License
 *
 * Copyright (c) 2025-2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect.gir.model;

import io.connglli.gorrect.gir.model.visitor.GObjectBreadthFirstVisitor;
import io.connglli.gorrect.gir.model.visitor.GObjectVisitor;
import io.connglli.gorrect.gir.model.visitor.GReferenceVisitor;
import io.connglli.gorrect.utility.Assert;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * GGraph represents a directed graph of objects. Where each node represents an object and an edge
 * between two nodes represent a reference. References are also labeled with different strengths to
 * model different types of references. It is important to note that, the object graph is designed
 * as simple as enough, so it is NOT a multi-graph. This means that there is only one reference
 * between any two objects u and v for each direction u->v and v->u, respectively. Currently, we
 * assume there's only one thread and all root objects live on that thread.
 */
public class GGraph {
  private final Map<GID, GObject> objects; // ID -> Object
  private final Map<GID, GReference> references; // ID -> Reference
  private final Map<GID, Set<GID>> refTo; // ID -> {ID}: Refer-to table
  private final Map<GID, Set<GID>> refBy; // ID -> {ID}: Referred-by table

  public GGraph() {
    this.objects = new HashMap<>();
    this.references = new HashMap<>();
    this.refTo = new HashMap<>();
    this.refBy = new HashMap<>();
  }

  public GGraph(@NonNull GGraph other) {
    this();
    reset(other);
  }

  public void reset(@NonNull GGraph other) {
    this.objects.clear();
    this.references.clear();
    this.refTo.clear();
    this.refBy.clear();

    for (GObject obj : other.objects.values()) {
      this.objects.put(obj.getId(), new GObject(obj));
    }

    for (GReference ref : other.references.values()) {
      this.references.put(ref.getId(), new GReference(ref));
    }

    for (GID refererId : other.refTo.keySet()) {
      this.refTo.put(
          refererId, other.refTo.get(refererId).stream().map(GID::new).collect(Collectors.toSet()));
    }

    for (GID referentId : other.refBy.keySet()) {
      this.refBy.put(
          referentId,
          other.refBy.get(referentId).stream().map(GID::new).collect(Collectors.toSet()));
    }
  }

  public GGraph deepCopy() {
    return new GGraph(this);
  }

  public List<GObject> getRoots() {
    return objects.values().stream().filter(GObject::isRoot).collect(Collectors.toList());
  }

  public List<GObject> getObjects() {
    return new ArrayList<>(objects.values());
  }

  public boolean hasObject(@NonNull GID objId) {
    return objects.containsKey(objId);
  }

  public @NonNull GObject getObject(@NonNull GID objId) {
    Assert.set(hasObject(objId), String.format("Object (with id %s) does not exist", objId));
    return objects.get(objId);
  }

  public boolean hasRoot(@NonNull GID objId) {
    return hasObject(objId) && getObject(objId).isRoot();
  }

  public @NonNull GObject getRoot(@NonNull GID rootId) {
    GObject obj = getObject(rootId);
    Assert.set(obj.isRoot(), String.format("Object (with id %s) is not a root object", rootId));
    return obj;
  }

  public boolean hasNonRoot(@NonNull GID objId) {
    return hasObject(objId) && getObject(objId).getType() != GObject.Type.GCROOT;
  }

  public @NonNull GObject getNonRoot(@NonNull GID objId) {
    GObject obj = getObject(objId);
    Assert.set(
        obj.getType() != GObject.Type.GCROOT,
        String.format("Object (with id %s) is a root object", objId));
    return obj;
  }

  public List<GReference> getReferences() {
    return new ArrayList<>(references.values());
  }

  public boolean hasReference(@NonNull GID refId) {
    return references.containsKey(refId);
  }

  public @NonNull GReference getReference(@NonNull GID refId) {
    Assert.set(hasReference(refId), String.format("Reference with id %s does not exist", refId));
    return references.get(refId);
  }

  public boolean hasReference(@NonNull GID refererId, @NonNull GID referentId) {
    return refTo.containsKey(refererId) && refTo.get(refererId).contains(referentId);
  }

  public @NonNull GReference getReference(@NonNull GID refererId, @NonNull GID referentId) {
    Assert.set(
        hasReference(refererId, referentId),
        String.format(
            "Reference from object %s to object %s does not exist", refererId, referentId));
    for (GReference ref : references.values()) {
      if (ref.getReferer().equals(refererId) && ref.getReferent().equals(referentId)) {
        return ref;
      }
    }
    Assert.panic("Cannot reach here");
    return references.get(new GID("null"));
  }

  public List<GReference> getOutgoingReferences(@NonNull GID refererId) {
    Assert.set(hasObject(refererId), String.format("Object with id %s does not exist", refererId));
    return refTo.get(refererId).stream()
        .map(referentId -> getReference(refererId, referentId))
        .collect(Collectors.toList());
  }

  public List<GReference> getIncomingReferences(@NonNull GID referentId) {
    Assert.set(
        hasObject(referentId), String.format("Object with id %s does not exist", referentId));
    return refBy.get(referentId).stream()
        .map(refererId -> getReference(refererId, referentId))
        .collect(Collectors.toList());
  }

  public List<GObject> getReferents(@NonNull GID refererId) {
    Assert.set(hasObject(refererId), String.format("Object with id %s does not exist", refererId));
    return refTo.get(refererId).stream().map(this::getObject).collect(Collectors.toList());
  }

  public List<GObject> getReferers(@NonNull GID referentId) {
    Assert.set(
        hasObject(referentId), String.format("Object with id %s does not exist", referentId));
    return refBy.get(referentId).stream().map(this::getObject).collect(Collectors.toList());
  }

  /// Add an object into the graph. Note that it is the user's responsibility to ensure that the
  /// added object is reachable from our root objects.
  public GID addObject(@NonNull GID objId, GObject.Type type, long size) {
    Assert.set(size >= 0, "Object size must be non-negative, but got %d", size);
    Assert.set(!objects.containsKey(objId), "The id %s is already used by another object", objId);
    Assert.set(
        !references.containsKey(objId), "The id %s is already used by another reference", objId);
    objects.put(objId, new GObject(objId, type, size));
    refTo.put(objId, new HashSet<>());
    refBy.put(objId, new HashSet<>());
    return objId;
  }

  /// Remove an object from the graph. This operation may cause certain objects unreachable. Call
  /// removeUnreachableObjects() to compact the graph.
  public void removeObject(@NonNull GID objId) {
    Assert.set(hasObject(objId), String.format("Object with id %s does not exist", objId));

    // Remove all references connected to this object
    List<GID> refsToRemove = new ArrayList<>();
    for (GReference ref : references.values()) {
      if (ref.getReferer().equals(objId) || ref.getReferent().equals(objId)) {
        refsToRemove.add(ref.getId());
      }
    }
    for (GID refId : refsToRemove) {
      removeReference(refId);
    }

    // Update adjacent tables
    Assert.set(refTo.get(objId).isEmpty(), "Object still %s has outgoing references", objId);
    refTo.remove(objId);
    Assert.set(refBy.get(objId).isEmpty(), "Object still %s has incoming references", objId);
    refBy.remove(objId);

    objects.remove(objId);
  }

  /// Add a reference into the graph. Note that it is the user's responsibility to ensure that the
  /// referrer and the referent all exist in the graph.
  @SuppressWarnings("UnusedReturnValue")
  public GID addReference(
      @NonNull GID refId, @NonNull GID refererId, @NonNull GID referentId, GReference.Type type) {
    Assert.set(
        objects.containsKey(refererId),
        String.format(
            "The object with id %s for adding as the referer of a new reference does not exist",
            refererId));
    Assert.set(
        objects.containsKey(referentId),
        String.format(
            "The object with id %s for adding as the referent of a new reference does not exist",
            referentId));
    Assert.set(
        !hasReference(refererId, referentId),
        String.format(
            "A reference already exists between object %s and object %s", refererId, referentId));

    references.put(refId, new GReference(refId, refererId, referentId, type));
    refTo.get(refererId).add(referentId);
    refBy.get(referentId).add(refererId);

    return refId;
  }

  /// Remove a reference from the graph. This operation may cause certain objects unreachable.
  /// Call removeUnreachableObjects() to compact the graph.
  public void removeReference(@NonNull GID refId) {
    Assert.set(hasReference(refId), String.format("Reference with id %s does not exist", refId));
    GReference ref = references.remove(refId);
    refTo.get(ref.getReferer()).remove(ref.getReferent());
    refBy.get(ref.getReferent()).remove(ref.getReferer());
  }

  /// Remove a reference from the graph. This operation may cause certain objects unreachable.
  /// Call removeUnreachableObjects() to compact the graph.
  public void removeReference(@NonNull GID refererId, @NonNull GID referentId) {
    removeReference(getReference(refererId, referentId).getId());
  }

  /// Visit all objects in the object graph in a specific order
  public void accept(GObjectVisitor visitor) {
    visitor.visit(this);
  }

  /// Visit all references in the object graph in a specific order
  public void accept(GReferenceVisitor visitor) {
    visitor.visit(this);
  }

  /*
   * Path finding algorithms. Path finding in GGraph involves finding a path from a root object to
   * another object in the graph. The path is represented as WeightedPath, starting from
   * the root object and ending with the target object. If no such path exists, the WeightedPath is null.
   */

  public static final Long INFINITY = Long.MAX_VALUE;

  @RequiredArgsConstructor
  public class WeightedPath {
    @NonNull private final List<GID> objIdPaths;
    @Getter @NonNull private final Long weight;

    /// Get the length of the path, that said, the number of objects in the path including the root
    /// object and the target object.
    public int length() {
      return objIdPaths.size();
    }

    /// Get the root object of the path.
    public GObject getRoot() {
      return getObject(objIdPaths.getFirst());
    }

    /// Get the target object of the path.
    public GObject getTarget() {
      return getObject(objIdPaths.getLast());
    }

    /// Get the i-th object in the path.
    public GObject getObjectAt(int i) {
      Assert.set(i >= 0 && i < objIdPaths.size(), "Index %d is out of bounds", i);
      return getObject(objIdPaths.get(i));
    }

    /// Represent the path as a list of objects.
    public List<GObject> getObjectPaths() {
      return objIdPaths.stream().map(GGraph.this::getObject).collect(Collectors.toList());
    }

    /// Represent the path as a list of references.
    public List<GReference> getReferencePaths() {
      List<GReference> refPaths = new ArrayList<>();
      for (int i = 0; i < objIdPaths.size() - 1; i++) {
        GID refererId = objIdPaths.get(i);
        GID referentId = objIdPaths.get(i + 1);
        refPaths.add(getReference(refererId, referentId));
      }
      return refPaths;
    }
  }

  /// Find the shortest path from a root object root to all other objects using Dijkstra's
  /// algorithm. The weights of each reference is given by weights. Return a map from object ID to
  /// the list of object IDs (i.e., WeightedPath) representing the shortest path from the root to
  /// that object. For reachable objects, the list starts with root and ends with that object. If an
  /// object is unreachable, the corresponding list is null.
  public Map<GID, WeightedPath> markShortestPaths(@NonNull GID root, Map<GID, Long> weights) {
    Assert.set(hasObject(root), "Object with id %s does not exist", root);
    Assert.set(getObject(root).isRoot(), "Object with id %s is not a root", root);

    // Mark shortest paths using Dijkstra's algorithm

    Map<GID, Long> minDist = new HashMap<>();
    Map<GID, GID> prevObj = new HashMap<>();
    PriorityQueue<GID> queue =
        new PriorityQueue<>(
            (objX, objY) -> {
              Long distX = minDist.get(objX);
              Long distY = minDist.get(objY);
              if (distX.equals(INFINITY) && distY.equals(INFINITY)) return 0;
              if (distX.equals(INFINITY)) return 1;
              if (distY.equals(INFINITY)) return -1;
              return Long.compare(distX, distY);
            });

    for (GID id : objects.keySet()) {
      minDist.put(id, INFINITY);
      prevObj.put(id, null);
      queue.offer(id);
    }
    minDist.put(root, 0L);
    prevObj.put(root, null);

    while (!queue.isEmpty()) {
      GID referer = queue.poll();
      Long refererDist = minDist.get(referer);
      if (refererDist.equals(INFINITY)) {
        continue; // All remaining objects are unreachable
      }
      for (GID referent : refTo.get(referer)) {
        GID reference = getReference(referer, referent).getId();
        Long referenceWeight = weights.getOrDefault(reference, null);
        Assert.set(
            referenceWeight != null,
            "Weight for reference %s is not provided or is null",
            reference);
        Assert.set(referenceWeight >= 0, "Weight for reference %s must be non-negative", reference);
        Assert.set(
            referenceWeight < INFINITY,
            "Weight for reference %s cannot be INFINITY (i.e., %d)",
            reference,
            INFINITY);
        long referentDist = refererDist + referenceWeight;
        if (referentDist < minDist.get(referent)) {
          minDist.put(referent, referentDist);
          prevObj.put(referent, referer);
          if (!queue.contains(referent)) {
            queue.offer(referent);
          }
        }
      }
    }

    // Compute the shortest paths using minDist and prevObj

    Map<GID, WeightedPath> shortestPaths = new HashMap<>();
    for (GID object : objects.keySet()) {
      List<GID> path = new ArrayList<>();
      for (GID referer = object; referer != null; referer = prevObj.get(referer)) {
        path.add(referer);
      }
      Collections.reverse(path);
      Assert.set(
          path.getLast().equals(object),
          "Path reconstruction failure; the last object is not %s, but %s",
          object,
          path.getLast());
      if (path.size() == 1) {
        path.clear(); // No path found
        shortestPaths.put(object, null);
      } else {
        Assert.set(
            path.getFirst().equals(root),
            "Path reconstruction failure; the first object is not %s, but %s",
            root,
            path.getFirst());
        shortestPaths.put(object, new WeightedPath(path, minDist.get(object)));
      }
    }

    return shortestPaths;
  }

  /// Find the shortest path from any root object root to all other objects using Dijkstra's
  /// algorithm. The weights of each reference is given by weights. Return a map from object ID to
  /// the list of object IDs representing the shortest path (i.e., WeightedPath) from any root to
  ///  that object. For reachable objects, the list starts with root and ends with that object. If
  /// an object is unreachable, the corresponding list is non-null but empty.
  public Map<GID, WeightedPath> markShortestPaths(Map<GID, Long> weights) {
    Map<GID, WeightedPath> shortestPaths = null;
    for (GObject root : getRoots()) {
      Map<GID, WeightedPath> rootShortestPaths = markShortestPaths(root.getId(), weights);
      if (shortestPaths == null) {
        shortestPaths = rootShortestPaths;
        continue;
      }
      for (GID objId : rootShortestPaths.keySet()) {
        WeightedPath rootPath = rootShortestPaths.get(objId);
        if (rootPath == null) {
          continue; // The object is unreachable from this root
        } else if (shortestPaths.get(objId) == null) {
          shortestPaths.put(
              objId,
              rootPath); // The object is reachable from this root but not from previous roots
        } else {
          // The object is reachable from both this root and previous roots, take the shorter one
          WeightedPath shortest = shortestPaths.get(objId);
          if (shortest.getWeight() > rootPath.getWeight()) {
            shortestPaths.put(objId, rootPath);
          } else if (shortest.getWeight().equals(rootPath.getWeight())
              && shortest.length() > rootPath.length()) {
            shortestPaths.put(objId, rootPath);
          }
        }
      }
    }
    return shortestPaths;
  }

  /*
   * Reachability going from strongest to weakest, the different levels of reachability reflect
   * the life cycle of an object. They are operationally defined as follows:
   *
   * - Strongly reachable: An object is strongly reachable if it can be reached by some thread
   *   without traversing any reference objects. A newly-created object is strongly reachable
   *   by the thread that created it.
   *
   * - Softly reachable: An object is softly reachable if it is not strongly reachable but can
   *   be reached by traversing a soft reference.
   *
   * - Weakly reachable: An object is weakly reachable if it is neither strongly nor softly
   *   reachable but can be reached by traversing a weak reference. When the weak references to
   *   a weakly-reachable object are cleared, the object becomes eligible for finalization.
   *
   * - Phantom reachable:An object is phantom reachable if it is neither strongly, softly, nor
   *   weakly reachable, it has been finalized, and some phantom reference refers to it.
   *
   * - Unreachable: Finally, an object is unreachable, and therefore eligible for reclamation,
   *   when it is not reachable in any of the above ways.
   *
   * More: https://docs.oracle.com/javase/10/docs/api/java/lang/ref/package-summary.html
   *
   */

  /// Remove unreachable objects from the graph. An unreachable object is an object that is not
  /// reachable from any root object.
  public Set<GID> removeUnreachableObjects() {
    Set<GID> reachableObjs = getReachableObjects();
    Set<GID> unreachableObjs =
        objects.keySet().stream()
            .filter(id -> !reachableObjs.contains(id))
            .collect(Collectors.toSet());
    unreachableObjs.forEach(
        id -> {
          if (hasObject(id)) {
            this.removeObject(id);
          }
        });
    return unreachableObjs;
  }

  /// Remove weakly reachable objects from the graph. A weakly reachable object is an object that
  /// is reachable from a root object through weak references, but not strongly or softly
  /// reachable.
  public Set<GID> removeWeaklyReachableObjects() {
    Set<GID> unreachableObjs = getWeaklyReachableObjects();
    unreachableObjs.forEach(
        id -> {
          if (hasObject(id)) {
            this.removeObject(id);
          }
        });
    unreachableObjs.addAll(removeUnreachableObjects());
    return unreachableObjs;
  }

  /// Remove softly reachable objects from the graph. A softly reachable object is an object that
  /// is reachable from a root object through soft references, but not strongly reachable.
  public Set<GID> removeSoftlyReachableObjects() {
    Set<GID> stronglyRechableObjects = getStronglyReachableObjects();
    Set<GID> unreachableObjs =
        objects.keySet().stream()
            .filter(id -> !stronglyRechableObjects.contains(id))
            .collect(Collectors.toSet());
    unreachableObjs.forEach(
        id -> {
          if (hasObject(id)) {
            this.removeObject(id);
          }
        });
    unreachableObjs.addAll(removeUnreachableObjects());
    return unreachableObjs;
  }

  /// Get all strongly reachable objects in the graph. A strongly reachable object is an object
  /// that is reachable from a root object without traversing any reference objects.
  public Set<GID> getStronglyReachableObjects() {
    return markReachability().entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.STRONG)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  /// Get all softly reachable objects in the graph. A softly reachable object is an object
  /// that is reachable from a root object through soft references, but not strongly reachable.
  public Set<GID> getSoftlyReachableObjects() {
    return markReachability().entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.SOFT)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  /// Get all weakly reachable objects in the graph. A weakly reachable object is an object that
  /// is reachable from a root object through weak references, but not strongly or softly
  /// reachable.
  public Set<GID> getWeaklyReachableObjects() {
    return markReachability().entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.WEAK)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  /// Get all reachable objects in the graph. A reachable object is an object that is reachable
  /// from a root object through any type of reference (strong, soft, or weak).
  public Set<GID> getReachableObjects() {
    return markReachability().keySet();
  }

  /// Mark the reachability of each object in the graph. The reachability is determined by the
  /// references between objects. The result is a map from object ID to its reachability type.
  public Map<GID, GReference.Type> markReachability() {
    // Mark reachability involves three passes over the object graph:
    // 1. Visit all objects via strong references and mark them as strongly reachable.
    // 2. Visit all non-visited objects via strong and soft references and mark them as
    //    softly reachable.
    // 3. Visit all non-visited objects via strong, soft, and weak references and mark them as
    // weakly reachable.
    // All objects that are not visited in the above three steps are unreachable.
    Map<GID, GReference.Type> result = new HashMap<>();
    accept(
        new GObjectBreadthFirstVisitor() {
          @Override
          public GReference.Type[] allowReferenceTypes() {
            return new GReference.Type[] {GReference.Type.STRONG};
          }

          @Override
          public void visit(@NonNull GGraph graph, @NonNull GObject object) {
            result.putIfAbsent(object.getId(), GReference.Type.STRONG);
          }
        });
    accept(
        new GObjectBreadthFirstVisitor() {
          @Override
          public GReference.Type[] allowReferenceTypes() {
            return new GReference.Type[] {GReference.Type.STRONG, GReference.Type.SOFT};
          }

          @Override
          public void visit(@NonNull GGraph graph, @NonNull GObject obj) {
            result.putIfAbsent(obj.getId(), GReference.Type.SOFT);
          }
        });
    accept(
        new GObjectBreadthFirstVisitor() {
          @Override
          public GReference.Type[] allowReferenceTypes() {
            return new GReference.Type[] {
              GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK
            };
          }

          @Override
          public void visit(@NonNull GGraph graph, @NonNull GObject obj) {
            result.putIfAbsent(obj.getId(), GReference.Type.WEAK);
          }
        });
    return result;
  }

  /// Helper method to convert the object graph in a human-readable format.
  public String formatToText() {
    StringBuilder sb = new StringBuilder();

    sb.append("Roots: ").append(getRoots().size()).append("\n");
    for (GObject obj : getRoots()) {
      sb.append("- ").append(obj).append("\n");
    }

    sb.append("NonRoots: ").append(getObjects().size() - getRoots().size()).append("\n");
    for (GObject obj : getObjects()) {
      if (obj.isRoot()) {
        continue;
      }
      sb.append("- ").append(obj).append("\n");
    }

    sb.append("References: ").append(getReferences().size()).append("\n");
    for (GReference ref : getReferences()) {
      sb.append("- ").append(ref).append("\n");
    }

    if (!objects.isEmpty()) {
      sb.append("Adjacent Table:").append("\n");
      for (GID source : refTo.keySet()) {
        sb.append("- ").append(source).append(": ");
        if (refTo.get(source).isEmpty()) {
          sb.append("<empty>").append("\n");
        } else {
          sb.append(
                  refTo.get(source).stream()
                      .map(t -> t + ":" + getReference(source, t).getType().name())
                      .collect(Collectors.joining(", ")))
              .append("\n");
        }
      }

      sb.append("Reachability:").append("\n");
      Map<GID, GReference.Type> reachability = markReachability();
      for (GID id : reachability.keySet()) {
        sb.append("- ").append(id).append(": ").append(reachability.get(id).name()).append("\n");
      }
    }

    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    GGraph graph = (GGraph) o;
    return Objects.equals(objects, graph.objects)
        && Objects.equals(references, graph.references)
        && Objects.equals(refTo, graph.refTo)
        && Objects.equals(refBy, graph.refBy);
  }

  @Override
  public int hashCode() {
    return Objects.hash(objects, references, refTo, refBy);
  }
}
