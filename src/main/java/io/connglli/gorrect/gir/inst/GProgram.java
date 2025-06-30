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

import java.util.ArrayList;
import java.util.List;

import io.connglli.gorrect.utility.GAssert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents a complete GIR program containing instructions and metadata.
 */
@Getter
@RequiredArgsConstructor
public class GProgram extends GInstruction {
    private final GSetHeapInstruction configuration;
    private final List<GInstruction> instructions = new ArrayList<>();

    public void addInstruction(GInstruction instruction) {
        GAssert.set(!(instruction instanceof GSetHeapInstruction),
                "GSetHeapInstruction is already aded");
        instructions.add(instruction);
    }

    public int size() {
        return instructions.size();
    }

    public boolean isEmpty() {
        return instructions.isEmpty();
    }

    @Override
    public <T> T accept(GInstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
