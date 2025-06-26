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
import io.connglli.gorrect.graph.ObjectGraph;
import io.connglli.gorrect.graph.Operation;
import lombok.RequiredArgsConstructor;

/**
 * AddRootObjOperation adds a new object to the graph as one of a root object. This operation opens
 * a new channel for adding a large number of reachable objects in the future. This operation aims
 * to increase the heap usage.
 */
@RequiredArgsConstructor
public class AddRootObjOperation extends Operation {

    @Override
    public ObjectGraph applyTo(ObjectGraph graph) {
        graph.addObject(GObject.Type.GCROOT);
        return graph;
    }
}
