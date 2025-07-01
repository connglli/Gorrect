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

import java.util.List;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * GAddFanoutRefsInstruction enables a referer to reference a list of different referents.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("add-fanin-refs")
public class GAddFanoutRefsInstruction extends GInstruction {
    /** The source object of the new references */
    @GIRInput(0)
    @NonNull
    GID refererId;

    /** The target objects being referenced */
    @GIRInput(1)
    @NonNull
    List<GID> referentIds;

    /** The target objects being referenced by which strength */
    List<GReference.Type> referenceTypes;

    /** The newly created references */
    @GIROutput(0)
    @NonNull
    List<GID> referenceIds;

    @Override
    public <T> T accept(GInstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
