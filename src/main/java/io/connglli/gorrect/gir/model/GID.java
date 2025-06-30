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

package io.connglli.gorrect.gir.model;

import io.connglli.gorrect.utility.GAssert;
import lombok.NonNull;

/**
 * Represents a globally unique identifier (GID) used in GIR. This class is immutable and
 * comparable.
 */
public class GID implements Comparable<GID> {
    @NonNull
    private final String id;

    public GID(@NonNull String id) {
        GAssert.set(!id.isEmpty(), "GID cannot be empty");
        this.id = id;
    }

    public GID(@NonNull GID other) {
        this.id = other.id;
    }

    public String get() {
        return id;
    }

    @Override
    public int compareTo(@NonNull GID other) {
        return id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof GID) && id.equals(((GID) obj).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
