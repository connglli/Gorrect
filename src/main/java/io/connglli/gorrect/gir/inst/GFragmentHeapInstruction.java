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
import io.connglli.gorrect.gir.inst.marks.GIRMarkUserEnsuredEffect;
import io.connglli.gorrect.gir.model.GGraph;
import java.util.List;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * GFragmentHeapInstruction allocates a number of large objects as GC roots, trying to make the heap
 * fragment. However, there's no guarantee that the heap will actually fragment. The user should try
 * best to provide object sizes that are likely to fragment the heap. The created objects are all
 * anonymous roots and will perhaps stay in the heap until the end of the program.
 */
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@EqualsAndHashCode(callSuper = true)
@GIROpCode("fragment-heap")
public class GFragmentHeapInstruction extends GInstruction implements GIRMarkUserEnsuredEffect {

  /** The number of objects to be allocated. */
  @GIRInput(0)
  @NonNull
  Integer count;

  /** The size of the objects to be allocated, in bytes. The size of this list should be count. */
  @GIRInput(1)
  @NonNull
  List<Long> objectSizes;

  @Override
  public <T> T accept(GProgramVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public void checkValidity(GGraph graph) {
    if (count <= 0) {
      throw new InvalidInputException("The count must be positive: " + this);
    }
    if (objectSizes.size() != count) {
      throw new InvalidInputException("The size of objectSizes must be equal to count: " + this);
    }
    for (Long s : objectSizes) {
      if (s <= 0) {
        throw new InvalidInputException("The object size must be positive: " + this);
      }
    }
    super.checkValidity(graph);
  }
}
