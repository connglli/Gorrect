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

import io.connglli.gorrect.gir.model.visitor.ObjectBreadthFirstVisitor;
import io.connglli.gorrect.gir.model.visitor.ObjectVisitor;
import io.connglli.gorrect.gir.model.visitor.ReferenceVisitor;
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
    private final Map<GID, Set<GID>> refTo; // ID -> {ID}: Refer-to table
    private final Map<GID, Set<GID>> refBy; // ID -> {ID}: Referred-by table

    public ObjectGraph() {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.refTo = new HashMap<>();
        this.refBy = new HashMap<>();
    }

    public ObjectGraph(@NonNull ObjectGraph other) {
        this();
        reset(other);
    }

    public void reset(@NonNull ObjectGraph other) {
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
            this.refTo.put(refererId,
                    other.refTo.get(refererId).stream().map(GID::new).collect(Collectors.toSet()));
        }

        for (GID referentId : other.refBy.keySet()) {
            this.refBy.put(referentId,
                    other.refBy.get(referentId).stream().map(GID::new).collect(Collectors.toSet()));
        }
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
        GAssert.set(hasObject(objId), String.format("Object (with id %s) does not exist", objId));
        return objects.get(objId);
    }

    public boolean hasRoot(@NonNull GID objId) {
        return hasObject(objId) && getObject(objId).isRoot();
    }

    public @NonNull GObject getRoot(@NonNull GID rootId) {
        GObject obj = getObject(rootId);
        GAssert.set(obj.isRoot(),
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
        return refTo.containsKey(refererId) && refTo.get(refererId).contains(referentId);
    }

    public @NonNull GReference getReference(@NonNull GID refererId, @NonNull GID referentId) {
        GAssert.set(hasReference(refererId, referentId), String.format(
                "Reference from object %s to object %s does not exist", refererId, referentId));
        for (GReference ref : references.values()) {
            if (ref.getReferer().equals(refererId) && ref.getReferent().equals(referentId)) {
                return ref;
            }
        }
        GAssert.panic("Cannot reach here");
        return references.get(new GID("null"));
    }

    public List<GObject> getReferents(@NonNull GID refererId) {
        GAssert.set(hasObject(refererId),
                String.format("Object with id %s does not exist", refererId));
        return refTo.get(refererId).stream().map(this::getObject).collect(Collectors.toList());
    }

    public List<GObject> getReferers(@NonNull GID referentId) {
        GAssert.set(hasObject(referentId),
                String.format("Object with id %s does not exist", referentId));
        return refBy.get(referentId).stream().map(this::getObject).collect(Collectors.toList());
    }

    /// Add an object into the graph. Note that it is the user's responsibility to ensure that the
    /// added object is reachable from our root objects.
    public GID addObject(@NonNull GID objId, GObject.Type type, long size) {
        GAssert.set(!objects.containsKey(objId), "The id %s is already used by another object",
                objId);
        GAssert.set(!references.containsKey(objId),
                "The id %s is already used by another reference", objId);
        objects.put(objId, new GObject(objId, type, size));
        refTo.put(objId, new HashSet<>());
        refBy.put(objId, new HashSet<>());
        return objId;
    }

    /// Remove an object from the graph. This operation may cause certain objects unreachable. Call
    /// removeUnreachableObjects() to compact the graph.
    public void removeObject(@NonNull GID objId) {
        GAssert.set(hasObject(objId), String.format("Object with id %s does not exist", objId));

        // Remove all references connected to this object
        List<GID> refsToRemove = new ArrayList<>();
        for (GReference ref : references.values()) {
            if (ref.getReferer().equals(objId) || ref.getReferent().equals(objId)) {
                refsToRemove.add(ref.getId());
            }
        }
        for (GID refId : refsToRemove) {
            references.remove(refId);
        }

        // Update adjacent tables
        refTo.remove(objId);
        refTo.values().forEach(referents -> {
            referents.remove(objId);
        });
        refBy.remove(objId);
        refBy.values().forEach(referers -> {
            referers.remove(objId);
        });

        objects.remove(objId);
    }

    /// Add a reference into the graph. Note that it is the user's responsibility to ensure that the
    /// referrer and the referent all exist in the graph.
    @SuppressWarnings("UnusedReturnValue")
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
        refTo.get(refererId).add(referentId);
        refBy.get(referentId).add(refererId);

        return refId;
    }

    /// Remove a reference from the graph. This operation may cause certain objects unreachable.
    /// Call removeUnreachableObjects() to compact the graph.
    public void removeReference(@NonNull GID refId) {
        GAssert.set(hasReference(refId),
                String.format("Reference with id %s does not exist", refId));
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
    public void accept(ObjectVisitor visitor) {
        visitor.visit(this);
    }

    /// Visit all references in the object graph in a specific order
    public void accept(ReferenceVisitor visitor) {
        visitor.visit(this);
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
     * More: https://docs.oracle.com/javase/10/docs/api/java/lang/ref/package-summary.html
     *
     * @formatter:on
     */

    /// Remove unreachable objects from the graph. An unreachable object is an object that is not
    /// reachable from any root object.
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

    /// Remove weakly reachable objects from the graph. A weakly reachable object is an object that
    /// is reachable from a root object through weak references, but not strongly or softly
    /// reachable.
    public void removeWeaklyReachableObjects() {
        Set<GID> weaklyReachableObjs = getWeaklyReachableObjects();
        weaklyReachableObjs.forEach(id -> {
            if (hasObject(id)) {
                this.removeObject(id);
            }
        });
        removeUnreachableObjects();
    }

    /// Remove softly reachable objects from the graph. A softly reachable object is an object that
    /// is reachable from a root object through soft references, but not strongly reachable.
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

    /// Get all strongly reachable objects in the graph. A strongly reachable object is an object
    /// that is reachable from a root object without traversing any reference objects.
    public Set<GID> getStronglyReachableObjects() {
        return markReachability().entrySet().stream()
                .filter(entry -> entry.getValue() == GReference.Type.STRONG).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /// Get all softly reachable objects in the graph. A softly reachable object is an object
    /// that is reachable from a root object through soft references, but not strongly reachable.
    public Set<GID> getSoftlyReachableObjects() {
        return markReachability().entrySet().stream()
                .filter(entry -> entry.getValue() == GReference.Type.SOFT).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /// Get all weakly reachable objects in the graph. A weakly reachable object is an object that
    /// is reachable from a root object through weak references, but not strongly or softly
    /// reachable.
    public Set<GID> getWeaklyReachableObjects() {
        return markReachability().entrySet().stream()
                .filter(entry -> entry.getValue() == GReference.Type.WEAK).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /// Get all reachable objects in the graph. A reachable object is an object that is reachable
    /// from a root object through any type of reference (strong, soft, or weak).
    public Set<GID> getReachableObjects() {
        return markReachability().keySet();
    }

    /// Mark the reachability of each object in the graph. The reachability is determined by the
    /// references between objects. The result is a map from object ID to its reachability type.
    private Map<GID, GReference.Type> markReachability() {
        Map<GID, GReference.Type> result = new HashMap<>();
        accept(new ObjectBreadthFirstVisitor() {
            @Override
            public void visit(@NonNull ObjectGraph graph, @NonNull GObject referent) {
                GID referentId = referent.getId();
                if (referent.isRoot()) {
                    result.put(referentId, GReference.Type.STRONG);
                    return;
                }
                GReference.Type referStrongestType = null;
                for (GObject referer : graph.getReferers(referentId)) {
                    GID refererId = referer.getId();
                    GReference.Type referType = result.getOrDefault(refererId, null);
                    if (referType == null) {
                        continue; // Back-edges doesn't matter as the referer relies on us
                    }
                    GReference.Type referenceType =
                            graph.getReference(refererId, referentId).getType();
                    GReference.Type currentReachabilityType = null;
                    if (referType == GReference.Type.STRONG) {
                        currentReachabilityType = referenceType;
                    } else if (referType == GReference.Type.SOFT) {
                        currentReachabilityType =
                                referenceType == GReference.Type.WEAK ? GReference.Type.WEAK
                                        : GReference.Type.SOFT;
                    } else if (referType == GReference.Type.WEAK) {
                        currentReachabilityType = GReference.Type.WEAK;
                    } else {
                        GAssert.panic("Unknown reference type: %s", referType);
                    }
                    if (referStrongestType == null
                            || currentReachabilityType.strongerThan(referStrongestType)) {
                        referStrongestType = currentReachabilityType;
                    }
                }
                result.put(referentId, referStrongestType);
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
                    sb.append(refTo.get(source).stream()
                            .map(t -> t + ":" + getReference(source, t).getType().name())
                            .collect(Collectors.joining(", "))).append("\n");
                }
            }

        }

        return sb.toString();
    }
}
