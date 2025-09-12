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

package io.connglli.gorrect.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class OracleReference {

  @NonNull private final OracleHeap heap;

  @NonNull private final GReference reference;

  public GID getId() {
    return reference.getId();
  }

  public GReference.Type getType() {
    return reference.getType();
  }

  /* package */ void setType(@NonNull GReference.Type type) {
    reference.setType(type);
  }

  public boolean isVolatile() {
    return reference.isVolatile();
  }

  public OracleObject getReferer() {
    return heap.getObject(reference.getReferer());
  }

  public OracleObject getReferent() {
    return heap.getObject(reference.getReferent());
  }

  public void strengthen() {
    heap.strengthen(this);
  }

  public void soften() {
    heap.soften(this);
  }

  public void unlink() {
    heap.unlink(getReferer(), getReferent());
  }

  public void redirect(@NonNull OracleObject newReferent) {
    heap.redirect(this, newReferent);
  }
}
