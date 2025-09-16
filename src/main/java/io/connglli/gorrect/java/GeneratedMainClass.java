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

import com.palantir.javapoet.*;
import io.connglli.gorrect.gir.model.GID;
import java.util.UUID;
import javax.lang.model.element.Modifier;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * GeneratedMainClass is the main class that contains the main method for the generation Java code.
 */
final class GeneratedMainClass extends GeneratedClass {
  static final ClassName clazzName = ClassName.get("", "Main");
  static final String UUID_FIELD_NAME = "uuid";
  static final String ROOT_REF_FIELD_NAME_PREFIX = "root_ref_to_";

  // Add an UUID to each case so that we can distinguish them easily.
  @Getter private final String uuid = UUID.randomUUID().toString();
  // The main method of the main class. It is used to initialize the heap state and execute the
  // heap manipulation sequence and finally waits for heap dumping.
  private final MethodSpec.Builder mainMethodSpec =
      MethodSpec.methodBuilder("main")
          .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
          .returns(void.class)
          .addParameter(String[].class, "args")
          .addException(Exception.class);
  // The code generator for the main method.
  @Getter private final CodeGenerator codeGen = new CodeGenerator(mainMethodSpec);

  GeneratedMainClass(GeneratedHeap heap) {
    super(heap, clazzName.canonicalName(), Modifier.PUBLIC, Modifier.FINAL);
    addField(
        FieldSpec.builder(
                ClassName.get(String.class), UUID_FIELD_NAME, Modifier.PUBLIC, Modifier.STATIC)
            .initializer("$S", uuid)
            .build());
  }

  @Override
  TypeSpec build() {
    addMethod(mainMethodSpec.build());
    return super.build();
  }

  static String computeFieldName(@NonNull GID gid) {
    return ROOT_REF_FIELD_NAME_PREFIX + gid;
  }

  /// A helper class to generate code for the main method.
  /// It exists to avoid exposing the MethodSpec.Builder directly.
  @SuppressWarnings("UnusedReturnValue")
  @RequiredArgsConstructor
  static class CodeGenerator {

    @NonNull MethodSpec.Builder method;

    public MethodSpec.Builder addStatement(@NonNull String format, Object... args) {
      return method.addStatement(format, args);
    }

    public MethodSpec.Builder addStatement(@NonNull CodeBlock codeBlock) {
      return method.addStatement(codeBlock);
    }

    public MethodSpec.Builder addComment(@NonNull String format, Object... args) {
      return method.addStatement("/* " + format + " */", args);
    }

    public MethodSpec.Builder addCode(@NonNull String format, Object... args) {
      return method.addCode(format, args);
    }

    public MethodSpec.Builder addCode(CodeBlock codeBlock) {
      return method.addCode(codeBlock);
    }

    public MethodSpec.Builder beginControlFlow(@NonNull String controlFlow, Object... args) {
      return method.beginControlFlow(controlFlow, args);
    }

    public MethodSpec.Builder beginControlFlow(@NonNull CodeBlock codeBlock) {
      return method.beginControlFlow(codeBlock);
    }

    public MethodSpec.Builder nextControlFlow(@NonNull String controlFlow, Object... args) {
      return method.nextControlFlow(controlFlow, args);
    }

    public MethodSpec.Builder nextControlFlow(@NonNull CodeBlock codeBlock) {
      return method.nextControlFlow(codeBlock);
    }

    public MethodSpec.Builder endControlFlow(@NonNull String controlFlow, Object... args) {
      return method.endControlFlow(controlFlow, args);
    }

    public MethodSpec.Builder endControlFlow(@NonNull CodeBlock codeBlock) {
      return method.endControlFlow(codeBlock);
    }

    public MethodSpec.Builder endControlFlow() {
      return method.endControlFlow();
    }
  }
}
