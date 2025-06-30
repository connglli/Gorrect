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
    private final Map<GID, GObject> objects; // ID -> Object
    private final Map<GID, GReference> references; // ID -> Reference
    private final Map<GID, Set<GID>> adjTab; // ID -> {ID}: Adjacent table

    public ObjectGraph() {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.adjTab = new HashMap<>();
    }

    public ObjectGraph(@NonNull ObjectGraph other) {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.adjTab = new HashMap<>();

        for (GObject obj : other.objects.values()) {
            this.objects.put(obj.getId(), new GObject(obj));
        }

        for (GReference ref : other.references.values()) {
            this.references.put(ref.getId(), new GReference(ref));
        }

        for (GID refererId : other.adjTab.keySet()) {
            this.adjTab.put(refererId,
                    other.adjTab.get(refererId).stream().map(GID::new).collect(Collectors.toSet()));
        }
    }

    public List<GObject> getRoots() {
        return objects.values().stream().filter(o -> o.getType() == GObject.Type.GCROOT)
                .collect(Collectors.toList());
    }

    public List<GObject> getObjects() {
        return new ArrayList<>(objects.values());
    }

    public boolean hasObject(@NonNull GID objId) {
        return objects.containsKey(objId);
    }

    public @NonNull GObject getObject(@NonNull GID objId) {
        GAssert.set(hasObject(objId), String.format("Object (with id %s) does not exist", objId));
        return objects.get(objId);
    }

    public boolean hasRoot(@NonNull GID objId) {
        return hasObject(objId) && getObject(objId).getType() == GObject.Type.GCROOT;
    }

    public @NonNull GObject getRoot(@NonNull GID rootId) {
        GObject obj = getObject(rootId);
        GAssert.set(obj.getType() == GObject.Type.GCROOT,
                String.format("Object (with id %s) is not a root object", rootId));
        return obj;
    }

    public boolean hasNonRoot(@NonNull GID objId) {
        return hasObject(objId) && getObject(objId).getType() != GObject.Type.GCROOT;
    }

    public @NonNull GObject getNonRoot(@NonNull GID objId) {
        GObject obj = getObject(objId);
        GAssert.set(obj.getType() != GObject.Type.GCROOT,
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
        GAssert.set(hasReference(refId),
                String.format("Reference with id %s does not exist", refId));
        return references.get(refId);
    }

    public boolean hasReference(@NonNull GID refererId, @NonNull GID referentId) {
        return adjTab.containsKey(refererId) && adjTab.get(refererId).contains(referentId);
    }

    public @NonNull GReference getReference(@NonNull GID refererId, @NonNull GID referentId) {
        GAssert.set(hasReference(refererId, referentId), String.format(
                "Reference from object %s to object %s does not exist", refererId, referentId));
        for (GReference ref : references.values()) {
            if (ref.getReferer() == refererId && ref.getReferent() == referentId) {
                return ref;
            }
        }
        GAssert.panic("Cannot reach here");
        return references.get(new GID("null"));
    }

    public Set<GID> getReferents(@NonNull GID refererId) {
        GAssert.set(hasObject(refererId),
                String.format("Object with id %s does not exist", refererId));
        return adjTab.get(refererId);
    }

    /// Add an object into the graph. Note that it is the user's responsibility to ensure that the
    /// added object is reachable from our root objects.
    public GID addObject(@NonNull GID objId, GObject.Type type, long size) {
        GAssert.set(!objects.containsKey(objId), "The id %s is already used by another object",
                objId);
        GAssert.set(!references.containsKey(objId),
                "The id %s is already used by another reference", objId);
        objects.put(objId, new GObject(objId, type, size));
        adjTab.put(objId, new HashSet<>());
        return objId;
    }

    /// Remove an object from the graph. This operation may cause certain objects unreachable. Call
    /// removeUnreachableObjects() to compact the graph.
    public void removeObject(@NonNull GID objId) {
        GAssert.set(hasObject(objId), String.format("Object with id %s does not exist", objId));

        // Remove all references connected to this object
        List<GID> refsToRemove = new ArrayList<>();
        for (GReference ref : references.values()) {
            if (ref.getReferer() == objId || ref.getReferent() == objId) {
                refsToRemove.add(ref.getId());
            }
        }
        for (GID refId : refsToRemove) {
            references.remove(refId);
        }

        // Update adjacent table
        adjTab.remove(objId);
        adjTab.values().forEach(referents -> {
            referents.remove(objId);
        });

        objects.remove(objId);
    }

    /// Add a reference into the graph. Note that it is the user's responsibility to ensure that the
    /// referrer and the referent all exist in the graph.
    public GID addReference(@NonNull GID refId, @NonNull GID refererId, @NonNull GID referentId,
            GReference.Type type) {
        GAssert.set(objects.containsKey(refererId), String.format(
                "The object with id %s for adding as the referer of a new reference does not exist",
                refererId));
        GAssert.set(objects.containsKey(referentId), String.format(
                "The object with id %s for adding as the referent of a new reference does not exist",
                referentId));
        GAssert.set(!hasReference(refererId, referentId),
                String.format("A reference already exists between object %s and object %s",
                        refererId, referentId));

        references.put(refId, new GReference(refId, refererId, referentId, type));
        adjTab.get(refererId).add(referentId);

        return refId;
    }

    /// Remove a reference from the graph. This operation may cause certain objects unreachable.
    /// Call removeUnreachableObjects() to compact the graph.
    public void removeReference(@NonNull GID refId) {
        GAssert.set(hasReference(refId),
                String.format("Reference with id %s does not exist", refId));
        GReference ref = references.remove(refId);
        adjTab.get(ref.getReferer()).remove(ref.getReferent());
    }

    /// Remove a reference from the graph. This operation may cause certain objects unreachable.
    /// Call removeUnreachableObjects() to compact the graph.
    public void removeReference(@NonNull GID refererId, @NonNull GID referentId) {
        removeReference(getReference(refererId, referentId).getId());
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
        Set<GID> reachableObjs = getReachableObjects();
        Set<GID> unreachableObjs = objects.keySet().stream()
                .filter(id -> !reachableObjs.contains(id)).collect(Collectors.toSet());
        unreachableObjs.forEach(id -> {
            if (hasObject(id)) {
                this.removeObject(id);
            }
        });
    }

    public void removeWeaklyReachableObjects() {
        Set<GID> weaklyReachableObjs = getWeaklyReachableObjects();
        weaklyReachableObjs.forEach(id -> {
            if (hasObject(id)) {
                this.removeObject(id);
            }
        });
        removeUnreachableObjects();
    }

    public void removeSoftlyReachableObjects() {
        Set<GID> stronglyRechableObjects = getStronglyReachableObjects();
        Set<GID> otherObjects = objects.keySet().stream()
                .filter(id -> !stronglyRechableObjects.contains(id)).collect(Collectors.toSet());
        otherObjects.forEach(id -> {
            if (hasObject(id)) {
                this.removeObject(id);
            }
        });
        removeUnreachableObjects();
    }

    public Set<GID> getStronglyReachableObjects(@NonNull GID rootId) {
        return getReachableObjects(rootId, EnumSet.of(GReference.Type.STRONG));
    }

    public Set<GID> getStronglyReachableObjects() {
        Set<GID> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getStronglyReachableObjects(rootObj.getId()));
        }
        return result;
    }

    public Set<GID> getSoftlyReachableObjects(@NonNull GID rootId) {
        Set<GID> result = getReachableObjects(rootId,
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT));
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<GID> getSoftlyReachableObjects() {
        Set<GID> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(),
                    EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT)));
        }
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<GID> getWeaklyReachableObjects(@NonNull GID rootId) {
        Set<GID> result = getReachableObjects(rootId,
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK));
        result.removeAll(getSoftlyReachableObjects());
        result.removeAll(getStronglyReachableObjects());
        return result;
    }

    public Set<GID> getWeaklyReachableObjects() {
        Set<GID> result = new HashSet<>();
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(), EnumSet.of(GReference.Type.STRONG,
                    GReference.Type.SOFT, GReference.Type.WEAK)));
        }
        result.removeAll(getStronglyReachableObjects());
        result.removeAll(getSoftlyReachableObjects());
        return result;
    }

    public Set<GID> getReachableObjects() {
        Set<GID> result = new HashSet<>();
        EnumSet<GReference.Type> types =
                EnumSet.of(GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK);
        GAssert.set(types.size() == GReference.Type.values().length,
                "Not all reachable types are considered");
        for (GObject rootObj : getRoots()) {
            result.addAll(getReachableObjects(rootObj.getId(), types));
        }
        return result;
    }

    private Set<GID> getReachableObjects(@NonNull GID rootId, Set<GReference.Type> allowedTypes) {

        // TODO: Optimize reachability algorithm implementations by running reachability only once.
        // This can be accomplished by giving each object an reachability mark and populating the
        // mark while we were visiting the graph. For example, when ONE of the parent of an object
        // is strongly reachable, this object must be strongly/softly/weakly reachable if the
        // reference type between the parent and the object is strong/soft/weak.

        GAssert.set(objects.containsKey(rootId),
                String.format("Object with id %s does not exist", rootId));
        GAssert.set(objects.get(rootId).getType() == GObject.Type.GCROOT,
                String.format("Object with id %s is not a root object", rootId));

        Set<GID> visited = new HashSet<>();
        Queue<GID> queue = new LinkedList<>();

        queue.offer(rootId);
        visited.add(rootId);

        while (!queue.isEmpty()) {
            GID current = queue.poll();

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
