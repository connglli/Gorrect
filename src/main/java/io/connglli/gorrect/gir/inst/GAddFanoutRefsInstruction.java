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
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import java.util.List;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GAddFanoutRefsInstruction enables a referer to reference a list of different referents. The
 * instruction requires that the referer and all referents are reachable, and no existing references
 * are among them.. Otherwise, some reference cannot be added and an exception should be thereby
 * thrown.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("add-fanout-refs")
public class GAddFanoutRefsInstruction extends GInstruction {
  /** The source object of the new references */
  @GIRInput(0)
  @GIRReachable
  @NonNull
  GID refererId;

  /** The target objects being referenced */
  @GIRInput(1)
  @GIRReachable
  @NonNull
  List<GID> referentIds;

  /** The target objects being referenced by which strength */
  @GIRInput(2)
  @NonNull
  List<GReference.Type> referenceTypes;

  /** Whether the new references are volatile */
  @GIRInput(3)
  @NonNull
  List<Boolean> isVolatile;

  /** The newly created references */
  @GIROutput(0)
  @GIRNotExist(GIRNotExist.Type.REFERENCE)
  @NonNull
  List<GID> referenceIds;

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
