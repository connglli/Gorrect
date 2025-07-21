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

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.smith.IdSmith;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.NetBeanHeapProfilers;
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

  public static GGraph parse(String hprofFile) throws IOException {
    Heap heap = HeapFactory.createHeap(new File(hprofFile));

    String className = GeneratedMainClass.clazzName.canonicalName();
    JavaClass mainClazz = heap.getJavaClassByName(className);
    Assert.set(
        mainClazz != null,
        "Main class not found in the heap: clazz=%s, hprof=%s",
        className,
        hprofFile);

    Map<Long, HprofObjectRecord> roots = new HashMap<>();
    Map<Long, HprofObjectRecord> nonRoots = new HashMap<>();
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
          HprofObjectRecord.isGeneratedByUs(o),
          "Expected Instance for strong reference, but got " + o.getClass());
      Instance instance = (Instance) o;
      long instanceId = instance.getInstanceId();
      HprofObjectRecord root = HprofObjectRecord.parse(instance);
      roots.put(instanceId, root);
      log.info("Found root object: %s (%d)", root.id, instanceId);
    }

    // Walk the heap to find all non-root objects
    @SuppressWarnings("rawtypes")
    Iterator objectIterator = heap.getAllInstancesIterator();
    while (objectIterator.hasNext()) {
      Object o = objectIterator.next();
      if (!HprofObjectRecord.isGeneratedByUs(o)) {
        continue;
      }
      Instance instance = (Instance) o;
      long instanceId = instance.getInstanceId();
      if (roots.containsKey(instanceId)) {
        // This is a root object, skip it
        continue;
      }
      HprofObjectRecord object = HprofObjectRecord.parse(instance);
      nonRoots.put(instanceId, object);
      log.info("Found non-root object: %s (%d)", object.id, instanceId);
    }

    // Register all objects and references into a GGraph
    GGraph graph = new GGraph();
    for (HprofObjectRecord root : roots.values()) {
      GID rootId = root.id;
      Assert.set(!graph.hasObject(rootId), "Root object already exists: " + rootId);
      log.info("Registering root: " + rootId);
      graph.addObject(rootId, GObject.Type.GCROOT, 0);
    }
    for (HprofObjectRecord object : nonRoots.values()) {
      GID objectId = object.id;
      Assert.set(!graph.hasObject(objectId), "Non-root object already exists: " + objectId);
      log.info("Registering non-root: " + objectId);
      graph.addObject(objectId, GObject.Type.GCROOT, 0);
    }
    for (Collection<HprofObjectRecord> objects : Arrays.asList(roots.values(), nonRoots.values())) {
      for (HprofObjectRecord referer : objects) {
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
    }
    return graph;
  }

  record HprofObjectRecord(@NonNull GID id, @NonNull Map<GID, GReference.Type> referents) {

    static boolean isGeneratedByUs(Object o) {
      return o instanceof Instance
          && ((Instance) o)
              .getJavaClass()
              .getName()
              .startsWith(GeneratedObjectClass.CLAZZ_NAME_PREFIX);
    }

    static boolean isReferenceInstance(Object o, Class<? extends Reference> clazz) {
      return o instanceof Instance
          && ((Instance) o).getJavaClass().getName().equals(clazz.getCanonicalName());
    }

    public static HprofObjectRecord parse(@NonNull Instance instance) {
      Assert.set(isGeneratedByUs(instance), "Expected a generated object, but got: " + instance);
      GID id = parseIdField(instance);
      Map<GID, GReference.Type> referents = parseReferentFields(instance);
      return new HprofObjectRecord(id, referents);
    }

    private static GID parseIdField(Instance instance) {
      String idIntern =
          NetBeanHeapProfilers.parseStringField(
              instance, GeneratedObjectBaseClass.objectId.name(), false);
      Assert.set(idIntern != null, "Expected a non-null id for instance " + instance);
      return new GID(idIntern);
    }

    private static Map<GID, GReference.Type> parseReferentFields(Instance instance) {
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
            referents.put(id, GReference.Type.SOFT);
          }
          default -> {
            // Ignore other fields
          }
        }
      }
      return referents;
    }
  }
}
