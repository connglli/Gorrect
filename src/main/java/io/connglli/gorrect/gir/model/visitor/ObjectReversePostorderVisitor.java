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

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.ObjectGraph;
import lombok.NonNull;

/**
 * A visitor that traverses an object graph in reverse postorder. It visits each object and its
 * referents, starting from the roots of the graph.
 */
public abstract class ObjectReversePostorderVisitor implements ObjectVisitor {

    @Override
    public void visit(@NonNull ObjectGraph graph) {
        Stack<GID> order = new Stack<>();
        Set<GID> visited = new HashSet<>();
        for (GObject root : graph.getRoots()) {
            visit0(graph, root.getId(), order, visited);
        }
        while (!order.isEmpty()) {
            visit(graph, graph.getObject(order.pop()));
        }
    }

    private void visit0(@NonNull ObjectGraph graph, @NonNull GID refererId, Stack<GID> order,
            Set<GID> visited) {
        if (visited.contains(refererId)) {
            return;
        }
        visited.add(refererId);
        for (GID referentId : graph.getReferents(refererId)) {
            visit0(graph, referentId, order, visited);
        }
        order.push(refererId);
    }
}
