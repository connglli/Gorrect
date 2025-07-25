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

package io.connglli.gorrect.gir.model.visitor;

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import lombok.NonNull;

/**
 * A visitor that traverses an object graph in breadth-first order. It visits each object and its
 * referents (if the reference is allowed), starting from the roots of the graph.
 */
public abstract class GObjectBreadthFirstVisitor implements GObjectVisitor {

  @NonNull
  public GReference.Type[] allowReferenceTypes() {
    return GReference.Type.values();
  }

  @Override
  public void visit(@NonNull GGraph graph) {
    List<GReference.Type> allowedReferenceTypes = List.of(allowReferenceTypes());
    Set<GID> visited = new HashSet<>();
    Queue<GID> queue = new LinkedList<>();
    for (GObject root : graph.getRoots()) {
      queue.offer(root.getId());
    }
    while (!queue.isEmpty()) {
      GID refererId = queue.poll();
      GObject referer = graph.getObject(refererId);
      if (visited.contains(refererId)) {
        continue;
      }
      visited.add(refererId);
      referer.accept(graph, this);
      for (GObject referent : graph.getReferents(refererId)) {
        GID referentId = referent.getId();
        if (allowedReferenceTypes.contains(graph.getReference(refererId, referentId).getType())) {
          queue.offer(referentId); // Only enqueue referents with allowed reference types
        }
      }
    }
  }
}
