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

package io.connglli.gorrect.java.oracle;

import io.connglli.gorrect.utility.Assert;
import java.util.HashMap;
import java.util.Map;
import org.openjdk.jol.datamodel.DataModel;
import org.openjdk.jol.datamodel.Model32;
import org.openjdk.jol.datamodel.Model64;
import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.layouters.HotSpotLayouter;

/**
 * HotSpotObjects provides methods to estimate the shallow sizes of objects in a HotSpot JVM
 * environment. It uses the Java Object Layout (JOL) library to determine the sizes of various data
 * structures, including primitive types, object headers, and arrays.
 */
public class HotSpotObjects {

  // We cache the shallow sizes of classes to reduce the cost of parsing.
  private static final Map<Class<?>, Long> cachedShallowSizes = new HashMap<>();

  // We utilize JOL to avoid calculating the sizes manually.
  private final DataModel model;
  private final HotSpotLayouter layout;

  public HotSpotObjects(
      int version,
      boolean mode64,
      boolean enableCompressedOops,
      boolean enableCompressedClassPointers,
      int alignment) {
    this(
        version,
        mode64
            ? new Model64(enableCompressedOops, enableCompressedClassPointers, alignment)
            : new Model32(alignment));
  }

  /*package*/ HotSpotObjects(int version, DataModel model) {
    this.model = model;
    this.layout = new HotSpotLayouter(this.model, version);
  }

  public long getMarkWordSize() {
    return model.markHeaderSize();
  }

  public long getClassPointerSize() {
    return model.classHeaderSize();
  }

  public long getArrayLengthSize() {
    return model.arrayLengthHeaderSize();
  }

  public long getObjectOopHeaderSize() {
    return model.headerSize();
  }

  public long getArrayOopHeaderSize() {
    return model.arrayHeaderSize();
  }

  public long getObjectAlignment() {
    return model.objectAlignment();
  }

  public long getPrimitiveSize(Class<?> primitive) {
    Assert.set(primitive.isPrimitive(), "Must be a primitive type, got " + primitive);
    return model.sizeOf(primitive.getCanonicalName());
  }

  public long getReferenceSize() {
    return model.sizeOf("reference");
  }

  /// Compute the shallow size of an object of a class.
  public long computeShallowSize(String className) {
    try {
      Class<?> clazz = Class.forName(className);
      return computeShallowSize(clazz);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Class not found: " + className, e);
    }
  }

  /// Compute the shallow size of an object of a class.
  public long computeShallowSize(Class<?> clazz) {
    if (clazz.isPrimitive()) {
      return getPrimitiveSize(clazz);
    }
    if (!cachedShallowSizes.containsKey(clazz)) {
      synchronized (cachedShallowSizes) {
        if (!cachedShallowSizes.containsKey(clazz)) {
          long size = layout.layout(ClassData.parseClass(clazz)).instanceSize();
          cachedShallowSizes.put(clazz, size);
        }
      }
    }
    return cachedShallowSizes.get(clazz);
  }

  /// Compute the shallow size of an object.
  public long computeShallowSize(Object object) {
    return layout.layout(ClassData.parseInstance(object)).instanceSize();
  }

  /// Align the size to the object alignment.
  public long align(long size) {
    long am = model.objectAlignment() - 1;
    return (size + am) & ~am;
  }
}
