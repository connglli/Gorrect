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

/**
 * ObjectGraph represents a directed graph of objects. Where each node represents an object and an
 * edge between two nodes represent a reference. References are also labeled with different
 * strengths to model different types of references. It is important to note that, the object graph
 * is designed as simple as enough, so it is NOT a multi-graph. This means that there is only one
 * reference between any two objects u and v for each direction u->v and v->u, respectively.
 * Currently, we assume there's only one thread and all root objects live on that thread.
 */
public class ObjectGraph {
    private final Map<Integer, GObject> objects;
    private final Map<Integer, GReference> references;
    private int nextObjId;
    private int nextRefId;

    public ObjectGraph() {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.nextObjId = 0;
        this.nextRefId = 0;
    }

    public ObjectGraph(ObjectGraph other) {
        this.objects = new HashMap<>();
        this.references = new HashMap<>();
        this.nextObjId = other.nextObjId;
        this.nextRefId = other.nextRefId;

        for (GObject obj : other.objects.values()) {
            this.objects.put(obj.getId(), new GObject(obj));
        }

        for (GReference ref : other.references.values()) {
            this.references.put(ref.getId(), new GReference(ref));
        }
    }


    public List<GObject> getRoots() {
        return objects.values().stream().filter(o -> o.getType() == GObject.Type.GC_ROOT)
                .collect(Collectors.toList());
    }

    public List<GObject> getNonRoots() {
        return objects.values().stream().filter(o -> o.getType() != GObject.Type.GC_ROOT)
                .collect(Collectors.toList());
    }

    public List<GObject> getObjects() {
        return new ArrayList<>(objects.values());
    }

    public List<GReference> getReferences() {
        return new ArrayList<>(references.values());
    }

    public Map<Integer, Set<Integer>> getAdjacentTable() {
        Map<Integer, Set<Integer>> table = new HashMap<>();
        for (var ref : references.values()) {
            table.computeIfAbsent(ref.getSource(), unused -> new HashSet<>()).add(ref.getTarget());
        }
        return table;
    }

    public int addObject(GObject.Type type) {
        int id = nextObjId++;
        objects.put(id, new GObject(id, type));
        return id;
    }

    public void removeObject(int objId) {
        if (!objects.containsKey(objId))
            return;

        // Remove all references connected to this object
        List<Integer> refsToRemove = new ArrayList<>();
        for (GReference ref : references.values()) {
            if (ref.getSource() == objId || ref.getTarget() == objId) {
                refsToRemove.add(ref.getId());
            }
        }

        for (int refId : refsToRemove) {
            references.remove(refId);
        }

        objects.remove(objId);
    }

    public int addReference(int fromId, int toId, GReference.Type type) {
        GAssert.set(objects.containsKey(fromId), String.format(
                "Invalid from object ID %d for adding as the source of a new reference", fromId));
        GAssert.set(objects.containsKey(toId), String.format(
                "Invalid to object ID %d for adding as the target of a new reference", toId));

        int refId = nextRefId++;
        references.put(refId, new GReference(refId, fromId, toId, type));

        return refId;
    }

    public void removeReference(int refId) {
        references.remove(refId);
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
        GAssert.set(objects.get(rootId).getType() == GObject.Type.GC_ROOT,
                String.format("Object with ID %d is not a root object", rootId));

        Set<Integer> visited = new HashSet<>();
        Queue<Integer> queue = new LinkedList<>();

        queue.offer(rootId);
        visited.add(rootId);

        while (!queue.isEmpty()) {
            int current = queue.poll();

            for (GReference ref : references.values()) {
                if (ref.getSource() == current && allowedTypes.contains(ref.getType())) {
                    if (!visited.contains(ref.getTarget())) {
                        visited.add(ref.getTarget());
                        queue.offer(ref.getTarget());
                    }
                }
            }
        }

        return visited;
    }
}
