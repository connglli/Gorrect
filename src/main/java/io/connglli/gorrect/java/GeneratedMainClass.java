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
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.connglli.gorrect.gir.model.GID;
import javax.lang.model.element.Modifier;
import lombok.NonNull;

/**
 * GeneratedMainClass is the main class that contains the main method for the generation Java code.
 */
final class GeneratedMainClass extends GeneratedClass {
  static final ClassName clazzName = ClassName.get("", "GGeneratedMain");

  // The main method of the main class. It is used to initialize the heap state and execute the
  // heap manipulation sequence and finally waits for heap dumping.
  private static final MethodSpec.Builder mainMethodSpec =
      MethodSpec.methodBuilder("main")
          .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
          .returns(void.class)
          .addParameter(String[].class, "args");

  GeneratedMainClass(GeneratedHeap heap) {
    super(heap, clazzName.canonicalName(), Modifier.PUBLIC, Modifier.FINAL);
  }

  void addStatement(@NonNull String stmtFormat, Object... stmtArgs) {
    mainMethodSpec.addStatement(stmtFormat, stmtArgs);
  }

  @Override
  TypeSpec build() {
    addMethod(mainMethodSpec.build());
    return super.build();
  }

  static String computeFieldName(@NonNull GID gid) {
    return "root_ref_to_" + gid;
  }
}
