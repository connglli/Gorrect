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

package io.connglli.gorrect.gir.inst;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * GAddNonRootObjInstruction adds a new object to the graph as one of a non-root object. To make the
 * object reachable, this operation requires a referer object to reference it.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("add-non-root")
public class GAddNonRootObjInstruction extends GInstruction {
    /** The size in bytes of the new object to be created. */
    @GIRInput(0)
    @NonNull
    Long objectSize;

    /** The ID of the existing object that will reference the new object. */
    @GIRInput(1)
    @NonNull
    GID refererId;

    /** The type of reference to create between the referer and the new object. */
    @GIRInput(2)
    @NonNull
    GReference.Type referenceType;

    /** The unique ID assigned to the newly created object. */
    @GIROutput(0)
    @NonNull
    GID objectId;

    /** The unique ID assigned to the newly created reference. */
    @GIROutput(1)
    @NonNull
    GID referenceId;

    @Override
    public <T> T accept(GInstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
