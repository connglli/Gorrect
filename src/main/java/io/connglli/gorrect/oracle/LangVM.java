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

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import lombok.Getter;
import lombok.NonNull;

/**
 * LangVM is a helper class to help {@link OracleInterpreter} and {@link OracleHeap} to ensure that
 * the {@link OracleInterpreter}'s and {@link OracleHeap}'s behavior aligns with the actual language
 * virtual machine's behavior, for example whether to allocate an object or not. Without the helper
 * class, we'll get a false object graph after interpreting a {@link GIRProgram}.
 *
 * Note, the helper class should directly complain an error, i.e., throw a specific exception,
 * whenever and whatever unexpected events happen.
 */
public abstract class LangVM {

    public static class OutOfMemError extends RuntimeException {
        public OutOfMemError(String message) {
            super(message);
        }
    }

    // The total size of the heap in bytes
    @Getter
    protected final long heapCapacity;

    // The number of bytes used by all objects in the heap
    @Getter
    protected long heapUnusedBytes;

    public LangVM(long heapCapacity) {
        this.heapCapacity = heapCapacity;
        this.heapUnusedBytes = heapCapacity;
    }

    // -================================================
    // - Heap Helper Methods
    // -================================================

    public abstract long estimateNewObjectSize(long requestedSize);

    public abstract long estimateObjectSize(OracleObject object);

    public abstract void onOracleAllocate(@NonNull GID objectId, long size, GObject.Type type);

    public abstract void onOracleFree(@NonNull OracleObject object);

    public abstract void onOracleReference(@NonNull GID referenceId, @NonNull OracleObject referer,
            @NonNull OracleObject referent, GReference.Type type);

    public abstract void onOracleUnlink(@NonNull OracleObject referer,
            @NonNull OracleObject referent);

    public abstract void onOracleClean();

    public abstract void onOracleCompact();

    public abstract void onOracleStressCompact();
}
