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

package io.connglli.gorrect.oracle;

import java.util.Set;
import java.util.stream.Collectors;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.model.ObjectGraph;
import lombok.Getter;
import lombok.NonNull;

public class OracleHeap {

    // The language virtual machine that we're simulating
    @Getter(lombok.AccessLevel.PACKAGE)
    private final LangVM lvm;

    // The underlying object graph that we're manipulating
    @Getter(lombok.AccessLevel.PACKAGE)
    @NonNull
    private final ObjectGraph graph;

    public OracleHeap(LangVM langVM, @NonNull ObjectGraph initState) {
        lvm = langVM;
        graph = new ObjectGraph(initState);
        // Notify the language VM about the initial state of the heap
        for (GObject object : graph.getObjects()) {
            lvm.onOracleAllocate(object.getId(), object.getSize(), object.getType());
        }
        for (GReference reference : graph.getReferences()) {
            lvm.onOracleReference(reference.getId(),
                    new OracleObject(this, graph.getObject(reference.getReferer())),
                    new OracleObject(this, graph.getObject(reference.getReferent())),
                    reference.getType());
        }
    }

    public @NonNull OracleObject getObject(@NonNull GID objectId) {
        return new OracleObject(this, graph.getObject(objectId));
    }

    public @NonNull OracleObject getRootObject(@NonNull GID objectId) {
        return new OracleObject(this, graph.getRoot(objectId));
    }

    public @NonNull OracleObject getNonRootObject(@NonNull GID objectId) {
        return new OracleObject(this, graph.getNonRoot(objectId));
    }

    public @NonNull OracleReference getReference(@NonNull GID referenceId) {
        return new OracleReference(this, graph.getReference(referenceId));
    }

    public @NonNull OracleObject allocRootObject(@NonNull GID objectId, long size) {
        return allocateObject(objectId, size, GObject.Type.GCROOT);
    }

    public @NonNull OracleObject allocNonRootObject(@NonNull GID objectId, long size) {
        return allocateObject(objectId, size, GObject.Type.ORDINARY);
    }

    public void freeObject(@NonNull OracleObject object) {
        lvm.onOracleFree(object);
        graph.removeObject(object.getId());
    }

    public void clean() {
        lvm.onOracleClean();
        graph.removeUnreachableObjects();
    }

    public void compact() {
        lvm.onOracleCompact();
        graph.removeWeaklyReachableObjects();
    }

    public void stressCompact() {
        lvm.onOracleStressCompact();
        graph.removeSoftlyReachableObjects();
    }

    @NonNull
    /* package */ OracleObject allocateObject(@NonNull GID objectId, long size, GObject.Type type) {
        lvm.onOracleAllocate(objectId, size, type);
        return new OracleObject(this, graph.getObject(graph.addObject(objectId, type, size)));
    }

    /* package */ Set<OracleReference> getReferences(@NonNull OracleObject object) {
        return graph.getReferents(object.getId()).stream()
                .map(to -> new OracleReference(this, graph.getReference(object.getId(), to)))
                .collect(Collectors.toSet());
    }

    /* package */ Set<OracleObject> getReferents(@NonNull OracleObject referer) {
        return graph.getReferents(referer.getId()).stream()
                .map(to -> new OracleObject(this, graph.getObject(to))).collect(Collectors.toSet());
    }

    /* package */ Set<OracleObject> getStrongReferents(@NonNull OracleObject referer) {
        return graph.getReferents(referer.getId()).stream().filter(
                to -> graph.getReference(referer.getId(), to).getType() == GReference.Type.STRONG)
                .map(to -> new OracleObject(this, graph.getObject(to))).collect(Collectors.toSet());
    }

    /* package */ Set<OracleObject> getSoftReferents(@NonNull OracleObject referer) {
        return graph.getReferents(referer.getId()).stream().filter(
                to -> graph.getReference(referer.getId(), to).getType() == GReference.Type.SOFT)
                .map(to -> new OracleObject(this, graph.getObject(to))).collect(Collectors.toSet());
    }

    /* package */ Set<OracleObject> getWeakReferents(@NonNull OracleObject referer) {
        return graph.getReferents(referer.getId()).stream().filter(
                to -> graph.getReference(referer.getId(), to).getType() == GReference.Type.WEAK)
                .map(to -> new OracleObject(this, graph.getObject(to))).collect(Collectors.toSet());
    }

    /* package */ void strongReference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent) {
        reference(referenceId, referer, referent, GReference.Type.STRONG);
    }

    /* package */ void softReference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent) {
        reference(referenceId, referer, referent, GReference.Type.SOFT);
    }

    /* package */ void weakReference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent) {
        reference(referenceId, referer, referent, GReference.Type.WEAK);
    }

    /* package */ void reference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent, GReference.Type type) {
        lvm.onOracleReference(referenceId, referer, referent, type);
        graph.addReference(referenceId, referer.getId(), referent.getId(), type);
    }

    /* packge */ void unlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {
        lvm.onOracleUnlink(referer, referent);
        graph.removeReference(referer.getId(), referent.getId());
    }

    /* package */ long estimateNewObjectHeapSize(long requestedSize, GObject.Type type) {
        return lvm.estimateNewObjectHeapSize(requestedSize);
    }

    /* package */ long estimateObjectHeapSize(OracleObject object) {
        return lvm.estimateObjectHeapSize(object);
    }
}
