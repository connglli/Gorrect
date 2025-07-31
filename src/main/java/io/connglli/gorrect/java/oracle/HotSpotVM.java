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

import com.palantir.javapoet.ClassName;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.OracleObject;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Checksums;
import io.connglli.gorrect.utility.Formats;
import io.connglli.gorrect.utility.Reflections;
import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import org.openjdk.jol.datamodel.DataModel;
import org.zeroturnaround.exec.ProcessExecutor;

public class HotSpotVM extends JavaVM {
  public static final String BRAND_NAME = "HotSpot/OpenJDK";

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
   * See: {@link HotSpotObjects} for the implementation of the object layout.
   *
   * More:
   * - The Structure of the Java Virtual Machine (https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html)
   * - Java Object Layout for HotSpot: https://github.com/openjdk/jol
   */
  private final HotSpotObjects model;

  // We trace the allocated objects so that we can estimate the heap size
  private final Map<GID, Long> allocatedObjects = new LinkedHashMap<>();
  private final Map<GID, Long> freedObjects = new LinkedHashMap<>();

  public HotSpotVM(
      File home,
      long heapCapacity,
      int version,
      boolean mode64,
      boolean enableCompressedOops,
      boolean enableCompressedClassPointers,
      int alignment) {
    super(home, heapCapacity);
    this.model =
        new HotSpotObjects(
            version, mode64, enableCompressedOops, enableCompressedClassPointers, alignment);
  }

  /*package*/ HotSpotVM(File home, long heapCapacity, int version, DataModel model) {
    super(home, heapCapacity);
    this.model = new HotSpotObjects(version, model);
  }

  @Override
  public long getReferenceSize() {
    return this.model.getReferenceSize();
  }

  @Override
  public void reset() {
    super.reset();
    this.allocatedObjects.clear();
    this.freedObjects.clear();
  }

  @Override
  public void onOracleAllocate(@NonNull GID objectId, long size, GObject.Type type) {
    long estimatedSize = estimateNewObjectHeapSize(objectId, size);
    reduceMemory(estimatedSize);
    allocatedObjects.put(objectId, estimatedSize);
  }

  public void onOracleFree(@NonNull OracleObject object) {
    reclaimMemory(List.of(object));
  }

  @Override
  public void onOracleClean(@NonNull List<OracleObject> removedObjects) {
    reclaimMemory(removedObjects);
  }

  @Override
  public void onOracleCompact(@NonNull List<OracleObject> removedObjects) {
    reclaimMemory(removedObjects);
  }

  @Override
  public void onOracleStressCompact(@NonNull List<OracleObject> removedObjects) {
    reclaimMemory(removedObjects);
  }

  @Override
  public void onOracleReference(
      @NonNull GID referenceId,
      @NonNull OracleObject referer,
      @NonNull OracleObject referent,
      GReference.Type type) {
    // Each reference adds a reference field in the generated object
    long refSize =
        getReferenceSize() // For the reference field in the generated object
            + computeReferenceObjectSize( // For the Reference object in heap
                type == GReference.Type.STRONG
                    ? null
                    : type == GReference.Type.SOFT ? SoftReference.class : WeakReference.class);
    GID refererId = referer.getId();
    Assert.set(
        allocatedObjects.containsKey(refererId),
        "Referer object with ID " + refererId + " is not allocated in the heap");
    reduceMemory(refSize);
    allocatedObjects.put(refererId, allocatedObjects.get(refererId) + refSize);
  }

  @Override
  public void onOracleUnlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {
    // This does not change the heap usage, unless the referent is no longer reachable.
    // We do not track the reachability. We rely on onOracleClean(), onOracleCompact(), and
    // onOracleStressCompact() to reclaim the memory.
  }

  @Override
  public long estimateNewObjectHeapSize(@NonNull GID objectId, long requestedSize) {
    // NOTE: This implementation is closely BOUND to the version of HotSpot, the String class, and
    // our generated object. We know that each our generated object has:
    // - a String id,
    // - a long size, and
    // - a byte[][] padding.
    Assert.set(requestedSize >= 0, "Requested size must be non-negative, but got " + requestedSize);
    int maxChunkSize = Integer.MAX_VALUE - 8;
    int numChunks = (int) ((requestedSize + maxChunkSize - 1) / maxChunkSize);
    int lastChunkSize = (int) (requestedSize % maxChunkSize);
    long paddingSize = 0;
    if (numChunks > 0) {
      // For the padding's first (numChunks - 1) chunks: they are all byte[maxChunkSize]{...}
      paddingSize =
          (numChunks - 1) * (model.computeShallowSize(byte[].class) + maxChunkSize * getByteSize())
              // For the padding's last chunk: it is byte[remainingSize]{...}
              + (model.computeShallowSize(byte[].class) + lastChunkSize * getByteSize());
    }
    return model.computeShallowSize(loadGeneratedObjectBaseClass())
        + model.computeShallowSize(String.class) // For the id's shallow size in the heap
        + model.computeShallowSize(
            objectId.get().getBytes()) // For the id's byte[] value field in the heap
        + model.computeShallowSize(byte[][].class) // For the padding's shallow size in the heap
        + paddingSize // For the padding's byte[][] value field in the heap
    ;
  }

  @Override
  public long estimateObjectHeapSize(@NonNull OracleObject object) {
    // TODO: Generate the class and estimate its size
    long initSize = estimateNewObjectHeapSize(object.getId(), object.getSize());
    // Each strong reference only saves the reference in the generated object,
    long strongRefsSize = (long) object.getStrongReferents().size() * getReferenceSize();
    // A soft reference saves the reference in the generated object and a SoftReference in heap,
    long softRefsSize =
        (long) object.getSoftReferents().size()
            * (getReferenceSize() + computeReferenceObjectSize(SoftReference.class));
    // A weak reference saves the reference in the generated object and a WeakReference in heap,
    long weakRefsSize =
        (long) object.getWeakReferents().size()
            * (getReferenceSize() + computeReferenceObjectSize(WeakReference.class));
    return model.align(initSize + strongRefsSize + softRefsSize + weakRefsSize);
  }

  @SuppressWarnings("rawtypes")
  private long computeReferenceObjectSize(Class<? extends Reference> type) {
    if (type == null) { // Strong reference, no real Reference objects in heap
      return 0;
    } else if (type == SoftReference.class) {
      return model.computeShallowSize(SoftReference.class);
    } else if (type == WeakReference.class) {
      return model.computeShallowSize(WeakReference.class);
    } else {
      Assert.panic("Unsupported reference type: " + type.getName());
      return -1L; // Unreachable, but needed to satisfy the compiler
    }
  }

  private void reduceMemory(long requestedSize) {
    if (heapUnusedBytes < requestedSize) {
      throw new OutOfMemError(
          "Java heap out of memory: "
              + Formats.toCommaString(heapUnusedBytes)
              + " bytes left, "
              + Formats.toCommaString(requestedSize)
              + " bytes needed.");
    }
    heapUnusedBytes -= requestedSize;
  }

  private void reclaimMemory(@NonNull List<OracleObject> removedObjects) {
    for (OracleObject object : removedObjects) {
      GID objectId = object.getId();
      Assert.set(
          allocatedObjects.containsKey(objectId),
          "Object with ID " + objectId + " is not allocated in the heap");
      long size = model.align(allocatedObjects.get(objectId));
      // When an object is freed, the memory usage is clear. We update the size.
      // Add new references may cause the memory not aligned, so we align the size
      allocatedObjects.remove(objectId);
      freedObjects.put(objectId, size);
      heapUnusedBytes += size;
    }
  }

  private static final String generatedObjectBaseClassChecksum = "0125df9f6f9dd1df015e39ef78fae78b";
  private static Class<?> generatedObjectBaseClass = null;

  private static Class<?> loadGeneratedObjectBaseClass() {
    if (generatedObjectBaseClass == null) {
      // Since we do not have access to the package-level class (I don't want to make it public),
      // we load the class using reflection. This is subject to change if the class is changed.
      try {
        ClassLoader thisClassLoader = HotSpotVM.class.getClassLoader();

        Class<?> heapClass = thisClassLoader.loadClass("io.connglli.gorrect.java.GeneratedHeap");
        Constructor<?> heapConstructor = heapClass.getDeclaredConstructor();
        heapConstructor.setAccessible(true);
        Object heap = heapConstructor.newInstance();

        Class<?> objectBaseClass =
            thisClassLoader.loadClass("io.connglli.gorrect.java.GeneratedObjectBaseClass");
        Constructor<?> objectBaseConstructor = objectBaseClass.getDeclaredConstructor(heapClass);
        objectBaseConstructor.setAccessible(true);
        Object objectBase = objectBaseConstructor.newInstance(heap);
        Method toJavaCodeMethod = Reflections.getMethod(objectBaseClass, "toJavaCode");
        toJavaCodeMethod.setAccessible(true);
        Field classNameField = Reflections.getField(objectBaseClass, "clazzName");
        classNameField.setAccessible(true);

        String className = ((ClassName) classNameField.get(null)).canonicalName();
        String javaCode = (String) toJavaCodeMethod.invoke(objectBase);
        // Compute the checksum of the generated code to ensure no changes have been made
        String checksum = Checksums.md5(javaCode.strip());
        Assert.set(
            generatedObjectBaseClassChecksum.equals(checksum),
            "The checksum of the generated code does not match the expected one, "
                + "indicating that the implementation of the generated object has changed. "
                + "Expected: %s. Actual: %s",
            generatedObjectBaseClassChecksum,
            checksum);

        Path tempDir = Files.createTempDirectory(UUID.randomUUID().toString());
        File javaFile = new File(tempDir.toFile(), className + ".java");
        Files.writeString(javaFile.toPath(), javaCode);
        new ProcessExecutor()
            .command("javac", "-cp", tempDir.toString(), javaFile.toString())
            .redirectErrorStream(true)
            .timeout(30, TimeUnit.SECONDS)
            .exitValue(0)
            .execute();

        try (URLClassLoader classLoader =
            new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, thisClassLoader)) {
          generatedObjectBaseClass = classLoader.loadClass(className);
        }
      } catch (Exception e) {
        Assert.panic(
            "Failed to load the GeneratedObjectBaseClass, perhaps the implementation of it has changed: "
                + e.getMessage());
      }
    }
    return generatedObjectBaseClass;
  }
}
