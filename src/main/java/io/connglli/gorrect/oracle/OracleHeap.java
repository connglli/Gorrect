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

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.Assert;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NonNull;

/**
 * OracleHeap is a class that simulates a heap memory management system for a language virtual
 * machine. It provides methods to allocate, free, and manage objects and references in the heap
 * with a explicit heap capacity.
 */
public class OracleHeap {

  // The language virtual machine that we're simulating
  @Getter(lombok.AccessLevel.PACKAGE)
  private final LangVM lvm;

  // The underlying object graph that we're manipulating
  @Getter(lombok.AccessLevel.PACKAGE)
  @NonNull
  private final GGraph graph;

  public OracleHeap(LangVM langVM) {
    lvm = langVM;
    graph = new GGraph();
  }

  public GGraph getState() {
    return getState(true);
  }

  public GGraph getState(boolean clean) {
    // We return a copy of the graph to ensure that the state is immutable
    GGraph state = new GGraph(graph);
    if (clean) {
      state.removeUnreachableObjects();
    }
    return state;
  }

  public boolean hasObject(@NonNull GID objectId) {
    return graph.hasObject(objectId);
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

  // -========================================================================
  // Initialization and Setup
  // -========================================================================

  public void initialize(@NonNull GGraph initState) {
    graph.reset(initState);
    // Notify the language VM about the initial state of the heap
    for (GObject object : graph.getObjects()) {
      lvm.onOracleAllocate(object.getId(), object.getSize(), object.getType());
    }
    for (GReference reference : graph.getReferences()) {
      lvm.onOracleReference(
          reference.getId(),
          new OracleObject(this, graph.getObject(reference.getReferer())),
          new OracleObject(this, graph.getObject(reference.getReferent())),
          reference.getType());
    }
  }

  // -========================================================================
  // Object Allocation and Free
  // -========================================================================

  @SuppressWarnings("UnusedReturnValue")
  public @NonNull OracleObject allocRootObject(@NonNull GID objectId, long size) {
    return allocateObject(objectId, size, GObject.Type.GCROOT);
  }

  public @NonNull OracleObject allocNonRootObject(@NonNull GID objectId, long size) {
    return allocateObject(objectId, size, GObject.Type.ORDINARY);
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
    Assert.set(size >= 0, "Object size must be non-negative, but got %d", size);
    lvm.onOracleAllocate(objectId, size, type);
    return new OracleObject(this, graph.getObject(graph.addObject(objectId, type, size)));
  }

  /* package */ void freeObject(@NonNull OracleObject object) {
    lvm.onOracleFree(object);
    graph.removeObject(object.getId());
  }

  // -========================================================================
  // Object Role Transition
  // -========================================================================

  void makeRoot(@NonNull OracleObject object) {
    Assert.set(!object.isRoot(), "Object with id %s is not a non-root object", object.getId());
    object.setType(GObject.Type.GCROOT);
  }

  void makeNonRoot(@NonNull OracleObject root) {
    Assert.set(root.isRoot(), "Object with id %s is not a root object", root.getId());
    root.setType(GObject.Type.ORDINARY);
  }

  // -========================================================================
  // Reference Addition and Deletion
  // -========================================================================

  /* package */ boolean hasReference(
      @NonNull OracleObject referer, @NonNull OracleObject referent) {
    return graph.hasReference(referer.getId(), referent.getId());
  }

  /* package */ OracleReference getReference(@NonNull GID referenceId) {
    return new OracleReference(this, graph.getReference(referenceId));
  }

  /* package */ OracleReference getReference(
      @NonNull OracleObject referer, @NonNull OracleObject referent) {
    return new OracleReference(this, graph.getReference(referer.getId(), referent.getId()));
  }

  /* package */ Set<OracleReference> getReferences(@NonNull OracleObject object) {
    return graph.getReferents(object.getId()).stream()
        .map(to -> new OracleReference(this, graph.getReference(object.getId(), to.getId())))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getReferents(@NonNull OracleObject referer) {
    return graph.getReferents(referer.getId()).stream()
        .map(to -> new OracleObject(this, to))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getStrongReferents(@NonNull OracleObject referer) {
    return graph.getReferents(referer.getId()).stream()
        .filter(
            to ->
                graph.getReference(referer.getId(), to.getId()).getType() == GReference.Type.STRONG)
        .map(to -> new OracleObject(this, to))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getSoftReferents(@NonNull OracleObject referer) {
    return graph.getReferents(referer.getId()).stream()
        .filter(
            to -> graph.getReference(referer.getId(), to.getId()).getType() == GReference.Type.SOFT)
        .map(to -> new OracleObject(this, to))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getWeakReferents(@NonNull OracleObject referer) {
    return graph.getReferents(referer.getId()).stream()
        .filter(
            to -> graph.getReference(referer.getId(), to.getId()).getType() == GReference.Type.WEAK)
        .map(to -> new OracleObject(this, to))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getReferers(@NonNull OracleObject referent) {
    return graph.getReferers(referent.getId()).stream()
        .map(from -> new OracleObject(this, from))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getStrongReferers(@NonNull OracleObject referent) {
    return graph.getReferers(referent.getId()).stream()
        .filter(
            from ->
                graph.getReference(from.getId(), referent.getId()).getType()
                    == GReference.Type.STRONG)
        .map(from -> new OracleObject(this, from))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getSoftReferers(@NonNull OracleObject referent) {
    return graph.getReferers(referent.getId()).stream()
        .filter(
            from ->
                graph.getReference(from.getId(), referent.getId()).getType()
                    == GReference.Type.SOFT)
        .map(from -> new OracleObject(this, from))
        .collect(Collectors.toSet());
  }

  /* package */ Set<OracleObject> getWeakReferers(@NonNull OracleObject referent) {
    return graph.getReferers(referent.getId()).stream()
        .filter(
            from ->
                graph.getReference(from.getId(), referent.getId()).getType()
                    == GReference.Type.WEAK)
        .map(from -> new OracleObject(this, from))
        .collect(Collectors.toSet());
  }

  /* package */ void strongReference(
      @NonNull GID referenceId, @NonNull OracleObject referer, @NonNull OracleObject referent) {
    reference(referenceId, referer, referent, GReference.Type.STRONG);
  }

  /* package */ void softReference(
      @NonNull GID referenceId, @NonNull OracleObject referer, @NonNull OracleObject referent) {
    reference(referenceId, referer, referent, GReference.Type.SOFT);
  }

  /* package */ void weakReference(
      @NonNull GID referenceId, @NonNull OracleObject referer, @NonNull OracleObject referent) {
    reference(referenceId, referer, referent, GReference.Type.WEAK);
  }

  /* package */ void reference(
      @NonNull GID referenceId,
      @NonNull OracleObject referer,
      @NonNull OracleObject referent,
      GReference.Type type) {
    lvm.onOracleReference(referenceId, referer, referent, type);
    graph.addReference(referenceId, referer.getId(), referent.getId(), type);
  }

  /* package */ void unlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {
    lvm.onOracleUnlink(referer, referent);
    graph.removeReference(referer.getId(), referent.getId());
  }

  /* package */ void redirect(
      @NonNull OracleReference reference, @NonNull OracleObject newReferent) {
    Assert.set(
        !newReferent.getId().equals(reference.getReferent().getId()),
        "Cannot redirect reference %s to the same referent",
        reference.getId());
    unlink(reference.getReferer(), reference.getReferent());
    reference(reference.getId(), reference.getReferer(), newReferent, reference.getType());
  }

  /* package */ void strengthen(@NonNull OracleReference reference) {
    Assert.set(
        reference.getType() != GReference.Type.STRONG,
        "Cannot strengthen a strong reference %s",
        reference.getId());
    reference.setType(reference.getType().strengthen());
  }

  /* package */ void soften(@NonNull OracleReference reference) {
    Assert.set(
        reference.getType() != GReference.Type.WEAK,
        "Cannot soften a weak reference %s",
        reference.getId());
    reference.setType(reference.getType().soften());
  }

  // -========================================================================
  // Heap Helper Methods
  // -========================================================================

  /* package */ long estimateNewObjectHeapSize(long requestedSize, GObject.Type type) {
    return lvm.estimateNewObjectHeapSize(requestedSize);
  }

  /* package */ long estimateObjectHeapSize(OracleObject object) {
    return lvm.estimateObjectHeapSize(object);
  }
}
