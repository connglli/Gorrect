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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

/**
 * GRef represents a reference between two objects
 */
@Data
@AllArgsConstructor
public class GReference {
    public enum Type {
        STRONG, SOFT, WEAK;
    }

    private final int id;
    private final int source;
    private final int target;
    private Type type;

    public GReference(@NonNull GReference other) {
        this.id = other.id;
        this.source = other.source;
        this.target = other.target;
        this.type = other.type;
    }

    @Override
    public String toString() {
        return "ref(" + id + ": " + source + "-[" + type.name() + "]->" + target + ")";
    }
}
