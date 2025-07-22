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

package io.connglli.gorrect.java;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.smith.IdSmith;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Files;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.NBHprofParser;
import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import lombok.NonNull;
import org.netbeans.lib.profiler.heap.*;

/**
 * HprofParser is a utility class to parse HPROF files and convert it into a GGraph using the
 * NetBeans Profiler library.
 */
public class HprofParser {
  public static final Logger log = Logger.getLogger(HprofParser.class);

  public static GGraph parse(@NonNull String hprofFile) throws IOException {
    deleteCacheIfNecessary(hprofFile);

    Heap heap = HeapFactory.createHeap(new File(hprofFile));

    String className = GeneratedMainClass.clazzName.canonicalName();
    JavaClass mainClazz = heap.getJavaClassByName(className);
    Assert.set(
        mainClazz != null,
        "Main class not found in the heap: clazz=%s, hprof=%s",
        className,
        hprofFile);

    Map<Long, GObjRecord> objects = new HashMap<>();
    IdSmith refIdSmith = new IdSmith("ref");

    // Walk the main class in the heap to find all root objects
    for (Object f : mainClazz.getStaticFieldValues()) {
      Assert.set(f instanceof FieldValue, "Expected FieldValue, but got " + f.getClass());
      FieldValue fieldValuePair = (FieldValue) f;
      Field field = fieldValuePair.getField();
      if (!field.isStatic()
          || !field.getName().startsWith(GeneratedMainClass.ROOT_REF_FIELD_NAME_PREFIX)) {
        continue; // Skip static fields as all our references are instance fields
      }
      Object o = mainClazz.getValueOfStaticField(field.getName());
      if (o == null) {
        continue; // The root reference is no longer reachable.
      }
      Assert.set(
          // We're a root reference, so it must a generated object
          GObjRecord.isGeneratedByUs(o),
          "Expected Instance for strong reference, but got " + o.getClass());
      Instance instance = (Instance) o;
      long instanceId = instance.getInstanceId();
      GObjRecord root = GObjRecord.parse(instance, GObject.Type.GCROOT);
      objects.put(instanceId, root);
      logFoundObject(instance, root, true);
    }

    // Walk the heap to find all non-root objects
    JavaClass objectBaseClazz =
        heap.getJavaClassByName(GeneratedObjectBaseClass.clazzName.canonicalName());
    Assert.set(
        objectBaseClazz != null,
        "Base object class not found in the heap: clazz=%s, hprof=%s",
        GeneratedObjectBaseClass.clazzName.canonicalName(),
        hprofFile);
    for (Object c : objectBaseClazz.getSubClasses()) {
      Assert.set(c instanceof JavaClass, "Expected JavaClass, but got " + c.getClass());
      JavaClass clazz = (JavaClass) c;

      @SuppressWarnings("rawtypes")
      Iterator objectIterator = clazz.getInstancesIterator();

      while (objectIterator.hasNext()) {
        Object o = objectIterator.next();
        if (!GObjRecord.isGeneratedByUs(o)) {
          continue;
        }
        Instance instance = (Instance) o;
        long instanceId = instance.getInstanceId();
        if (objects.containsKey(instanceId)) {
          continue;
        }
        GObjRecord object = GObjRecord.parse(instance, GObject.Type.ORDINARY);
        objects.put(instanceId, object);
        logFoundObject(instance, object, false);
      }
    }

    // Register all objects and references into a GGraph
    GGraph graph = new GGraph();
    for (GObjRecord object : objects.values()) {
      GID objectId = object.id;
      Assert.set(
          !graph.hasObject(objectId),
          "%s object already exists: %s",
          object.isRoot() ? "Root" : "Non-root",
          objectId);
      log.info("Registering non-root: " + objectId);
      graph.addObject(objectId, object.type, object.size);
    }
    for (GObjRecord referer : objects.values()) {
      GID refererId = referer.id;
      Assert.set(graph.hasObject(refererId), "Referer object does not exist: " + refererId);
      for (Map.Entry<GID, GReference.Type> entry : referer.referents.entrySet()) {
        GID referentId = entry.getKey();
        GReference.Type referenceType = entry.getValue();
        Assert.set(graph.hasObject(referentId), "Referent object does not exist: " + referentId);
        GID referenceId = refIdSmith.next(refererId, referentId);
        graph.addReference(referenceId, refererId, referentId, referenceType);
        log.info("Register reference: %s -> %s (%s)", refererId, referentId, referenceType);
      }
    }
    return graph;
  }

  private record GObjRecord(
      @NonNull GID id,
      @NonNull GObject.Type type,
      long size,
      @NonNull Map<GID, GReference.Type> referents) {
    private static final Map<Long, GID> cachedIds = new WeakHashMap<>();

    static boolean isGeneratedByUs(Object o) {
      return o instanceof Instance
          && ((Instance) o)
              .getJavaClass()
              .getName()
              .startsWith(GeneratedObjectClass.CLAZZ_NAME_PREFIX);
    }

    @SuppressWarnings("rawtypes")
    static boolean isReferenceInstance(Object o, Class<? extends Reference> clazz) {
      return o instanceof Instance
          && ((Instance) o).getJavaClass().getName().equals(clazz.getCanonicalName());
    }

    static GObjRecord parse(@NonNull Instance instance, GObject.Type type) {
      Assert.set(isGeneratedByUs(instance), "Expected a generated object, but got: " + instance);
      GID id = parseIdField(instance);
      Long size = parseSizeField(instance);
      Map<GID, GReference.Type> referents = parseReferentFields(instance);
      return new GObjRecord(id, type, size, referents);
    }

    static GID parseIdField(@NonNull Instance instance) {
      long instanceId = instance.getInstanceId();
      GID objectId = cachedIds.getOrDefault(instanceId, null);
      if (objectId != null) {
        return objectId; // Return cached ID if available
      }
      Assert.set(
          GeneratedObjectBaseClass.objectId.type().equals(ClassName.get(String.class)),
          "Expected objectId field to be of type String, but got "
              + GeneratedObjectBaseClass.objectId.type());
      String idIntern =
          NBHprofParser.parseStringField(instance, GeneratedObjectBaseClass.objectId.name(), false);
      Assert.set(idIntern != null, "Expected a non-null id for instance " + instance);
      objectId = new GID(idIntern);
      cachedIds.put(instanceId, objectId);
      return objectId;
    }

    static Long parseSizeField(@NonNull Instance instance) {
      Assert.set(
          GeneratedObjectBaseClass.objectSize.type().equals(TypeName.get(long.class)),
          "Expected objectSize field to be of type long, but got "
              + GeneratedObjectBaseClass.objectSize.type());
      Long size =
          NBHprofParser.parsePrimitiveField(
              instance, GeneratedObjectBaseClass.objectSize.name(), Long.class, false);
      Assert.set(size != null, "Expected a non-null size for instance " + instance);
      return size;
    }

    static Map<GID, GReference.Type> parseReferentFields(@NonNull Instance instance) {
      Map<GID, GReference.Type> referents = new HashMap<>();
      for (Object f : instance.getFieldValues()) {
        Assert.set(f instanceof FieldValue, "Expected FieldValue, but got " + f.getClass());
        FieldValue fieldValuePair = (FieldValue) f;
        Field field = fieldValuePair.getField();
        if (field.isStatic()) {
          continue; // Skip static fields as all our references are instance fields
        }
        switch (field.getName()) {
          case String fieldName
              when fieldName.startsWith(GeneratedObjectClass.STRONG_REF_FIELD_NAME_PREFIX) -> {
            Object o = instance.getValueOfField(fieldName);
            if (o == null) {
              continue; // The referent no longer reachable.
            }
            Assert.set(
                // We're a strong reference, so it must a generated object
                isGeneratedByUs(o),
                "Expected Instance for strong reference, but got " + o.getClass());
            GID id = parseIdField((Instance) o);
            referents.put(id, GReference.Type.STRONG);
          }
          case String fieldName
              when fieldName.startsWith(GeneratedObjectClass.SOFT_REF_FIELD_NAME_PREFIX) -> {
            Object o = instance.getValueOfField(fieldName);
            if (o == null) {
              continue; // The referent no longer reachable.
            }
            Assert.set(
                // We're a soft reference, so it must a SoftReference
                isReferenceInstance(o, SoftReference.class),
                "Expected Instance for soft reference, but got " + o.getClass());
            Instance refInstance = (Instance) o;
            o = refInstance.getValueOfField("referent");
            Assert.set(
                isGeneratedByUs(o),
                "Expected Instance for soft reference's referent, but got " + o.getClass());
            GID id = parseIdField((Instance) o);
            referents.put(id, GReference.Type.SOFT);
          }
          case String fieldName
              when fieldName.startsWith(GeneratedObjectClass.WEAK_REF_FIELD_NAME_PREFIX) -> {
            Object o = instance.getValueOfField(fieldName);
            if (o == null) {
              continue; // The referent no longer reachable.
            }
            Assert.set(
                // We're a weak reference, so it must a WeakReference
                isReferenceInstance(o, WeakReference.class),
                "Expected Instance for weak reference, but got " + o.getClass());
            Instance refInstance = (Instance) o;
            o = refInstance.getValueOfField("referent");
            Assert.set(
                isGeneratedByUs(o),
                "Expected Instance for weak reference's referent, but got " + o.getClass());
            GID id = parseIdField((Instance) o);
            referents.put(id, GReference.Type.WEAK);
          }
          default -> {
            // Ignore other fields
          }
        }
      }
      return referents;
    }

    boolean isRoot() {
      return type == GObject.Type.GCROOT;
    }
  }

  private static void deleteCacheIfNecessary(@NonNull String hprof) {
    File hprofFile = new File(hprof);
    Assert.set(hprofFile.exists(), "Hprof file does not exist: " + hprofFile.getAbsolutePath());
    Assert.set(
        hprofFile.isFile(), "Hprof file is not a regular file: " + hprofFile.getAbsolutePath());
    File cacheDir = new File(hprofFile + ".nbcache");
    if (cacheDir.exists()) {
      if (!cacheDir.isDirectory()) {
        Assert.panic("A cache file exists but is not a directory: " + cacheDir.getAbsolutePath());
      }
      // Delete the cache if it is older than the hprof file
      if (cacheDir.lastModified() <= hprofFile.lastModified()) {
        log.info(
            "Deleting existing hprof cache since it's tool old: " + cacheDir.getAbsolutePath());
        boolean deleted = Files.deleteFileTreeIfExists(cacheDir.toPath());
        Assert.set(deleted, "Failed to delete hprof cache: " + cacheDir.getAbsolutePath());
      }
    }
  }

  private static void logFoundObject(
      @NonNull Instance instance, GObjRecord object, boolean isRoot) {
    log.info(
        "Found %s object: %s (instance_id=%d, root_obj=%b, num_referents=%d, padding_size=%sB, raw_instance_size=%dB, raw_base_size=%dB, retained_size=%dB, reachable_size=%dB)",
        isRoot ? "root" : "non-root",
        object.id,
        instance.getInstanceId(),
        isRoot,
        object.referents.size(),
        object.size,
        instance.getSize(),
        instance.getJavaClass().getSuperClass().getInstanceSize(),
        instance.getRetainedSize(),
        instance.getReachableSize());
  }
}
