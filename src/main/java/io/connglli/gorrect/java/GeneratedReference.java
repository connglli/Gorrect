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

package io.connglli.gorrect.java;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * GeneratedReference represents a reference in the generated Java code. It contains methods to
 * manage the referer, referent, and the type of the reference.
 */
@RequiredArgsConstructor
class GeneratedReference {
  @NonNull private final GeneratedHeap heap;

  @Getter @NonNull GID id;

  @Getter @NonNull private String field;

  @NonNull GID refererId;

  @NonNull GID referentId;

  @Getter @Setter @NonNull GReference.Type type;

  @NonNull
  GeneratedObject getReferer() {
    return heap.getObject(refererId);
  }

  @NonNull
  GeneratedObject getReferent() {
    return heap.getObject(referentId);
  }

  void setReferent(@NonNull GeneratedObject referent) {
    this.referentId = referent.getId();
  }

  void strengthen() {
    setType(getType().strengthen());
  }

  void soften() {
    setType(getType().soften());
  }

  void unlink() {
    heap.unlink(this);
  }

  void redirect(@NonNull GeneratedObject newReferent) {
    heap.redirect(this, newReferent);
  }
}
