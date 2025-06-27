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

import io.connglli.gorrect.graph.GReference;
import io.connglli.gorrect.graph.ObjectGraph;
import io.connglli.gorrect.graph.Operation;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * SwapReferentsOperation swaps the referents of two references. Even though the reachability of all
 * objects remain unchanged. This drastically changes the reference relation in the graph, including
 * the reference chains of each object, and the reachability strength of each object.
 */
@RequiredArgsConstructor
public class SwapReferentsOperation extends Operation {
    @Getter
    @NonNull
    private final Integer refId1;
    @Getter
    @NonNull
    private final Integer refId2;

    @Override
    public ObjectGraph applyTo(ObjectGraph graph) {
        GReference ref1 = graph.getReference(refId1);
        GReference ref2 = graph.getReference(refId2);
        graph.removeReference(refId1);
        graph.removeReference(refId2);
        graph.addReference(ref1.getReferer(), ref2.getReferent(), ref1.getType());
        graph.addReference(ref2.getReferer(), ref1.getReferent(), ref2.getType());
        return graph;
    }
}
