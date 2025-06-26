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
import io.connglli.gorrect.utility.GAssert;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * SoftenRefOperation softens the type of a reference one layer up. This will drastically change the
 * reachability strength of each object in the graph.
 */
@RequiredArgsConstructor
public class SoftenRefOperation extends Operation {
    @Getter
    @NonNull
    private final Integer refId;

    @Override
    public ObjectGraph applyTo(ObjectGraph graph) {
        GReference ref = graph.getReference(refId);
        switch (ref.getType()) {
            case WEAK:
                GAssert.panic(String.format(
                        "The reference with id %d is already a weak reference; it cannot be weakened any more as we currently do not support phantom references",
                        refId));
                break;
            case STRONG:
                ref.setType(GReference.Type.SOFT);
                break;
            case SOFT:
                ref.setType(GReference.Type.WEAK);
                break;
            default:
                GAssert.panic("Cannot reach here");
        }
        return graph;
    }
}
