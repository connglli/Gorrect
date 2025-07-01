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

import java.util.Set;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.GAssert;
import lombok.NonNull;

public class OracleObject {

    @NonNull
    private final OracleHeap heap;

    @NonNull
    private final GObject object;

    public OracleObject(@NonNull OracleHeap heap, @NonNull GObject object) {
        this.heap = heap;
        this.object = object;
    }

    public GID getId() {
        return object.getId();
    }

    public GObject.Type getType() {
        return object.getType();
    }

    public void toRoot() {
        GAssert.set(!object.isRoot(), "Object with id %s is not a non-root object", getId());
        object.setType(GObject.Type.GCROOT);
    }

    public void toNonRoot() {
        GAssert.set(object.isRoot(), "Object with id %s is not a root object", getId());
        object.setType(GObject.Type.ORDINARY);
    }

    public long getSize() {
        return object.getSize();
    }

    public long getEstimatedHeapSize() {
        return heap.estimateObjectHeapSize(this);
    }

    public Set<OracleReference> getReferences() {
        return heap.getReferences(this);
    }

    public Set<OracleObject> getReferents() {
        return heap.getReferents(this);
    }

    public Set<OracleObject> getStrongReferents() {
        return heap.getStrongReferents(this);
    }

    public Set<OracleObject> getSoftReferents() {
        return heap.getSoftReferents(this);
    }

    public Set<OracleObject> getWeakReferents() {
        return heap.getWeakReferents(this);
    }

    public void strongReference(@NonNull GID referenceId, @NonNull OracleObject referent) {
        heap.strongReference(referenceId, this, referent);
    }

    public void softReference(@NonNull GID referenceId, @NonNull OracleObject referent) {
        heap.softReference(referenceId, this, referent);
    }

    public void weakReference(@NonNull GID referenceId, @NonNull OracleObject referent) {
        heap.weakReference(referenceId, this, referent);
    }

    /* package */ void reference(@NonNull GID referenceId, @NonNull OracleObject referent,
            @NonNull GReference.Type type) {
        heap.reference(referenceId, this, referent, type);
    }

    public void unlink(@NonNull OracleObject referent) {
        heap.unlink(this, referent);
    }

    public void free() {
        heap.freeObject(this);
    }
}
