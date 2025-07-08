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

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import lombok.NonNull;

/**
 * GeneratedObjectBaseClass is the base class for all object's corresponding classes. It contains a
 * final field for the object ID which is used to identify a specific object and a padding byte
 * array to control the size of the object. This class is extended by all objects' generation
 * classes so that we're able to substitute the reference of one object in its own class into
 * another object in its own class.
 */
final class GeneratedObjectBaseClass extends GeneratedClass {
  static final ClassName clazzName = ClassName.get("", "GGeneratedBase");
  static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8; // 1MB

  private static final FieldSpec objectId =
      FieldSpec.builder(String.class, "id", Modifier.PUBLIC, Modifier.FINAL).build();
  private static final FieldSpec objectSize =
      FieldSpec.builder(byte[][].class, "padding", Modifier.PUBLIC, Modifier.FINAL).build();
  private static final MethodSpec constructor =
      MethodSpec.constructorBuilder()
          .addParameter(String.class, "id")
          .addParameter(long.class, "size")
          .addCode(
              """
                  this.id = id;
                  if (size <= 0) {
                      throw new IllegalArgumentException("Object size must be a positive value, got " + size);
                  }
                  int numChunks = (int) ((size + $L - 1) / $L);
                  this.padding = new byte[numChunks][];
                  for (int i = 0; i < numChunks; i++) {
                      int chunkSize = (int) Math.min(size, $L);
                      this.padding[i] = new byte[chunkSize];
                      size -= chunkSize;
                  }
              """,
              MAX_ARRAY_SIZE,
              MAX_ARRAY_SIZE,
              MAX_ARRAY_SIZE)
          .build();

  GeneratedObjectBaseClass(@NonNull GeneratedHeap heap) {
    super(heap, clazzName.canonicalName(), Modifier.ABSTRACT);
    addField(objectId).addField(objectSize).addMethod(constructor);
  }
}
