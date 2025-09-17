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
import io.connglli.gorrect.gir.inst.marks.GIRMarkReenterable;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GSwapReferentsInstruction swaps the referents of two references. This instruction requires the
 * two reference are of the same type, of different referers, and different referents. Otherwise, an
 * exception should be thrown.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("swap-referents")
public class GSwapReferentsInstruction extends GInstruction implements GIRMarkReenterable {
  /** The ID of the first reference involved in the swap operation. */
  @GIRInput(0)
  @GIRNotExist(value = GIRNotExist.Type.REFERENCE, reverse = true)
  @NonNull
  GID referenceId1;

  /** The ID of the second reference involved in the swap operation. */
  @GIRInput(1)
  @GIRNotExist(value = GIRNotExist.Type.REFERENCE, reverse = true)
  @NonNull
  GID referenceId2;

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public void checkValidity(GGraph graph) {
    super.checkValidity(graph);
    GReference ref1 = graph.getReference(referenceId1);
    GReference ref2 = graph.getReference(referenceId2);
    if (ref1.getType() != ref2.getType()) {
      throw new InvalidInputException(
          String.format(
              "Cannot swap referents of different reference types: %s and %s",
              ref1.getType(), ref2.getType()));
    }
    if (ref1.getReferer().equals(ref2.getReferer())) {
      throw new InvalidInputException(
          String.format(
              "Reference %s and reference %s have the same referer %s",
              ref1.getId(), ref2.getId(), ref1.getReferer()));
    }
    if (ref1.getReferent().equals(ref2.getReferent())) {
      throw new InvalidInputException(
          String.format(
              "Reference %s and reference %s have the same referent %s",
              ref1.getId(), ref2.getId(), ref1.getReferent()));
    }
  }
}
