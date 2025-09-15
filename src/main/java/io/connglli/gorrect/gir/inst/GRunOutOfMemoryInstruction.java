/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
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

import io.connglli.gorrect.gir.GProgramVisitor;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GOutOfMemoryInstruction represents an instruction to stress test memory by making the language
 * virtual machine throw an out of memory error. The requirement for this instruction is that:
 * strongly reachable objects before executing us remain strongly-reachable after execution.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("run-out-of-memory")
public class GRunOutOfMemoryInstruction extends GInstruction {

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
