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

package io.connglli.gorrect.graph;

import java.util.*;
import java.util.stream.Collectors;

import io.connglli.gorrect.utility.GAssert;
import lombok.NonNull;

/**
 * ObjectGraph represents a directed graph of objects. Where each node represents an object and an
 * edge between two nodes represent a reference. References are also labeled with different
 * strengths to model different types of references. It is important to note that, the object graph
 * is designed as simple as enough, so it is NOT a multi-graph. This means that there is only one
 * reference between any two objects u and v for each direction u->v and v->u, respectively.
 * Currently, we assume there's only one thread and all root objects live on that thread.
 */
public class ObjectGraph {
    private final Map<Integer, GObject> objects; // ID -> Object
    private final Map<Integer, GReference> references; // ID -> Reference
    private final Map<Integer, Set<Integer>> adjTab; // ID -> {ID}: Adjacent table

    private int nextObjId;
    private int nextRefId;

    public ObjectGraph() {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.adjTab = new HashMap<>();
        this.nextObjId = 0;
        this.nextRefId = 0;
    }

    public ObjectGraph(ObjectGraph other) {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.adjTab = new HashMap<>();
        this.nextObjId = other.nextObjId;
        this.nextRefId = other.nextRefId;

        for (GObject obj : other.objects.values()) {
            this.objects.put(obj.getId(), new GObject(obj));
        }

        for (GReference ref : other.references.values()) {
            this.references.put(ref.getId(), new GReference(ref));
        }

        for (Integer refererId : other.adjTab.keySet()) {
            this.adjTab.put(refererId, new HashSet<>(other.adjTab.get(refererId)));
        }
    }

    public List<GObject> getRoots() {
        return objects.values().stream().filter(o -> o.getType() == GObject.Type.GCROOT)
                .collect(Collectors.toList());
    }

    public List<GObject> getNonRoots() {
        return objects.values().stream().filter(o -> o.getType() != GObject.Type.GCROOT)
                .collect(Collectors.toList());
    }

    public List<GObject> getObjects() {
        return new ArrayList<>(objects.values());
    }

    public boolean hasObject(int objId) {
        return objects.containsKey(objId);
    }

    public @NonNull GObject getObject(int objId) {
        GAssert.set(hasObject(objId), String.format("Object (with id %d) does not exist", objId));
        return objects.get(objId);
    }

    public boolean hasRoot(int objId) {
        return hasObject(objId) && getObject(objId).getType() == GObject.Type.GCROOT;
    }

    public @NonNull GObject getRoot(int rootId) {
        GObject obj = getObject(rootId);
        GAssert.set(obj.getType() == GObject.Type.GCROOT,
                String.format("Object (with id %d) is not a root object", rootId));
        return obj;
    }

    public boolean hasNonRoot(int objId) {
        return hasObject(objId) && getObject(objId).getType() != GObject.Type.GCROOT;
    }

    public @NonNull GObject getNonRoot(int objId) {
        GObject obj = getObject(objId);
        GAssert.set(obj.getType() != GObject.Type.GCROOT,
                String.format("Object (with id %d) is a root object", objId));
        return obj;
    }

    public List<GReference> getReferences() {
        return new ArrayList<>(references.values());
    }

    public boolean hasReference(int refId) {
        return references.containsKey(refId);
    }

    public @NonNull GReference getReference(int refId) {
        GAssert.set(hasReference(refId),
                String.format("Reference with id %d does not exist", refId));
        return references.get(refId);
    }

    public boolean hasReference(int refererId, int referentId) {
        return adjTab.containsKey(refererId) && adjTab.get(refererId).contains(referentId);
    }

    public @NonNull GReference getReference(int refererId, int referentId) {
        GAssert.set(hasReference(refererId, referentId), String.format(
                "Reference from object %d to object %d does not exist", refererId, referentId));
        for (GReference ref : references.values()) {
            if (ref.getReferer() == refererId && ref.getReferent() == referentId) {
                return ref;
            }
        }
        GAssert.panic("Cannot reach here");
        return references.get(0);
    }

    public Map<Integer, Set<Integer>> getAdjacentTable() {
        return adjTab;
    }

    /// Add an object into the graph. Note that it is the user's responsibility to ensure that the
    /// added object is reachable from our root objects.
    public int addObject(GObject.Type type, long size) {
        int id = nextObjId++;
        objects.put(id, new GObject(id, type, size));
        adjTab.put(id, new HashSet<>());
        return id;
    }

    /// Remove an object from the graph. This operation may cause certain objects unreachable. Call
    /// removeUnreachableObjects() to compact the graph.
    public void removeObject(int objId) {
        GAssert.set(hasObject(objId), String.format("Object with id %d does not exist", objId));

        // Remove all references connected to this object
        List<Integer> refsToRemove = new ArrayList<>();
        for (GReference ref : references.values()) {
            if (ref.getReferer() == objId || ref.getReferent() == objId) {
                refsToRemove.add(ref.getId());
            }
        }
        for (int refId : refsToRemove) {
            references.remove(refId);
        }

        // Update adjacent table
        adjTab.remove(objId);
        adjTab.values().forEach(adjs -> {
            adjs.remove(objId);
        });

        objects.remove(objId);
    }

    /// Add a reference into the graph. Note that it is the user's responsibility to ensure that the
    /// referrer and the referent all exist in the graph.
    public int addReference(int refererId, int referentId, GReference.Type type) {
        GAssert.set(objects.containsKey(refererId), String.format(
                "The object with id %d for adding as the referer of a new reference does not exist",
                refererId));
        GAssert.set(objects.containsKey(referentId), String.format(
                "The object with id %d for adding as the referent of a new reference does not exist",
                referentId));
        GAssert.set(!hasReference(refererId, referentId),
                String.format("A reference already exists between object %d and object %d",
                        refererId, referentId));

        int refId = nextRefId++;
        references.put(refId, new GReference(refId, refererId, referentId, type));
        adjTab.get(refererId).add(referentId);

        return refId;
    }

    /// Remove a reference from the graph. This operation may cause certain objects unreachable.
    /// Call removeUnreachableObjects() to compact the graph.
    public void removeReference(int refId) {
        GAssert.set(hasReference(refId),
                String.format("Reference with id %d does not exist", refId));
        GReference ref = references.remove(refId);
        adjTab.get(ref.getReferer()).remove(ref.getReferent());
    }

    /// Remove a reference from the graph. This operation may cause certain objects unreachable.
    /// Call removeUnreachableObjects() to compact the graph.
    public void removeReference(int refererId, int referentId) {
        removeReference(getReference(refererId, referentId).getId());
    }

    /// Apply an operation to the graph and return this for chained operations
    public @NonNull ObjectGraph apply(@NonNull Operation op) {
        GAssert.set(op.applyTo(this) == this, "The operation generated a new graph");
        return this;
    }

    /*
     * @formatter:off
     *
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
     * @formatter:on
     */

    public void removeUnreachableObjects() {
        Set<Integer> reachableObjs = getReachableObjects();
        Set<Integer> unreachableObjs = objects.keySet().stream()
                .filter(id -> !reachableObjs.contains(id)).collect(Collectors.toSet());
        unreachableObjs.forEach(objects::remove);
        Set<Integer> unreachableRefs = references.keySet().stream()
                .filter(id -> unreachableObjs.contains(references.get(id).getReferer()))
                .collect(Collectors.toSet());
        unreachableRefs.forEach(references::remove);
        unreachableObjs.forEach(adjTab::remove);
    }

    public Set<Integer> getStronglyReachableObjects(int rootId) {
        return getReachableObjects(rootId, EnumSet.of(GReference.Type.STRONG));
    }

    public Set<Integer> getStronglyReachableObjects() {
        Set<Integer> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getStronglyReachableObjects(rootObj.getId()));
        }
        return result;
    }

    public Set<Integer> getSoftlyReachableObjects(int rootId) {
        Set<Integer> result = getReachableObjects(rootId,
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT));
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<Integer> getSoftlyReachableObjects() {
        Set<Integer> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(),
                    EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT)));
        }
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<Integer> getWeaklyReachableObjects(int rootId) {
        Set<Integer> result = getReachableObjects(rootId,
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK));
        result.removeAll(getSoftlyReachableObjects());
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<Integer> getWeaklyReachableObjects() {
        Set<Integer> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(), EnumSet.of(GReference.Type.STRONG,
                    GReference.Type.SOFT, GReference.Type.WEAK)));
        }
        result.removeAll(getStronglyReachableObjects());
        result.removeAll(getSoftlyReachableObjects());
        return result;
    }

    public Set<Integer> getReachableObjects() {
        Set<Integer> result = new HashSet<>();
        EnumSet<GReference.Type> types =
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK);
        GAssert.set(types.size() == GReference.Type.values().length,
                "Not all reachable types are considered");
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(), types));
        }
        return result;
    }

    private Set<Integer> getReachableObjects(int rootId, Set<GReference.Type> allowedTypes) {

        // TODO: Optimize reachability algorithm implementations by running reachability only once.
        // This can be accomplished by giving each object an reachability mark and populating the
        // mark while we were visiting the graph. For example, when ONE of the parent of an object
        // is strongly reachable, this object must be strongly/softly/weakly reachable if the
        // reference type between the parent and the object is strong/soft/weak.

        GAssert.set(objects.containsKey(rootId),
                String.format("Object with ID %d does not exist", rootId));
        GAssert.set(objects.get(rootId).getType() == GObject.Type.GCROOT,
                String.format("Object with ID %d is not a root object", rootId));

        Set<Integer> visited = new HashSet<>();
        Queue<Integer> queue = new LinkedList<>();

        queue.offer(rootId);
        visited.add(rootId);

        while (!queue.isEmpty()) {
            int current = queue.poll();

            for (GReference ref : references.values()) {
                if (ref.getReferer() == current && allowedTypes.contains(ref.getType())) {
                    if (!visited.contains(ref.getReferent())) {
                        visited.add(ref.getReferent());
                        queue.offer(ref.getReferent());
                    }
                }
            }
        }

        return visited;
    }
}
