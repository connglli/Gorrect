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

package io.connglli.gorrect.utility;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class Reflections {

  /// Get a method by name and parameter types from the given class or its superclasses.
  public static Method getMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredMethod(name, parameterTypes);
      } catch (NoSuchMethodException e) {
        // Method not found in this class, continue to superclass
      }
      clazz = clazz.getSuperclass();
    }
    return null;
  }

  /// Get a field by name from the given class or its superclasses.
  public static Field getField(Class<?> clazz, String name) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        // Method not found in this class, continue to superclass
      }
      clazz = clazz.getSuperclass();
    }
    return null;
  }

  /// Returns a list of fields in the given class and its superclasses that are annotated with the
  /// specified annotation. The fields are made accessible if they are private.
  public static Field[] getAnnotatedFields(Object object, Class<? extends Annotation> annotation) {
    Class<?> clazz = object.getClass();
    List<Field> fields = new ArrayList<>();
    while (clazz != null) {
      for (Field field : clazz.getDeclaredFields()) {
        if (field.isAnnotationPresent(annotation)) {
          fields.add(field);
        }
      }
      clazz = clazz.getSuperclass();
    }
    return fields.toArray(new Field[0]);
  }
}
