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

import io.connglli.gorrect.gir.GProgramVisitor;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GMakeRootInstruction makes a non-root object one of a root object. This instruction requires the
 * object to be reachable and is not a root object before applying this instruction. Otherwise, an
 * exception should be thrown.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("make-root")
public class GMakeRootInstruction extends GInstruction {
  /** The ID of the non-root object that are about to make root. */
  @GIRInput(0)
  @GIRReachable
  @NonNull
  GID objectId;

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public void checkValidity(GGraph graph) {
    super.checkValidity(graph);
    if (graph.getObject(objectId).isRoot()) {
      throw new InvalidInputException("The object " + objectId + " is already a root object.");
    }
  }
}
