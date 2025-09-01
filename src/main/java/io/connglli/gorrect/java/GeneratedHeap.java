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
import io.connglli.gorrect.gir.utility.UnmodifiableGraph;
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
 *
 * <p>Note that, in this heap implementation, an object is considered alive if it is allocated and
 * not yet manually freed, even though it is already unreachable from the root objects.
 */
class GeneratedHeap {
  private static final Logger log = Logger.getLogger(GeneratedHeap.class);

  // Strict mode means we disable all operations on non-strongly reachable objects as this might
  // introduce NullPointerExceptions in the generated code.
  private final boolean strict;

  @NonNull private final GeneratedMainClass mainClazz;

  @NonNull private final Map<GID, GeneratedObject> objects; // All generated objects

  @NonNull
  private final Map<GID, GeneratedObject> // We do not allow dead objects to be alive again
      deadObjects; // All dead objects, which are not reachable from the root objects

  @NonNull
  private final Map<GID, List<GeneratedReference>> referents; // The referents of each object

  @NonNull
  private final List<GeneratedReference> // We allow the same reference to come back
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
    strict = enableStrictMode;
    mainClazz = new GeneratedMainClass(this);
    mainClazz.addField(objectPoolField);
    objects = new HashMap<>();
    deadObjects = new HashMap<>();
    referents = new HashMap<>();
    deadReferences = new LinkedList<>();
  }

  ///  Get the state of the heap.
  GGraph getState(boolean clean) {
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
    if (clean) {
      graph.removeUnreachableObjects();
    }
    return graph;
  }

  /// Get the cleaned state (i.e., unreachable objects are removed) of the heap.
  GGraph getState() {
    return getState(true);
  }

  /// Get all the live (i.e. not moved to dead, either reachable or unreachable) objects.
  List<GeneratedObject> getLiveObjects() {
    List<GeneratedObject> sorted = new ArrayList<>(objects.values());
    sorted.sort(Comparator.comparing(GeneratedObject::getId));
    return sorted;
  }

  /// Get all the dead (i.e., unreachable and moved to dead) objects.
  List<GeneratedObject> getDeadObjects() {
    List<GeneratedObject> sorted = new ArrayList<>(deadObjects.values());
    sorted.sort(Comparator.comparing(GeneratedObject::getId));
    return sorted;
  }

  /// Append a comment to the generated class.
  void appendComment(String comment) {
    appendStatement("/* " + comment + " */");
  }

  /// Append a statement to the generated class.
  void appendStatement(@NonNull String stmtFormat, Object... stmtArgs) {
    mainClazz.addStatement(stmtFormat, stmtArgs);
  }

  /// Generate the Java code and return the relative file path to the workdir parameter.
  String exportToJava(String directory) {
    String relativePath = mainClazz.getClazzName() + ".java";
    String fullPath = directory + File.separator + relativePath;
    try (FileOutputStream fout = new FileOutputStream(fullPath)) {
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
      return relativePath;
    } catch (IOException e) {
      Assert.panic("Cannot write to file %s: %s", fullPath, e.getMessage());
      return null; // Unreachable, but required by the compiler
    }
  }

  /// Format the cleaned state of the heap to text.
  String formatGraphStateToText() {
    return getState().formatToText();
  }

  /// Format all the objects and their references to text.
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
        sb.append("  Identifier: ").append(obj.getJavaRefId()).append("\n");
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

  /// Query if a live object with the given id exists in the heap.
  boolean hasObject(@NonNull GID objectId) {
    return objects.containsKey(objectId);
  }

  /// Get a live object with the given id from the heap.
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
        allocator.reference(reference.getId(), referer, referent, reference.getType());
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
            this, rootClazz, GObject.Type.GCROOT, fieldName, GReference.Type.STRONG, null));
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
            this, rootClazz, GObject.Type.GCROOT, fieldName, GReference.Type.STRONG, null));
  }

  NonRootInPoolAllocator getNonRootInPoolAllocator() {
    return new NonRootInPoolAllocator();
  }

  class NonRootInPoolAllocator implements AutoCloseable {
    private List<GID> allocatedObjects = new ArrayList<>();

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
              "((%s) %s.get(\"%s\"))"
                  .formatted(objectClazz.clazzName, OBJECT_POOL_NAME, objectClazz.getId()),
              GReference.Type.STRONG, // It is strongly reachable as it is in the pool
              null);
      appendStatement(
          OBJECT_POOL_NAME + ".put($S, new $T())", objectClazz.getId(), objectClazz.clazzName);
      allocatedObjects.add(objectId);
      return putObject(object);
    }

    @NonNull
    GeneratedReference reference(
        @NonNull GID referenceId,
        @NonNull GeneratedObject referer,
        @NonNull GeneratedObject referent,
        @NonNull GReference.Type referenceType) {
      GeneratedReference reference =
          GeneratedHeap.this.reference(referenceId, referer, referent, referenceType);
      // After referencing, the reachability of the objects might change.
      // So we need to patch it back to avoid our allocated objects (and its referents)
      // from being set as non-strongly reachable.
      updateHeap(this);
      return reference;
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
            .formatted(referer.getId(), referer.getJavaRefId()));

    GeneratedClass refererClazz = referer.getClazz();

    GeneratedObjectClass objectClazz = new GeneratedObjectClass(this, objectId, objectSize);
    GeneratedObject object = // The reachability will be updated after the pool is closed
        putObject(
            new GeneratedObject(
                this,
                objectClazz,
                GObject.Type.ORDINARY,
                "",
                null, // The reachability will be updated later
                referenceType));

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
              "%s.%s = new $T()".formatted(referer.getJavaDerefId(), fieldName),
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
              "%s.%s = new $T<$T>(new $T())".formatted(referer.getJavaDerefId(), fieldName),
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
              "%s.%s = new $T<$T>(new $T())".formatted(referer.getJavaDerefId(), fieldName),
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
              // It is likely that unlink a reference will cause other objects to be unreachable
              // For example, b<->a, when removing b->a, a becomes unreachable. So it is unlikely
              // to unlink b<-a. In this case, we only need to free the link.
              GeneratedReference reference = getReference(referer, object);
              // The referent depends on us, now it is no longer reachable, i.e.,
              // being null in the generated Java. In this case, we only move it.
              if (referer.isReachable()) {
                unlink(reference);
              } else {
                moveReferenceToDead(reference);
              }
            });
    if (object.isRoot()) {
      makeNonRoot(object);
    }
    // Remove the reference from the referents map
    moveObjectToDead(object);
  }

  public void clean() {
    GGraph state = getState();
    Set<GID> reachableObjects = state.getReachableObjects();
    Set<GID> objectsToRemove = new HashSet<>();
    for (GID objectId : objects.keySet()) {
      if (!reachableObjects.contains(objectId)) {
        objectsToRemove.add(objectId);
      }
    }
    for (GID objectId : objectsToRemove) {
      moveObjectAndItsReferencesToDead(objects.get(objectId));
    }
  }

  public void compact() {
    GGraph state = getState();
    Map<GID, GReference.Type> reachability = state.markReachability();
    Set<GID> objectsToRemove = new HashSet<>();
    for (GID objectId : objects.keySet()) {
      GReference.Type reachType = reachability.get(objectId);
      if (reachType != GReference.Type.STRONG && reachType != GReference.Type.SOFT) {
        objectsToRemove.add(objectId);
      }
    }
    for (GID objectId : objectsToRemove) {
      moveObjectAndItsReferencesToDead(objects.get(objectId));
    }
  }

  public void stressCompact() {
    GGraph state = getState();
    Set<GID> reachableObjects = state.getStronglyReachableObjects();
    Set<GID> objectsToRemove = new HashSet<>();
    for (GID objectId : objects.keySet()) {
      if (!reachableObjects.contains(objectId)) {
        objectsToRemove.add(objectId);
      }
    }
    for (GID objectId : objectsToRemove) {
      moveObjectAndItsReferencesToDead(objects.get(objectId));
    }
  }

  // -========================================================================
  // Object Role Transition
  // -========================================================================

  void makeRoot(@NonNull GeneratedObject object) {
    ensureReachable(
        object,
        GReference.Type.STRONG,
        "Making a non-strongly reachable object (id=%s, java_identifier=%s) root might introduce NullPointerExceptions"
            .formatted(object.getId(), object.getJavaRefId()));

    Assert.set(!object.isRoot(), "The object with id %s is already a root object", object.getId());
    // Create a static field in the main class to reference the root object but leave the
    // field as uninstantiated (i.e., initialize to null) when the main class is loaded.
    String fieldName = GeneratedMainClass.computeFieldName(object.getId());
    // It is likely that the object is made root for a second time
    if (!mainClazz.hasField(fieldName)) {
      mainClazz.addField(
          FieldSpec.builder(
                  GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC, Modifier.STATIC)
              .initializer("null")
              .build());
    }
    // Transfer the ownership of the object to the main class
    appendStatement(fieldName + " = " + object.getJavaDerefId());
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
    return referents.get(referer.getId()).stream().collect(Collectors.toList());
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
            .formatted(referer.getId(), referer.getJavaRefId()));

    String fieldName = GeneratedObject.computeFieldName(referenceType, referent.getId());
    GeneratedObjectClass refererClazz = referer.getClazz();

    switch (referenceType) {
      case STRONG:
        {
          // Create a field in our class so that we can reference the referent
          // It is likely that the referer and the referent has been linked before
          if (!refererClazz.hasField(fieldName)) {
            refererClazz.addField(
                FieldSpec.builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC)
                    .initializer("null")
                    .build());
          }
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = %s"
                  .formatted(referer.getJavaDerefId(), fieldName, referent.getJavaDerefId()));
          break;
        }

      case SOFT:
        {
          ClassName softRefClazz = ClassName.get(SoftReference.class);
          // Create a field in our class so that we can reference the referent
          // It is likely that the referer and the referent has been linked before
          if (!refererClazz.hasField(fieldName)) {
            refererClazz.addField(
                FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                    .initializer("null")
                    .build());
          }
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(%s)"
                  .formatted(referer.getJavaDerefId(), fieldName, referent.getJavaDerefId()),
              softRefClazz,
              referent.getClazz().clazzName);
          break;
        }

      case WEAK:
        {
          ClassName weakRefClazz = ClassName.get(WeakReference.class);
          // Create a field in our class so that we can reference the referent
          // It is likely that the referer and the referent has been linked before
          if (!refererClazz.hasField(fieldName)) {
            refererClazz.addField(
                FieldSpec.builder(weakRefClazz, fieldName, Modifier.PUBLIC)
                    .initializer("null")
                    .build());
          }
          // Reference the referent object by our new field
          appendStatement(
              "%s.%s = new $T<$T>(%s)"
                  .formatted(referer.getJavaDerefId(), fieldName, referent.getJavaDerefId()),
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
            .formatted(referer.getId(), referer.getJavaRefId()));

    String fieldName = reference.getField();
    Assert.set(
        referer.getClazz().hasField(fieldName),
        "Cannot replace reference's referent %s to %s, as it does not exist",
        reference.getReferent().getId(),
        newReferent.getId());

    switch (reference.getType()) {
      case STRONG:
        appendStatement(
            referer.getJavaDerefId() + "." + fieldName + " = " + newReferent.getJavaDerefId());
        break;

      case SOFT:
        appendStatement(
            referer.getJavaDerefId()
                + "."
                + fieldName
                + " = new $T<$T>(%s)".formatted(newReferent.getJavaDerefId()),
            ClassName.get(SoftReference.class),
            newReferent.getClazz().clazzName);
        break;

      case WEAK:
        appendStatement(
            referer.getJavaDerefId()
                + "."
                + fieldName
                + " = new $T<$T>(%s)".formatted(newReferent.getJavaDerefId()),
            ClassName.get(WeakReference.class),
            newReferent.getClazz().clazzName);
        break;
    }
    reference.setReferent(newReferent);

    // The reference relationship is changed, we should update the heap
    updateHeap();
  }

  void strengthen(GeneratedReference reference) {
    GeneratedObject referer = reference.getReferer();
    GeneratedObject referent = reference.getReferent();
    boolean isRoot = referent.isRoot();
    if (!isRoot) {
      referent.toRoot(); // Temporarily make it a root to ensure it is reachable
    }
    unlink(reference);
    reference(reference.getId(), referer, referent, reference.getType().strengthen());
    if (!isRoot) {
      referent.toNonRoot(); // Restore the original state
    }
    // The reachability might change, we should update the heap
    updateHeap();
  }

  void soften(GeneratedReference reference) {
    GeneratedObject referer = reference.getReferer();
    GeneratedObject referent = reference.getReferent();
    boolean isRoot = referent.isRoot();
    if (!isRoot) {
      referent.toRoot(); // Temporarily make it a root to ensure it is reachable
    }
    unlink(reference);
    reference(reference.getId(), referer, referent, reference.getType().soften());
    if (!isRoot) {
      referent.toNonRoot(); // Restore the original state
    }
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
    ensureReachable(referer, "The referer object %s is not reachable".formatted(referer.getId()));
    // When the referer is already null, we do not need to set it to null again
    if (!referent.isReachable()) {
      appendComment("The referent is already null (i.e., unreachable).");
    } else {
      appendStatement(referer.getJavaDerefId() + "." + reference.getField() + " = null");
    }

    // Remove the reference from the referents map
    moveReferenceToDead(reference);

    // The reference relationship is changed, we should update the heap
    updateHeap();
  }

  // -========================================================================
  // Oracle Checking and Generation
  // -========================================================================

  // Generate the oracle state check at the end of the main method to ensure that the heap
  // state of the generated Java code after execution matches the oracle state.
  // Note, this is a softer check compared to the one in JavaOracle.
  void appendOracleCheck(@NonNull GGraph oracleState) {
    // Update the heap to ensure the state is up-to-date
    updateHeap();

    GGraph currState = getState();
    // We have to ensure that our state matches the oracle state
    Assert.set(
        currState.equals(oracleState),
        "The current heap state of the JavaInterpreter does not match the oracle state");

    // This implementation should be aligned with JavaOracle
    UnmodifiableGraph state = UnmodifiableGraph.of(currState);

    // Root objects in the oracle should still be roots in the runtime state.
    for (GObject obj : state.getObjects()) {
      if (obj.isRoot()) {
        appendStatement(
            """
            assert %s != null : "The root object with ID %s (in the oracle state) is not found in the runtime state"\
            """
                .formatted(getObject(obj.getId()).getJavaRefId(), obj.getId()));
      }
    }

    // Strongly reachable objects in the oracle should remain strongly reachable.
    for (GID objId : state.getStronglyReachableObjects()) {
      GeneratedObject obj = getObject(objId);
      appendStatement(
          """
          assert %s != null : "The %s-reachable object with ID %s (in the oracle state) is not found in the runtime state"\
          """
              .formatted(obj.getJavaRefId(), GReference.Type.STRONG, obj.getId()));
    }

    // Softly reachable objects in the oracle can be softly reachable or cleared.
    for (GID objId : state.getSoftlyReachableObjects()) {
      GeneratedObject obj = getObject(objId);
      appendStatement(
          """
          if (%s != null) {
              assert %s != null : "The %s-reachable object with ID %s (in the oracle state) is not found in the runtime state";
          }
          """
              .formatted(
                  obj.getJavaRefId(), obj.getJavaDerefId(), GReference.Type.SOFT, obj.getId()));
    }

    // Weakly reachable objects are expected to be cleared after each GC cycle.
    for (GID objId : state.getWeaklyReachableObjects()) {
      GeneratedObject obj = getObject(objId);
      appendStatement(
          """
          assert %s == null : "The %s-reachable object with ID %s (in the oracle state) was found in the runtime state while it shouldn't exist"\
          """
              .formatted(obj.getJavaRefId(), GReference.Type.WEAK, obj.getId()));
    }

    // See the matrix in JavaOracle for the expected relationship between referers and referents.
    for (GReference oracleRef : state.getReferences()) {
      GID oracleReferer = oracleRef.getReferer();
      GID oracleReferent = oracleRef.getReferent();
      GReference.Type oracleRefererReach = state.getReachability(oracleReferer);
      GReference.Type oracleReferentReach = state.getReachability(oracleReferent);
      if (oracleRefererReach == GReference.Type.STRONG
          && oracleReferentReach == GReference.Type.STRONG) {
        appendStatement(
            """
            assert %s != null : "The %s-reachable referer with ID %s (in the oracle state) is not found in the runtime state"\
            """
                .formatted(
                    getObject(oracleReferer).getJavaRefId(),
                    GReference.Type.STRONG,
                    oracleReferer));
        appendStatement(
            """
            assert %s != null : "The %s-reachable referent with ID %s (in the oracle state) is not found in the runtime state"\
            """
                .formatted(
                    getObject(oracleReferent).getJavaRefId(),
                    GReference.Type.STRONG,
                    oracleReferent));
      } else if (oracleReferentReach == GReference.Type.WEAK) {
        appendStatement(
            """
            assert %s == null : "The %s-reachable referent with ID %s (in the oracle state) was found in the runtime state while it shouldn't exist"\
            """
                .formatted(
                    getObject(oracleReferent).getJavaRefId(),
                    GReference.Type.WEAK,
                    oracleReferent));
      } else if (oracleRefererReach == GReference.Type.WEAK) {
        appendStatement(
            """
            assert %s == null : "The %s-reachable referer with ID %s (in the oracle state) was found in the runtime state while it shouldn't exist"\
            """
                .formatted(
                    getObject(oracleReferer).getJavaRefId(), GReference.Type.WEAK, oracleReferer));
      }
    }
  }

  // -========================================================================
  // Helper Methods for Reference and Object Management
  // -========================================================================

  private void moveObjectAndItsReferencesToDead(@NonNull GeneratedObject object) {
    GID objectId = object.getId();
    Assert.set(hasObject(object.getId()), "The object with id %s does not exist", object.getId());
    // Remove all references to the object
    List<GeneratedReference> referencesToRemove = new LinkedList<>();
    for (GID refererId : referents.keySet()) {
      if (refererId.equals(objectId)) {
        referencesToRemove.addAll(referents.get(refererId));
      } else {
        for (GeneratedReference reference : referents.get(refererId)) {
          if (reference.getReferent().getId().equals(objectId)) {
            // Move the reference to dead references
            referencesToRemove.add(reference);
          }
        }
      }
    }
    // Remove all references to the object
    referencesToRemove.forEach(this::moveReferenceToDead);
    // Remove the object itself
    moveObjectToDead(object);
  }

  private GeneratedObject putObject(@NonNull GeneratedObject object) {
    Assert.set(!hasObject(object.getId()), "The object with id %s already exists", object.getId());
    referents.put(object.getId(), new LinkedList<>());
    objects.put(object.getId(), object);
    return object;
  }

  private void moveObjectToDead(@NonNull GeneratedObject object) {
    Assert.set(hasObject(object.getId()), "The object with id %s does not exist", object.getId());
    Assert.set(
        referents.get(object.getId()).isEmpty(), "The object %s has references", object.getId());
    objects.remove(object.getId());
    deadObjects.put(object.getId(), object);
    referents.remove(object.getId());
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
    deadReferences.add(reference);
  }

  private void updateHeap() {
    updateHeap(/*allocator*/ null);
  }

  private void updateHeap(NonRootInPoolAllocator allocator) {
    // Update the state of the heap based on the current objects and references
    GGraph graph = getState(/* clean= */ false);
    if (allocator != null) {
      for (GID objId : allocator.allocatedObjects) {
        GObject obj = graph.getObject(objId);
        obj.setType(GObject.Type.GCROOT); // Object in the pool should be treated as root
      }
    }
    graph.removeUnreachableObjects();
    // Update the reachability of all objects based on the current state
    Map<GID, GReference.Type> reachableObjects = graph.markReachability();
    for (GID objId : objects.keySet()) {
      GeneratedObject obj = objects.get(objId);
      obj.setReachability(reachableObjects.getOrDefault(objId, null));
    }
    if (allocator != null) {
      for (GID objId : allocator.allocatedObjects) {
        GObject obj = graph.getObject(objId);
        obj.setType(GObject.Type.ORDINARY); // Revert it back to avoid smashing the identifiers
      }
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
              genObj.setJavaRefId(GeneratedMainClass.computeFieldName(obj.getId()));
              genObj.setRefToStrongestReferer(null);
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
            genObj.setJavaRefId(
                strongestGenReferer.getJavaDerefId() + "." + strongestGenRef.getField());
            genObj.setRefToStrongestReferer(strongestGenRef.getType());

            visited.add(obj.getId());
          }
        });
  }

  // Ensure the object is reachable with any reachability type
  private void ensureReachable(GeneratedObject object, String message) {
    if (object.getReachability() == null) {
      if (strict) {
        log.error(message);
      } else {
        log.warning(message);
      }
    }
  }

  // Ensure the object is reachable with the expected reachability type
  private void ensureReachable(GeneratedObject object, GReference.Type type, String message) {
    if (object.getReachability() != type) {
      if (strict) {
        log.error(message);
      } else {
        log.warning(message);
      }
    }
  }
}
