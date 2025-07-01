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

import java.util.HashMap;
import java.util.Map;

import javax.lang.model.element.Modifier;

import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import io.connglli.gorrect.utility.GAssert;
import lombok.Getter;
import lombok.NonNull;

/**
 * GeneratedClass is the base class for all generation classes in the Java code generation. It
 * provides methods to manage fields and methods, and to build the class specification.
 */
@SuppressWarnings("UnusedReturnValue")
abstract class GeneratedClass {
    @Getter
    @NonNull
    private final GeneratedHeap heap;

    @Getter
    @NonNull
    private final String clazzName;

    @NonNull
    private final TypeSpec.Builder clazzSpec;

    // We need these maps as TypeSpec.Builder does not allow us to query them
    private final Map<String, FieldSpec> fieldSpecs = new HashMap<>();
    private final Map<String, MethodSpec> methodSpecs = new HashMap<>();

    GeneratedClass(@NonNull GeneratedHeap heap, @NonNull String clazzName, TypeName superClazz,
            Modifier... modifiers) {
        this.heap = heap;
        this.clazzName = clazzName;
        this.clazzSpec = TypeSpec.classBuilder(clazzName).addModifiers(modifiers);
        if (superClazz != null) {
            this.clazzSpec.superclass(superClazz);
        }
    }

    GeneratedClass(@NonNull GeneratedHeap heap, @NonNull String clazzName, Modifier... modifiers) {
        this(heap, clazzName, null, modifiers);
    }

    boolean hasField(@NonNull String fieldName) {
        return fieldSpecs.containsKey(fieldName);
    }

    boolean hasMethod(@NonNull String methodName) {
        return methodSpecs.containsKey(methodName);
    }

    FieldSpec getField(@NonNull String fieldName) {
        GAssert.set(hasField(fieldName), "Field %s does not exist in class %s", fieldName,
                clazzName);
        return fieldSpecs.get(fieldName);
    }

    MethodSpec getMethod(@NonNull String methodName) {
        GAssert.set(hasMethod(methodName), "Method %s does not exist in class %s", methodName,
                clazzName);
        return methodSpecs.get(methodName);
    }

    GeneratedClass addField(@NonNull FieldSpec field) {
        GAssert.set(!hasField(field.name()), "Field %s already exists in class %s", field.name(),
                clazzName);
        fieldSpecs.put(field.name(), field);
        return this;
    }

    GeneratedClass addMethod(@NonNull MethodSpec method) {
        GAssert.set(!hasMethod(method.name()), "Field %s already exists in class %s", method.name(),
                clazzName);
        methodSpecs.put(method.name(), method);
        return this;
    }

    TypeSpec build() {
        return clazzSpec.addFields(fieldSpecs.values()).addMethods(methodSpecs.values()).build();
    }

    String toJavaCode() {
        return build().toString();
    }
}
