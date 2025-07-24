/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
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

package io.connglli.gorrect.gir.utility;

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * UnmodifiableGraph is a wrapper around GGraph that provides read-only access to the graph's
 * properties and methods. It also maintains a single copy of reachability information.
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class UnmodifiableGraph {

  @NonNull private final GGraph graph;
  private Map<GID, GReference.Type> reachability = null;

  public static UnmodifiableGraph of(@NonNull GGraph graph) {
    return new UnmodifiableGraph(graph);
  }

  // -=======================================================
  // - Basic methods (delegation to GGraph)
  // -=======================================================

  public List<GObject> getRoots() {
    return graph.getRoots();
  }

  public List<GObject> getObjects() {
    return graph.getObjects();
  }

  public boolean hasObject(@NonNull GID objId) {
    return graph.hasObject(objId);
  }

  @NonNull
  public GObject getObject(@NonNull GID objId) {
    return graph.getObject(objId);
  }

  public boolean hasRoot(@NonNull GID objId) {
    return graph.hasRoot(objId);
  }

  @NonNull
  public GObject getRoot(@NonNull GID rootId) {
    return graph.getRoot(rootId);
  }

  public boolean hasNonRoot(@NonNull GID objId) {
    return graph.hasNonRoot(objId);
  }

  @NonNull
  public GObject getNonRoot(@NonNull GID objId) {
    return graph.getNonRoot(objId);
  }

  public List<GReference> getReferences() {
    return graph.getReferences();
  }

  public boolean hasReference(@NonNull GID refId) {
    return graph.hasReference(refId);
  }

  @NonNull
  public GReference getReference(@NonNull GID refId) {
    return graph.getReference(refId);
  }

  public boolean hasReference(@NonNull GID refererId, @NonNull GID referentId) {
    return graph.hasReference(refererId, referentId);
  }

  @NonNull
  public GReference getReference(@NonNull GID refererId, @NonNull GID referentId) {
    return graph.getReference(refererId, referentId);
  }

  public List<GReference> getOutgoingReferences(@NonNull GID refererId) {
    return graph.getOutgoingReferences(refererId);
  }

  public List<GReference> getIncomingReferences(@NonNull GID referentId) {
    return graph.getIncomingReferences(referentId);
  }

  public List<GObject> getReferents(@NonNull GID refererId) {
    return graph.getReferents(refererId);
  }

  public List<GObject> getReferers(@NonNull GID referentId) {
    return graph.getReferers(referentId);
  }

  // -=======================================================
  // - Reachability methods (maintaining one copy)
  // -=======================================================

  public Map<GID, GReference.Type> getReachability() {
    if (reachability == null) {
      reachability = graph.markReachability();
    }
    return Collections.unmodifiableMap(reachability);
  }

  public GReference.Type getReachability(GID objectId) {
    return getReachability().get(objectId);
  }

  public boolean isReachable(GID objectId) {
    return getReachability().containsKey(objectId);
  }

  public boolean isUnreachable(GID objectId) {
    return !getReachability().containsKey(objectId);
  }

  public boolean isStronglyReachable(GID objectId) {
    return getReachability().get(objectId) == GReference.Type.STRONG;
  }

  public boolean isSoftlyReachable(GID objectId) {
    return getReachability().get(objectId) == GReference.Type.SOFT;
  }

  public boolean isWeaklyReachable(GID objectId) {
    return getReachability().get(objectId) == GReference.Type.WEAK;
  }

  public List<GID> getReachableObjects() {
    return new ArrayList<>(getReachability().keySet());
  }

  public List<GID> getStronglyReachableObjects() {
    Map<GID, GReference.Type> reachability = getReachability();
    return reachability.entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.STRONG)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }

  public List<GID> getSoftlyReachableObjects() {
    Map<GID, GReference.Type> reachability = getReachability();
    return reachability.entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.SOFT)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }

  public List<GID> getWeaklyReachableObjects() {
    Map<GID, GReference.Type> reachability = getReachability();
    return reachability.entrySet().stream()
        .filter(entry -> entry.getValue() == GReference.Type.WEAK)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }
}
