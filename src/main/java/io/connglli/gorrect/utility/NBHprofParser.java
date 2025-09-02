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

package io.connglli.gorrect.utility;

import java.lang.reflect.Array;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.PrimitiveArrayInstance;

public final class NBHprofParser {
  public static Object getFieldValue(Instance instance, String fieldName, boolean isStatic) {
    if (!isStatic) {
      return instance.getValueOfField(fieldName);
    } else {
      return instance.getJavaClass().getValueOfStaticField(fieldName);
    }
  }

  public static Object getFieldValue(JavaClass clazz, String fieldName) {
    return clazz.getValueOfStaticField(fieldName);
  }

  private static final Set<Class<?>> BOXED_PRIMITIVE_TYPES =
      Set.of(
          Boolean.class,
          Byte.class,
          Character.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class);

  public static <T> T parsePrimitiveField(
      JavaClass clazz, String fieldName, Class<T> expectedType) {
    Assert.set(
        BOXED_PRIMITIVE_TYPES.contains(expectedType),
        "Expected a boxed primitive type, but got " + expectedType);
    // Primitive fields are their boxed primitive type by NetBeans Profiler.
    return parsePrimitiveFieldValue(getFieldValue(clazz, fieldName), expectedType);
  }

  public static <T> T parsePrimitiveField(
      Instance instance, String fieldName, Class<T> expectedType, boolean isStatic) {
    Assert.set(
        BOXED_PRIMITIVE_TYPES.contains(expectedType),
        "Expected a boxed primitive type, but got " + expectedType);
    // Primitive fields are their boxed primitive type by NetBeans Profiler.
    return parsePrimitiveFieldValue(getFieldValue(instance, fieldName, isStatic), expectedType);
  }

  public static <T> T parsePrimitiveFieldValue(Object o, Class<T> expectedType) {
    if (o == null) {
      return null;
    }
    Assert.set(
        expectedType.isInstance(o),
        "Expected %s for an integer field, but got %s",
        expectedType.getCanonicalName(),
        o.getClass());
    return expectedType.cast(o);
  }

  public static <T> T[] parsePrimitiveArrayField(
      JavaClass clazz, String fieldName, Class<T> elementType) {
    Assert.set(
        BOXED_PRIMITIVE_TYPES.contains(elementType),
        "Expected the element type to be a boxed primitive type, but got " + elementType);
    return parsePrimitiveArrayFieldValue(getFieldValue(clazz, fieldName), elementType);
  }

  public static <T> T[] parsePrimitiveArrayField(
      Instance instance, String fieldName, Class<T> elementType, boolean isStatic) {
    Assert.set(
        BOXED_PRIMITIVE_TYPES.contains(elementType),
        "Expected the element type to be a boxed primitive type, but got " + elementType);
    return parsePrimitiveArrayFieldValue(getFieldValue(instance, fieldName, isStatic), elementType);
  }

  @SuppressWarnings("unchecked")
  private static <T> T[] parsePrimitiveArrayFieldValue(Object o, Class<T> elementType) {
    if (o == null) {
      return null;
    }
    Assert.set(
        o instanceof PrimitiveArrayInstance,
        "Expected PrimitiveArrayInstance for a primitive array field, but got " + o.getClass());
    PrimitiveArrayInstance arrayInstance = (PrimitiveArrayInstance) o;
    T[] resultArray = (T[]) Array.newInstance(elementType, arrayInstance.getValues().size());
    for (int i = 0; i < arrayInstance.getValues().size(); i++) {
      Object element = arrayInstance.getValues().get(i);
      Assert.set(
          element instanceof String,
          "Expected String for each value of a PrimitiveArrayInstance, but got "
              + element.getClass());
      if (elementType == Byte.class) {
        resultArray[i] = elementType.cast(Byte.parseByte((String) element));
      } else if (elementType == Short.class) {
        resultArray[i] = elementType.cast(Short.parseShort((String) element));
      } else if (elementType == Integer.class) {
        resultArray[i] = elementType.cast(Integer.parseInt((String) element));
      } else if (elementType == Long.class) {
        resultArray[i] = elementType.cast(Long.parseLong((String) element));
      } else if (elementType == Float.class) {
        resultArray[i] = elementType.cast(Float.parseFloat((String) element));
      } else if (elementType == Double.class) {
        resultArray[i] = elementType.cast(Double.parseDouble((String) element));
      } else if (elementType == Boolean.class) {
        resultArray[i] = elementType.cast(Boolean.parseBoolean((String) element));
      } else if (elementType == Character.class) {
        Assert.set(
            ((String) element).length() == 1,
            "Expected single character for Character type, but got " + element);
        resultArray[i] = elementType.cast(((String) element).charAt(0));
      } else {
        throw new IllegalArgumentException("Unsupported element type: " + elementType);
      }
    }
    return resultArray;
  }

  public static String parseStringField(JavaClass clazz, String fieldName) {
    // String instances are all Instances according to NetBeans' HPROF parser.
    return parseStringFieldValue(getFieldValue(clazz, fieldName));
  }

  public static String parseStringField(Instance instance, String fieldName, boolean isStatic) {
    // String instances are all Instances according to NetBeans' HPROF parser.
    return parseStringFieldValue(getFieldValue(instance, fieldName, isStatic));
  }

  private static String parseStringFieldValue(Object o) {
    if (o == null) {
      return null;
    }
    Assert.set(
        o instanceof Instance, "Expected Instance for a string field, but got " + o.getClass());
    Instance stringInstance = (Instance) o;
    Assert.set(
        stringInstance.getJavaClass().getName().equals(String.class.getCanonicalName()),
        "Expected JavaClass for a string instance to be String, but got %s",
        stringInstance.getJavaClass().getName());
    // We reocver the string from its byte array representation.
    Byte[] byteArray = parsePrimitiveArrayField(stringInstance, "value", Byte.class, false);
    Assert.set(byteArray != null, "Expected byte array for a string field not null, but got null");
    StringBuilder sb = new StringBuilder();
    for (Byte b : byteArray) {
      sb.append(Character.toString(b));
    }
    return sb.toString();
  }
}
