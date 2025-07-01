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
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * CloneObjInstruction clones an object in the graph. This includes create a new object. Then it
 * connects it to all the referents of the cloned object, as well as connects all the referers of
 * the cloned object to the new object.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("clone-obj")
public class GCloneObjInstruction extends GInstruction {

    /** The object to be cloned. */
    @GIRInput(0)
    GID objectId;

    /** The cloned object. */
    @GIROutput(1)
    GID cloneId;

    @Override
    public <T> T accept(GInstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
