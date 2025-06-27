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

package io.connglli.gorrect.graph.ops;

import io.connglli.gorrect.graph.GObject;
import io.connglli.gorrect.graph.GReference;
import io.connglli.gorrect.graph.ObjectGraph;
import io.connglli.gorrect.graph.Operation;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * AddNonRootObjOperation adds a new object to the graph as one of a non-root object. To make the
 * object reachable, this operation requires a referer object to reference to it. This operation
 * also aims to increase the heap usage.
 */
@RequiredArgsConstructor
public class AddNonRootObjOperation extends Operation {
    @Getter
    private final Long objSize;
    @Getter
    @NonNull
    private final Integer refererId;
    @Getter
    @NonNull
    private final GReference.Type refType;

    @Override
    public ObjectGraph applyTo(ObjectGraph graph) {
        int toId = graph.addObject(GObject.Type.ORDINARY, objSize);
        graph.addReference(refererId, toId, refType);
        return graph;
    }
}
