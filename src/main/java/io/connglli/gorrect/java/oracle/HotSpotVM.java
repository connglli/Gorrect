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

package io.connglli.gorrect.java.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.OracleObject;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Formats;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NonNull;

public class HotSpotVM extends JavaVM {
  /*
   * HotSpot Object Layout
   * ---------------------
   *
   * Each object in HotSpot is composed of an Ordinary Object Pointer (oop) header and all its fields.
   *
   * Oop Header: Oop header describes the meta information of the object ().
   * + a mark word: 4 or 8 bytes, depending on the VM mode
   *   - the mark word is a markWord: https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/oops/oop.hpp#L56
   *   - markWord is an uintptr_t, depending on the VM mode: https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/oops/markWord.hpp
   * + a class pointer: 4 or 8 bytes, depending on the VM mode and whether compressed class pointers are enabled
   *   - uncompressed class pointer is a Klass*, depends on VM mode: https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/oops/oop.hpp#L57
   *   - compressed class pointer is a narrowKlass, which is further a juint: https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/oops/oopsHierarchy.hpp#L41
   * + an array length: 0 or 4 bytes, depending on whether the the object is an array.
   *   - https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/oops/arrayOop.hpp#L81
   * + alignment paddings to ensure the object is aligned to 4 or 8 bytes, depending on the VM mode.
   *
   * Fields: Each field can be one of 9 types, including the reference type and the 9 primitive types (following JSL).
   * - Reference: 4 or 8 bytes, depending on whether compressed oops are enabled
   * - Boolean: 4 bytes
   * - Byte: 1 byte
   * - Char: 2 bytes
   * - Short: 2 bytes
   * - Int: 4 bytes
   * - Long: 8 bytes
   * - Float: 4 bytes
   * - Double: 8 bytes
   *
   * More:
   * - The Structure of the Java Virtual Machine (https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html)
   * - Java Object Layout for HotSpot: https://github.com/openjdk/jol
   */

  @Getter private final boolean isMode64;
  @Getter private final boolean enableCompressedOops;
  @Getter private final boolean enableCompressedClassPointers;

  // We trace the allocated objects so that we can estimate the heap size
  private final Map<GID, Long> allocatedObjects = new LinkedHashMap<>();

  public HotSpotVM(long heapCapacity) {
    this(heapCapacity, true, true, true);
  }

  public HotSpotVM(
      long heapCapacity,
      boolean isMode64,
      boolean enableCompressedOops,
      boolean enableCompressedClassPointers) {
    super(heapCapacity);
    this.isMode64 = isMode64;
    this.enableCompressedOops = enableCompressedOops;
    this.enableCompressedClassPointers = enableCompressedClassPointers;
  }

  public int getReferenceSize() {
    return enableCompressedOops ? 4 : isMode64 ? 8 : 4;
  }

  public int getOopHeaderSize(boolean isArray) {
    return (isMode64 ? 8 : 4) // mark word
        + (enableCompressedClassPointers ? 4 : (isMode64 ? 8 : 4)) // class pointer
        + (isArray ? 4 : 0) // array length
    ;
  }

  @Override
  public void onOracleAllocate(@NonNull GID objectId, long size, GObject.Type type) {
    long estimatedSize = estimateNewObjectHeapSize(objectId, size);
    if (heapUnusedBytes < estimatedSize) {
      throw new OutOfMemError(
          "Java heap out of memory: "
              + Formats.toCommaString(heapUnusedBytes)
              + " bytes left, "
              + Formats.toCommaString(size)
              + " bytes needed for object of type "
              + type.name());
    }
    heapUnusedBytes -= estimatedSize;
    allocatedObjects.put(objectId, estimatedSize);
  }

  public void onOracleFree(@NonNull OracleObject object) {
    GID objectId = object.getId();
    Assert.set(
        allocatedObjects.containsKey(objectId),
        "Object with ID " + objectId + " is not allocated in the heap");
    // Add new references may cause the memory not aligned, so we align the size
    long size = align(allocatedObjects.get(objectId));
    allocatedObjects.put(objectId, size);
    heapUnusedBytes += size;
  }

  @Override
  public void onOracleClean() {}

  @Override
  public void onOracleCompact() {}

  @Override
  public void onOracleStressCompact() {}

  @Override
  public void onOracleReference(
      @NonNull GID referenceId,
      @NonNull OracleObject referer,
      @NonNull OracleObject referent,
      GReference.Type type) {
    long refSize =
        getReferenceSize() // For the reference field
            + computeReferenceObjectSize( // For the Reference object in heap
                type == GReference.Type.STRONG
                    ? null
                    : type == GReference.Type.SOFT ? SoftReference.class : WeakReference.class);
    GID refererId = referer.getId();
    Assert.set(
        allocatedObjects.containsKey(refererId),
        "Referer object with ID " + refererId + " is not allocated in the heap");
    if (heapUnusedBytes < refSize) {
      throw new OutOfMemError(
          "Java heap out of memory: "
              + Formats.toCommaString(heapUnusedBytes)
              + " bytes left, "
              + Formats.toCommaString(refSize)
              + " bytes needed for object of type "
              + type.name());
    }
    heapUnusedBytes -= refSize;
    allocatedObjects.put(refererId, allocatedObjects.get(refererId) + refSize);
  }

  @Override
  public void onOracleUnlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {}

  @Override
  public long estimateNewObjectHeapSize(@NonNull GID objectId, long requestedSize) {
    // TODO: Load an object using URLClassLoader and estimate the size by JOL to avoid hardcoding
    // NOTE: This implementation is closely BOUND to the version of HotSpot, the String class, and
    // our generated object. We know that each our generated object has:
    // - a String id,
    // - a long size, and
    // - a byte[][] padding.
    Assert.set(requestedSize >= 0, "Requested size must be non-negative, but got " + requestedSize);
    return align(
        getOopHeaderSize(false) // For the oop header
            + getReferenceSize() // For the id reference
            + computeStringObjectSize(objectId.get()) // For the id value (a String object in heap)
            + getLongSize() // For the size field and its value (a long object in heap)
            + getReferenceSize() // For the padding reference
            + requestedSize * getByteSize() // For the padding value (a byte[] array in heap)
        );
  }

  @Override
  public long estimateObjectHeapSize(@NonNull OracleObject object) {
    long initSize = estimateNewObjectHeapSize(object.getId(), object.getSize());
    long strongRefsSize = (long) object.getStrongReferents().size() * getReferenceSize();
    long softRefsSize =
        (long) object.getSoftReferents().size()
            * (getReferenceSize() + computeReferenceObjectSize(SoftReference.class));
    long weakRefsSize =
        (long) object.getWeakReferents().size()
            * (getReferenceSize() + computeReferenceObjectSize(WeakReference.class));
    return align(initSize + strongRefsSize + softRefsSize + weakRefsSize);
  }

  @SuppressWarnings("rawtypes")
  private long computeReferenceObjectSize(Class<? extends Reference> type) {
    if (type == null) { // Strong reference, no real Reference objects in heap
      return 0;
    } else if (type == SoftReference.class) {
      return align(
          getOopHeaderSize(false) // For the oop header (a SoftReference object in heap)
              + getLongSize() // The `long timestamp` field
              + getReferenceSize() // The `T referent` field
              + getReferenceSize() // The `ReferenceQueue<? super T> queue` field
              + getReferenceSize() // The `Reference next` field
              + getReferenceSize() // The `Reference<?> discovered` field
          );
    } else if (type == WeakReference.class) {
      return align(
          getOopHeaderSize(false) // For the oop header (a WeakReference object in heap)
              + getReferenceSize() // The `T referent` field
              + getReferenceSize() // The `ReferenceQueue<? super T> queue` field
              + getReferenceSize() // The `Reference next` field
              + getReferenceSize() // The `Reference<?> discovered` field); // Each WeakReference
          // has a `int hash` field
          );
    } else {
      Assert.panic("Unsupported reference type: " + type.getName());
      return -1L; // Unreachable, but needed to satisfy the compiler
    }
  }

  private long computeStringObjectSize(String str) {
    return align(
        getOopHeaderSize(false) // For the oop header of the String object
            + getReferenceSize() // Each String has a `byte[] value` reference
            + computePrimitiveArrayObjectSize(byte.class, str.getBytes().length)
            + getByteSize() // Each String has a `byte coder` field
            + getIntSize() // Each String has a `int hash` field
            + getBoolSize() // Each String has a `boolean hashIsZero` field
        );
  }

  @SuppressWarnings("SameParameterValue")
  private long computePrimitiveArrayObjectSize(Class<?> elementType, int size) {
    Assert.set(
        elementType.isPrimitive(),
        "Element type must be a primitive type, but got " + elementType.getName());
    Assert.set(size >= 0, "Size must be non-negative, but got " + size);
    if (elementType == boolean.class) {
      return align(getOopHeaderSize(true) + (long) size * getBoolSize());
    } else if (elementType == byte.class) {
      return align(getOopHeaderSize(true) + (long) size * getByteSize());
    } else if (elementType == char.class) {
      return align(getOopHeaderSize(true) + (long) size * getCharSize());
    } else if (elementType == short.class) {
      return align(getOopHeaderSize(true) + (long) size * getShortSize());
    } else if (elementType == int.class) {
      return align(getOopHeaderSize(true) + (long) size * getIntSize());
    } else if (elementType == long.class) {
      return align(getOopHeaderSize(true) + (long) size * getLongSize());
    } else if (elementType == float.class) {
      return align(getOopHeaderSize(true) + (long) size * getFloatSize());
    } else if (elementType == double.class) {
      return align(getOopHeaderSize(true) + (long) size * getDoubleSize());
    } else {
      Assert.panic("Unsupported primitive type: " + elementType.getName());
      return -1L; // Unreachable, but needed to satisfy the compiler
    }
  }

  private long align(long size) {
    return isMode64 ? (size + 7) & ~7 : (size + 3) & ~3;
  }
}
