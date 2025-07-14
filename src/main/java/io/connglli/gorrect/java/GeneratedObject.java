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
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.Assert;
import java.util.List;
import lombok.*;

/**
 * GeneratedObject represents a generated object in the Java code generation. It contains methods to
 * manage the object's class, type, identifier, and references.
 */
@AllArgsConstructor
class GeneratedObject {
  @NonNull private final GeneratedHeap heap;

  @NonNull
  @Getter(AccessLevel.PACKAGE)
  private GeneratedObjectClass clazz;

  @NonNull
  @Getter(AccessLevel.PACKAGE)
  @Setter(AccessLevel.PACKAGE)
  private GObject.Type type;

  @NonNull
  @Getter(AccessLevel.PACKAGE)
  @Setter(AccessLevel.PACKAGE)
  private String javaRefId;

  // Null means unreachable
  @Getter(AccessLevel.PACKAGE)
  @Setter(AccessLevel.PACKAGE)
  private GReference.Type reachability;

  // Null means no referer (root or unreachable)
  @Getter(AccessLevel.PACKAGE)
  @Setter(AccessLevel.PACKAGE)
  private GReference.Type refToStrongestReferer;

  @NonNull
  GID getId() {
    return clazz.getId();
  }

  long getSize() {
    return clazz.getSize();
  }

  boolean isRoot() {
    return type == GObject.Type.GCROOT;
  }

  boolean isReachable() {
    return reachability != null;
  }

  void toRoot() {
    Assert.set(!isRoot(), "Object with id %s is already a root", getId());
    heap.makeRoot(this);
  }

  void toNonRoot() {
    Assert.set(isRoot(), "Object with id %s is already a non-root", getId());
    heap.makeNonRoot(this);
  }

  List<GeneratedObject> getReferers() {
    return heap.getReferers(this);
  }

  List<GeneratedObject> getReferents() {
    return heap.getReferents(this);
  }

  List<GeneratedReference> getReferences() {
    return heap.getReferences(this);
  }

  @SuppressWarnings("UnusedReturnValue")
  @NonNull
  GeneratedReference strongReference(@NonNull GID referenceId, @NonNull GeneratedObject referent) {
    return heap.reference(referenceId, this, referent, GReference.Type.STRONG);
  }

  @SuppressWarnings("UnusedReturnValue")
  @NonNull
  GeneratedReference softReference(@NonNull GID referenceId, @NonNull GeneratedObject referent) {
    return heap.reference(referenceId, this, referent, GReference.Type.SOFT);
  }

  @SuppressWarnings("UnusedReturnValue")
  @NonNull
  GeneratedReference weakReference(@NonNull GID referenceId, @NonNull GeneratedObject referent) {
    return heap.reference(referenceId, this, referent, GReference.Type.WEAK);
  }

  @SuppressWarnings("UnusedReturnValue")
  @NonNull
  GeneratedReference reference(
      @NonNull GID referenceId,
      @NonNull GeneratedObject referent,
      @NonNull GReference.Type referenceType) {
    return heap.reference(referenceId, this, referent, referenceType);
  }

  void free() {
    heap.freeObject(this);
  }

  String getJavaDerefId() {
    return switch (refToStrongestReferer) {
      case null -> "((%s)(%s))".formatted(clazz.getClazzName(), javaRefId);
      case STRONG -> "((%s)(%s))".formatted(clazz.getClazzName(), javaRefId);
      case SOFT, WEAK -> "((%s)(%s.get()))".formatted(clazz.getClazzName(), javaRefId);
      default -> {
        Assert.panic("Cannot reach here: %s", reachability);
        yield "";
      }
    };
  }

  static String computeFieldName(@NonNull GReference.Type type, @NonNull GID referentId) {
    return switch (type) {
      case STRONG -> "strong_ref_to_" + referentId;
      case SOFT -> "soft_ref_to_" + referentId;
      case WEAK -> "weak_ref_to_" + referentId;
      default -> {
        Assert.panic("Cannot reach here");
        yield "";
      }
    };
  }
}
