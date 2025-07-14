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
import com.palantir.javapoet.FieldSpec;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.model.visitor.GObjectReversePostorderVisitor;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import lombok.NonNull;

/**
 * GeneratedHeap manages all generated Java objects and their reference relationships. Specifically,
 * it is responsible for generating code for their classes and for the operations on them in the
 * main class. It is also responsible for generating code for waiting for heap dump and exiting the
 * program. For each object, it generates a unique class for them and an unique instance
 * representing the object. The unique class contains one field for each object it has referenced,
 * is referencing, and will reference. These reference fields are either strong, soft, or weak. For
 * those root object, it generates a static field in the main class to reference them as root.
 */
class GeneratedHeap {
  private static final Logger log = Logger.getLogger(GeneratedHeap.class);

  // Strict mode means we disable all operations on non-strongly reachable objects as this might
  // introduce NullPointerExceptions in the generated code.
  private final boolean strict;

  @NonNull private final GeneratedMainClass mainClazz;

  @NonNull private final Map<GID, GeneratedObject> objects; // All generated objects

  @NonNull
  private final Map<GID, GeneratedObject>
      deadObjects; // All dead objects, which are not reachable from the root objects

  @NonNull
  private final Map<GID, List<GeneratedReference>> referents; // The referents of each object

  @NonNull
  private final Map<GID, GeneratedReference>
      deadReferences; // All dead references, which are not reachable from the root objects

  // Object pool is a temporary pool to save all created objects when initializing the
  // heap state or allocating some objects to avoid being garbage collected.
  private static final String OBJECT_POOL_NAME = "object_pool";
  private static final FieldSpec objectPoolField =
      FieldSpec.builder(
              ClassName.get(Map.class),
              OBJECT_POOL_NAME,
              Modifier.PRIVATE,
              Modifier.STATIC,
              Modifier.FINAL)
          .initializer(
              "new $T<$T, $T>()",
              ClassName.get(HashMap.class),
              ClassName.get(String.class),
              GeneratedObjectBaseClass.clazzName)
          .build();

  GeneratedHeap() {
    this(true);
  }

  GeneratedHeap(boolean enableStrictMode) {
    this.strict = enableStrictMode;
    mainClazz = new GeneratedMainClass(this);
    mainClazz.addField(objectPoolField);
    objects = new HashMap<>();
    deadObjects = new HashMap<>();
    referents = new HashMap<>();
    deadReferences = new HashMap<>();
  }

  GGraph getState() {
    GGraph graph = new GGraph();
    for (GeneratedObject obj : objects.values()) {
      graph.addObject(obj.getId(), obj.getType(), obj.getSize());
    }
    for (GeneratedObject referer : objects.values()) {
      for (GeneratedReference reference : referents.get(referer.getId())) {
        graph.addReference(
            reference.getId(),
            referer.getId(),
            reference.getReferent().getId(),
            reference.getType());
      }
    }
    graph.removeUnreachableObjects();
    return graph;
  }

  List<GeneratedObject> getLiveObjects() {
    List<GeneratedObject> sorted = new ArrayList<>(objects.values());
    sorted.sort(Comparator.comparing(GeneratedObject::getId));
    return sorted;
  }

  List<GeneratedObject> getDeadObjects() {
    List<GeneratedObject> sorted = new ArrayList<>(deadObjects.values());
    sorted.sort(Comparator.comparing(GeneratedObject::getId));
    return sorted;
  }

  String toJavaCode(String directory) {
    String filePath = directory + File.separator + mainClazz.getClazzName() + ".java";
    try (FileOutputStream fout = new FileOutputStream(filePath)) {
      fout.write(mainClazz.toJavaCode().getBytes());
      fout.write(new GeneratedObjectBaseClass(this).toJavaCode().getBytes());
      List<GID> sorted = new LinkedList<>();
      sorted.addAll(objects.keySet());
      sorted.addAll(deadObjects.keySet());
      sorted.sort(Comparator.comparing(GID::toString));
      for (GID objId : sorted) {
        GeneratedObject obj = objects.getOrDefault(objId, deadObjects.getOrDefault(objId, null));
        Assert.set(obj != null, "The object with id %s does not exist", objId);
        fout.write(obj.getClazz().toJavaCode().getBytes());
      }
      return filePath;
    } catch (IOException e) {
      Assert.panic("Cannot write to file %s: %s", filePath, e.getMessage());
      return null; // Unreachable, but required by the compiler
    }
  }

  String formatGraphStateToText() {
    return getState().formatToText();
  }

  String formatObjectStateToText() {
    StringBuilder sb = new StringBuilder();
    for (List<GeneratedObject> objectList : Arrays.asList(getLiveObjects(), getDeadObjects())) {
      for (GeneratedObject obj : objectList) {
        if (obj.getReachability() != null) {
          sb.append("Live Object:").append("\n");
        } else {
          sb.append("Dead Object:").append("\n");
        }
        sb.append("  ID: ").append(obj.getId()).append("\n");
        sb.append("  Root: ").append(obj.isRoot()).append("\n");
        sb.append("  Size: ").append(obj.getSize()).append("\n");
        sb.append("  Identifier: ").append(obj.getIdentifier()).append("\n");
        sb.append("  Reachability: ").append(obj.getReachability()).append("\n");
        sb.append("  Class:").append("\n");
        sb.append("    Name: ").append(obj.getClazz().getClazzName()).append("\n");
        List<FieldSpec> fields = obj.getClazz().getFields();
        if (fields.isEmpty()) {
          sb.append("    Fields: ").append("<empty>").append("\n");
        } else {
          sb.append("    Fields:").append("\n");
          for (FieldSpec field : fields) {
            sb.append("    - ").append(field.name()).append("\n");
          }
        }
      }
    }
    return sb.toString();
  }

  boolean hasObject(@NonNull GID objectId) {
    return objects.containsKey(objectId);
  }

  @NonNull
  GeneratedObject getObject(@NonNull GID objectId) {
    Assert.set(hasObject(objectId), "The object with id %s does not exist", objectId);
    return objects.get(objectId);
  }

  // -========================================================================
  // Initialization and Setup
  // -========================================================================

  void initialize(GGraph initState) {
    // Use the object pool to allocate objects during heap state initialization to avoid being
    // collected by the garbage collector.
    try (NonRootInPoolAllocator allocator = getNonRootInPoolAllocator()) {
      // For each root object in the graph, we instantiate them in the main class as root
      // object.
      for (GObject obj : initState.getRoots()) {
        allocRootWithInitializer(obj.getId(), obj.getSize());
      }
      // For each non-root object in the graph, we instantiate them in the object pool
      // so that they will definitely not be cleaned by GC during heap state initialization
      for (GObject obj : initState.getObjects()) {
        if (obj.isRoot()) {
          continue;
        }
        allocator.alloc(obj.getId(), obj.getSize());
      }
      // For each reference in the object graph, we add a reference to them.
      for (GReference reference : initState.getReferences()) {
        GeneratedObject referer = getObject(reference.getReferer());
        GeneratedObject referent = getObject(reference.getReferent());
        referer.reference(reference.getId(), referent, reference.getType());
      }
    }
  }

  // -========================================================================
  // Object Allocation and Free
  // -========================================================================

  @NonNull
  @SuppressWarnings("UnusedReturnValue")
  GeneratedObject allocRootWithInitializer(@NonNull GID rootId, long rootSize) {
    Assert.set(!hasObject(rootId), "The object with id %s already exists", rootId);
    GeneratedObjectClass rootClazz = new GeneratedObjectClass(this, rootId, rootSize);
    // Create a static field in the main class to reference the root object and at the same
    // time, directly instantiate the root object immediately after the main class is loaded.
    String fieldName = GeneratedMainClass.computeFieldName(rootClazz.getId());
    mainClazz.addField(
        FieldSpec.builder(
                GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC, Modifier.STATIC)
            .initializer("new $T()", rootClazz.clazzName)
            .build());
    return putObject(
        new GeneratedObject(
            this, rootClazz, GObject.Type.GCROOT, fieldName, GReference.Type.STRONG));
  }

  @NonNull
  @SuppressWarnings("UnusedReturnValue")
  GeneratedObject allocRootWithoutInitializer(@NonNull GID rootId, long rootSize) {
    Assert.set(!hasObject(rootId), "The object with id %s already exists", rootId);
    GeneratedObjectClass rootClazz = new GeneratedObjectClass(this, rootId, rootSize);
    // Create a static field in the main class to reference the root object but leave the
    // field as uninstantiated (i.e., initialize to null) when the main class is loaded.
    String fieldName = GeneratedMainClass.computeFieldName(rootClazz.getId());
    mainClazz.addField(
        FieldSpec.builder(
                GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC, Modifier.STATIC)
            .initializer("null")
            .build());
    // Instantiate the root object in the main method
    appendStatement(fieldName + " = new $T()", rootClazz.clazzName);
    return putObject(
        new GeneratedObject(
            this, rootClazz, GObject.Type.GCROOT, fieldName, GReference.Type.STRONG));
  }

  NonRootInPoolAllocator getNonRootInPoolAllocator() {
    return new NonRootInPoolAllocator();
  }

  class NonRootInPoolAllocator implements AutoCloseable {
    @SuppressWarnings("UnusedReturnValue")
    public GeneratedObject alloc(@NonNull GID objectId, @NonNull Long objectSize) {
      Assert.set(!hasObject(objectId), "The object with id %s already exists", objectId);
      GeneratedObjectClass objectClazz =
          new GeneratedObjectClass(GeneratedHeap.this, objectId, objectSize);
      // Create a temporary object in the pool so that it can be referenced later.
      GeneratedObject object = // The reachability will be updated after the pool is closed
          new GeneratedObject(
              GeneratedHeap.this,
              objectClazz,
              GObject.Type.ORDINARY,
              "%s.get(%s)".formatted(OBJECT_POOL_NAME, objectClazz.getId()),
              null);
      appendStatement(
          OBJECT_POOL_NAME + ".put($S, new $T())", objectClazz.getId(), objectClazz.clazzName); //
      return putObject(object);
    }

    @Override
    public void close() {
      // We clear the pool to release all the strong references
      appendStatement(OBJECT_POOL_NAME + ".clear()");
      // The reference relationship is changed, we should update the heap
      updateHeap();
    }
  }

  @NonNull
  @SuppressWarnings("UnusedReturnValue")
  GeneratedObject allocNonRootAndReference(
      @NonNull GID objectId,
      long objectSize,
      @NonNull GeneratedObject referer,
      @NonNull GReference.Type referenceType,
      @NonNull GID referenceId) {

    ensureReachable(
        referer,
        GReference.Type.STRONG,
        "Allocating non-roots to a non-strongly reachable object (id=%s, java_identifier=%s) might introduce NullPointerExceptions"
            .formatted(referer.getId(), referer.getIdentifier()));

    GeneratedClass refererClazz = referer.getClazz();

    GeneratedObjectClass objectClazz = new GeneratedObjectClass(this, objectId, objectSize);
    GeneratedObject object = // The reachability will be updated after the pool is closed
        putObject(new GeneratedObject(this, objectClazz, GObject.Type.ORDINARY, "", null));

    String fieldName = GeneratedObject.computeFieldName(referenceType, objectClazz.getId());
    switch (referenceType) {
      case STRONG:
        {
          // Create a field in our class so that we can reference the object
          refererClazz.addField(
              FieldSpec.builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the object object by our new field
          appendStatement(
              "%s.%s = new $T()".formatted(referer.getTypeCastIdentifier(), fieldName),
              objectClazz.clazzName);
          break;
        }

      case SOFT:
        {
          ClassName softRefClazz = ClassName.get(SoftReference.class);
          // Create a field in our class so that we can reference the object
          refererClazz.addField(
              FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the object object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(new $T())".formatted(referer.getTypeCastIdentifier(), fieldName),
              softRefClazz,
              objectClazz.clazzName,
              objectClazz.clazzName);
          break;
        }

      case WEAK:
        {
          ClassName weakRefClazz = ClassName.get(WeakReference.class);
          // Create a field in our class so that we can reference the object
          refererClazz.addField(
              FieldSpec.builder(weakRefClazz, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the object object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(new $T())".formatted(referer.getTypeCastIdentifier(), fieldName),
              weakRefClazz,
              objectClazz.clazzName,
              objectClazz.clazzName);
          break;
        }

      default:
        Assert.panic("Cannot reach here");
        break;
    }

    putReference(
        new GeneratedReference(
            this, referenceId, fieldName, referer.getId(), object.getId(), referenceType));

    // The reference relationship is changed, we should update the heap
    updateHeap();

    return object;
  }

  void freeObject(@NonNull GeneratedObject object) {
    object
        .getReferents()
        .forEach(
            referent -> {
              unlink(getReference(object, referent));
            });
    object
        .getReferers()
        .forEach(
            referer -> {
              unlink(getReference(referer, object));
            });
    if (object.isRoot()) {
      makeNonRoot(object);
    }
    // Remove the reference from the referents map
    moveObjectToDead(object);
  }

  // -========================================================================
  // Object Role Transition
  // -========================================================================

  void makeRoot(@NonNull GeneratedObject object) {
    ensureReachable(
        object,
        GReference.Type.STRONG,
        "Making a non-strongly reachable object (id=%s, java_identifier=%s) root might introduce NullPointerExceptions"
            .formatted(object.getId(), object.getIdentifier()));

    Assert.set(!object.isRoot(), "The object with id %s is already a root object", object.getId());
    // Create a static field in the main class to reference the root object but leave the
    // field as uninstantiated (i.e., initialize to null) when the main class is loaded.
    String fieldName = GeneratedMainClass.computeFieldName(object.getId());
    mainClazz.addField(
        FieldSpec.builder(
                GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC, Modifier.STATIC)
            .initializer("null")
            .build());
    // Transfer the ownership of the object to the main class
    appendStatement(fieldName + " = " + object.getTypeCastIdentifier());
    object.setType(GObject.Type.GCROOT);
    // The object turns to a root object, we should update the heap globally
    updateHeap();
  }

  void makeNonRoot(@NonNull GeneratedObject root) {
    Assert.set(root.isRoot(), "The object with id %s is not yet a root object", root.getId());
    String fieldName = GeneratedMainClass.computeFieldName(root.getId());
    Assert.set(
        mainClazz.hasField(fieldName),
        "Cannot remove root reference to %s, as it does not exist",
        fieldName);
    appendStatement(fieldName + " = null");
    root.setType(GObject.Type.ORDINARY);
    // The object turns to a non-root object, we should update the heap globally
    updateHeap();
  }

  // -========================================================================
  // Reference Addition and Deletion
  // -========================================================================

  List<GeneratedObject> getReferers(@NonNull GeneratedObject referent) {
    Assert.set(
        hasObject(referent.getId()), "The referent with id %s does not exist", referent.getId());
    List<GeneratedObject> referers = new ArrayList<>();
    for (GID referId : referents.keySet()) {
      for (GeneratedReference ref : referents.get(referId)) {
        if (ref.getReferent().getId().equals(referent.getId())) {
          referers.add(ref.getReferer());
        }
      }
    }
    return referers;
  }

  List<GeneratedObject> getReferents(@NonNull GeneratedObject referer) {
    Assert.set(
        hasObject(referer.getId()), "The referer with id %s does not exist", referer.getId());
    return referents.get(referer.getId()).stream()
        .map(GeneratedReference::getReferent)
        .collect(Collectors.toList());
  }

  List<GeneratedReference> getReferences(@NonNull GeneratedObject referer) {
    Assert.set(
        hasObject(referer.getId()), "The referer with id %s does not exist", referer.getId());
    return referents.get(referer.getId());
  }

  boolean hasReference(@NonNull GeneratedObject referer, @NonNull GeneratedObject referent) {
    Assert.set(
        hasObject(referer.getId()), "The referer with id %s does not exist", referer.getId());
    return referents.get(referer.getId()).stream()
        .anyMatch(ref -> ref.getReferent().getId().equals(referent.getId()));
  }

  @NonNull
  GeneratedReference getReference(
      @NonNull GeneratedObject referer, @NonNull GeneratedObject referent) {
    Assert.set(
        hasObject(referer.getId()), "The referer with id %s does not exist", referer.getId());
    return referents.get(referer.getId()).stream()
        .filter(ref -> ref.getReferent().getId().equals(referent.getId()))
        .findFirst()
        .orElseGet(
            () -> {
              Assert.panic(
                  "The reference from %s to %s does not exist", referer.getId(), referent.getId());
              return null; // Unreachable, but required by the compiler
            });
  }

  @NonNull
  GeneratedReference getReference(@NonNull GID referenceId) {
    for (List<GeneratedReference> references : referents.values()) {
      for (GeneratedReference reference : references) {
        if (reference.getId().equals(referenceId)) {
          return reference;
        }
      }
    }
    Assert.panic("The reference with id %s does not exist", referenceId);
    return null; // Unreachable, but required by the compiler
  }

  @NonNull
  GeneratedReference reference(
      @NonNull GID referenceId,
      @NonNull GeneratedObject referer,
      @NonNull GeneratedObject referent,
      @NonNull GReference.Type referenceType) {
    ensureReachable(
        referer,
        GReference.Type.STRONG,
        "Adding referents to the non-strongly reachable object (id=%s, java_identifier=%s) might introduce NullPointerExceptions"
            .formatted(referer.getId(), referer.getIdentifier()));

    String fieldName = GeneratedObject.computeFieldName(referenceType, referent.getId());
    GeneratedObjectClass refererClazz = referer.getClazz();

    switch (referenceType) {
      case STRONG:
        {
          // Create a field in our class so that we can reference the referent
          refererClazz.addField(
              FieldSpec.builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = %s"
                  .formatted(
                      referer.getTypeCastIdentifier(),
                      fieldName,
                      referent.getTypeCastIdentifier()));
          break;
        }

      case SOFT:
        {
          ClassName softRefClazz = ClassName.get(SoftReference.class);
          // Create a field in our class so that we can reference the referent
          refererClazz.addField(
              FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(%s)"
                  .formatted(
                      referer.getTypeCastIdentifier(), fieldName, referent.getTypeCastIdentifier()),
              softRefClazz,
              referent.getClazz().clazzName);
          break;
        }

      case WEAK:
        {
          ClassName weakRefClazz = ClassName.get(WeakReference.class);
          // Create a field in our class so that we can reference the referent
          refererClazz.addField(
              FieldSpec.builder(weakRefClazz, fieldName, Modifier.PUBLIC)
                  .initializer("null")
                  .build());
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(%s)"
                  .formatted(
                      referer.getTypeCastIdentifier(), fieldName, referent.getTypeCastIdentifier()),
              weakRefClazz,
              referent.getClazz().clazzName);
          break;
        }

      default:
        Assert.panic("Cannot reach here");
        break;
    }

    // The reference relationship is changed, we should update the heap
    GeneratedReference reference =
        putReference(
            new GeneratedReference(
                this, referenceId, fieldName, referer.getId(), referent.getId(), referenceType));
    updateHeap();

    return reference;
  }

  void redirect(GeneratedReference reference, GeneratedObject newReferent) {
    GeneratedObject referer = reference.getReferer();
    GeneratedObject referent = reference.getReferent();

    ensureReachable(
        referer,
        GReference.Type.STRONG,
        "Redirecting referents of a non-strongly reachable object (id=%s, java_identifier=%s) might introduce NullPointerExceptions"
            .formatted(referer.getId(), referer.getIdentifier()));

    String fieldName = reference.getField();
    Assert.set(
        referer.getClazz().hasField(fieldName),
        "Cannot replace reference's referent %s to %s, as it does not exist",
        reference.getReferent().getId(),
        newReferent.getId());

    appendStatement(referent.getIdentifier() + " = " + newReferent.getTypeCastIdentifier());
    reference.setReferent(newReferent);

    // The reference relationship is changed, we should update the heap
    updateHeap();
  }

  void strengthen(GeneratedReference reference) {
    reference.setType(reference.getType().strengthen());
    // The reachability might change, we should update the heap
    updateHeap();
  }

  void soften(GeneratedReference reference) {
    reference.setType(reference.getType().soften());
    // The reachability might change, we should update the heap
    updateHeap();
  }

  void unlink(@NonNull GeneratedReference reference) {
    GeneratedObject referer = reference.getReferer();
    GeneratedObject referent = reference.getReferent();
    String fieldName = reference.getField();
    Assert.set(
        referer.getClazz().hasField(fieldName),
        "Cannot remove reference to %s, as it does not exist",
        fieldName);
    appendStatement(referent.getIdentifier() + " = null");

    // Remove the reference from the referents map
    moveReferenceToDead(reference);

    // The reference relationship is changed, we should update the heap
    updateHeap();
  }

  // -========================================================================
  // Helper Methods for Reference and Object Management
  // -========================================================================

  private GeneratedObject putObject(@NonNull GeneratedObject object) {
    Assert.set(!hasObject(object.getId()), "The object with id %s already exists", object.getId());
    referents.put(object.getId(), new LinkedList<>());
    objects.put(object.getId(), object);
    return object;
  }

  private void moveObjectToDead(@NonNull GeneratedObject object) {
    Assert.set(hasObject(object.getId()), "The object with id %s does not exist", object.getId());
    objects.remove(object.getId());
    deadObjects.put(object.getId(), object);
  }

  private GeneratedReference putReference(@NonNull GeneratedReference reference) {
    GeneratedObject referer = reference.getReferer();
    GeneratedObject referent = reference.getReferent();
    GID refererId = referer.getId();
    GID referentId = referent.getId();
    Assert.set(
        !hasReference(referer, referent),
        "The reference from %s to %s already exists",
        refererId,
        referentId);
    referents.get(refererId).add(reference);
    return reference;
  }

  private void moveReferenceToDead(@NonNull GeneratedReference reference) {
    GID refererId = reference.getReferer().getId();
    Assert.set(
        referents.containsKey(refererId), "The referer with id %s does not exist", refererId);
    Assert.set(
        referents.get(refererId).contains(reference),
        "The reference %s does not exist in the referer %s",
        reference.getId(),
        refererId);
    referents.get(refererId).remove(reference);
    deadReferences.put(reference.getId(), reference);
  }

  private void updateHeap() {
    // Update the state of the heap based on the current objects and references
    GGraph graph = getState();
    // Update the reachability of all objects based on the current state
    Map<GID, GReference.Type> reachableObjects = graph.markReachability();
    for (GID objId : objects.keySet()) {
      GeneratedObject obj = objects.get(objId);
      obj.setReachability(reachableObjects.getOrDefault(objId, null));
    }
    // Update the identifiers of all objects based on the strongest reference
    graph.accept(
        new GObjectReversePostorderVisitor() {
          private final Set<GID> visited = new HashSet<>();

          @Override
          public void visit(@NonNull GGraph graph, @NonNull GObject obj) {
            GeneratedObject genObj = getObject(obj.getId());

            if (obj.isRoot()) {
              // Root objects are always referenced by a static field in the main class.
              genObj.setIdentifier(GeneratedMainClass.computeFieldName(obj.getId()));
              visited.add(obj.getId());
              return;
            }

            // For other objects, we need to find the strongest reference
            GReference strongestRef = null;

            // When obj is visited, all its referers have been visited except for
            // cycles/backedges which are always relying on us. We exclude them.
            for (GObject referer : graph.getReferers(obj.getId())) {
              if (!visited.contains(referer.getId())) {
                // This is a cycle/backedge, we skip it
                continue;
              }
              GReference reference = graph.getReference(referer.getId(), obj.getId());
              if (strongestRef == null
                  || reference.getType().strongerThan(strongestRef.getType())) {
                strongestRef = reference;
              }
            }

            Assert.set(
                strongestRef != null,
                "The object %s has no referers, which should not happen",
                obj.getId());

            // We update the reference according to the strongest reference.
            GeneratedObject strongestGenReferer = getObject(strongestRef.getReferer());
            GeneratedReference strongestGenRef = getReference(strongestRef.getId());
            genObj.setIdentifier(
                strongestGenReferer.getTypeCastIdentifier() + "." + strongestGenRef.getField());

            visited.add(obj.getId());
          }
        });
  }

  private void ensureReachable(GeneratedObject object, GReference.Type type, String message) {
    if (object.getReachability() != type) {
      if (strict) {
        log.error(message);
      } else {
        log.warning(message);
      }
    }
  }

  private void appendStatement(@NonNull String stmtFormat, Object... stmtArgs) {
    mainClazz.addStatement(stmtFormat, stmtArgs);
  }
}
