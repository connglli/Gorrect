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

package io.connglli.gorrect.java.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.OracleObject;
import lombok.NonNull;

public class JavaVM extends LangVM {
    public JavaVM(long heapCapacity) {
        super(heapCapacity);
    }

    @Override
    public void onOracleAllocate(@NonNull GID objectId, long size, GObject.Type type) {
        if (heapUnusedBytes < estimateNewObjectHeapSize(size)) {
            throw new OutOfMemError("Heap out of memory: " + heapUnusedBytes + " bytes left, "
                    + size + " bytes needed for object of type " + type.name());
        }
        heapUnusedBytes -= estimateNewObjectHeapSize(size);
    }

    public void onOracleFree(@NonNull OracleObject object) {
        heapUnusedBytes += estimateObjectHeapSize(object);
    }

    @Override
    public void onOracleClean() {}

    @Override
    public void onOracleCompact() {}

    @Override
    public void onOracleStressCompact() {}

    @Override
    public void onOracleReference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent, GReference.Type type) {}

    @Override
    public void onOracleUnlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {}

    @Override
    public long estimateNewObjectHeapSize(long requestedSize) {
        System.out.println("WARNING: OracleHeap.estimateNewObjectSize() is current NOT reliable");
        // TODO: Consider metadata, vtable, type, etc.
        // We know that class have a base class GGeneratedClassBase.
        return requestedSize;
    }

    @Override
    public long estimateObjectHeapSize(OracleObject object) {
        System.out.println("WARNING: OracleHeap.estimateObjectSize() is current NOT reliable");
        // TODO: Consider referents, etc.
        // We know that each reference <u,v,s> has a field in
        // GGeneratedClassU.s_ref_to_v and the class has a super class called
        // GGeneratedClassBase. No other super classes, no other subclasses, etc.
        return object.getSize();
    }
}
