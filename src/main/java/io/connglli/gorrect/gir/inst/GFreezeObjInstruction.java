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
import io.connglli.gorrect.gir.inst.marks.GIRMarkNoGraphEffect;
import io.connglli.gorrect.gir.inst.marks.GIRMarkReinterpretable;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GFreezeObject freezes an object for N steps. This means the follow-up N instructions cannot
 * remove the object explicitly by GRemoveObjInstruction so that to try promoting the object into an
 * elder generation for generation-based heap. Note that, an implicit remove (for example removed
 * due to unreachable because of the removal of some other objects or some references) is allowed.
 * However, there's no guarantee, or it is the user's responsibility to ensure that after N
 * instructions, the object is indeed promoted.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("freeze-obj")
public class GFreezeObjInstruction extends GInstruction
    implements GIRMarkReinterpretable, GIRMarkNoGraphEffect {

  @GIRInput(0)
  @GIRReachable
  @NonNull
  GID objectId;

  @GIRInput(1)
  @NonNull
  Integer nStep;

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public void checkValidity(GGraph graph) {
    if (nStep <= 0) {
      throw new IllegalStateException(
          "The nStep argument of GFreezeObjInstruction must be positive, but got " + nStep);
    }
    super.checkValidity(graph);
  }
}
