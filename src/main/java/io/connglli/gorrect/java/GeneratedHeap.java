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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;

import javax.lang.model.element.Modifier;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.model.ObjectGraph;
import io.connglli.gorrect.gir.model.visitor.ObjectReversePostorderVisitor;
import io.connglli.gorrect.utility.GAssert;
import lombok.NonNull;

/**
 * @formatter:off
 * GeneratedHeap manages all generated Java objects and their reference relationships. Specifically,
 * it is responsible for generating code for their classes and for the operations on them in the
 * main class. It is also responsible for generating code for waiting for heap dump and exiting the
 * program. For each object, it generates a unique class for them and an unique instance representing
 * the object. The unique class contains one field for each object it has referenced, is referencing,
 * and will reference. These reference fields are either strong, soft, or weak. For those root object,
 * it generates a static field in the main class to reference them as root.
 * @formatter:on
 */
class GeneratedHeap {

    @NonNull
    private final GeneratedMainClass mainClazz;

    @NonNull
    private final Map<GID, GeneratedObject> objects; // All generated objects
    @NonNull
    private final Map<GID, GeneratedObject> deadObjects; // All dead objects, which are not
                                                         // reachable from the root objects

    @NonNull
    private final Map<GID, List<GeneratedReference>> referents; // The referents of each object
    @NonNull
    private final Map<GID, GeneratedReference> deadReferences; // All dead references, which are not
                                                               // reachable from the root objects

    // Object pool is a temporary pool to save all created objects when initializing the
    // heap state or allocating some objects to avoid being garbage collected.
    private static final String OBJECT_POOL_NAME = "object_pool";
    private static final FieldSpec objectPoolField = FieldSpec
            .builder(ClassName.get(Map.class), OBJECT_POOL_NAME, Modifier.PRIVATE, Modifier.STATIC,
                    Modifier.FINAL)
            .initializer("new $T<$T, $T>()", ClassName.get(HashMap.class),
                    ClassName.get(String.class), GeneratedObjectBaseClass.clazzName)
            .build();

    GeneratedHeap() {
        mainClazz = new GeneratedMainClass(this);
        mainClazz.addField(objectPoolField);
        objects = new HashMap<>();
        deadObjects = new HashMap<>();
        referents = new HashMap<>();
        deadReferences = new HashMap<>();
    }

    ObjectGraph getState() {
        ObjectGraph graph = new ObjectGraph();
        for (GeneratedObject obj : objects.values()) {
            graph.addObject(obj.getId(), obj.getType(), obj.getSize());
        }
        for (GeneratedObject referer : objects.values()) {
            for (GeneratedReference reference : referents.get(referer.getId())) {
                graph.addReference(reference.getId(), referer.getId(),
                        reference.getReferent().getId(), reference.getType());
            }
        }
        graph.removeUnreachableObjects();
        return graph;
    }

    String toJavaCode(String directory) {
        String filePath = directory + File.separator + mainClazz.getClazzName() + ".java";
        try (FileOutputStream fout = new FileOutputStream(filePath)) {
            fout.write(mainClazz.toJavaCode().getBytes());
            List<GID> sorted = new LinkedList<>();
            sorted.addAll(objects.keySet());
            sorted.addAll(deadObjects.keySet());
            sorted.sort(Comparator.comparing(GID::toString));
            for (GID objId : sorted) {
                GeneratedObject obj =
                        objects.getOrDefault(objId, deadObjects.getOrDefault(objId, null));
                GAssert.set(obj != null, "The object with id %s does not exist", objId);
                fout.write(obj.getClazz().toJavaCode().getBytes());
            }
            for (GeneratedObject obj : deadObjects.values()) {
                fout.write(obj.getClazz().toJavaCode().getBytes());
            }
            return filePath;
        } catch (IOException e) {
            GAssert.panic("Cannot write to file %s: %s", filePath, e.getMessage());
            return null; // Unreachable, but required by the compiler
        }
    }

    boolean hasObject(@NonNull GID objectId) {
        return objects.containsKey(objectId);
    }

    @NonNull
    GeneratedObject getObject(@NonNull GID objectId) {
        GAssert.set(hasObject(objectId), "The object with id %s does not exist", objectId);
        return objects.get(objectId);
    }

    // -========================================================================
    // Initialization and Setup
    // -========================================================================

    void initialize(ObjectGraph initState) {
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
        GAssert.set(!hasObject(rootId), "The object with id %s already exists", rootId);
        GeneratedObjectClass rootClazz = new GeneratedObjectClass(this, rootId, rootSize);
        // Create a static field in the main class to reference the root object and at the same
        // time, directly instantiate the root object immediately after the main class is loaded.
        String fieldName = GeneratedMainClass.computeFieldName(rootClazz.getId());
        mainClazz
                .addField(FieldSpec
                        .builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC,
                                Modifier.STATIC)
                        .initializer("new $T()", rootClazz.clazzName).build());
        return putObject(new GeneratedObject(this, rootClazz, GObject.Type.GCROOT, fieldName));
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    GeneratedObject allocRootWithoutInitializer(@NonNull GID rootId, long rootSize) {
        GAssert.set(!hasObject(rootId), "The object with id %s already exists", rootId);
        GeneratedObjectClass rootClazz = new GeneratedObjectClass(this, rootId, rootSize);
        // Create a static field in the main class to reference the root object but leave the
        // field as uninstantiated (i.e., initialize to null) when the main class is loaded.
        String fieldName = GeneratedMainClass.computeFieldName(rootClazz.getId());
        mainClazz
                .addField(
                        FieldSpec
                                .builder(GeneratedObjectBaseClass.clazzName, fieldName,
                                        Modifier.PUBLIC, Modifier.STATIC)
                                .initializer("null").build());
        // Instantiate the root object in the main method
        appendStatement("$S = new $T()", fieldName, rootClazz.clazzName);
        return putObject(new GeneratedObject(this, rootClazz, GObject.Type.GCROOT, fieldName));
    }

    NonRootInPoolAllocator getNonRootInPoolAllocator() {
        return new NonRootInPoolAllocator();
    }

    class NonRootInPoolAllocator implements AutoCloseable {
        @SuppressWarnings("UnusedReturnValue")
        public GeneratedObject alloc(@NonNull GID objectId, @NonNull Long objectSize) {
            GAssert.set(!hasObject(objectId), "The object with id %s already exists", objectId);
            GeneratedObjectClass objectClazz =
                    new GeneratedObjectClass(GeneratedHeap.this, objectId, objectSize);
            // Create a temporary object in the pool so that it can be referenced later.
            GeneratedObject object =
                    new GeneratedObject(GeneratedHeap.this, objectClazz, GObject.Type.ORDINARY,
                            "%s.get(%s)".formatted(OBJECT_POOL_NAME, objectClazz.getId()));
            appendStatement("$S.put($S, new $T())", OBJECT_POOL_NAME, objectClazz.getId(),
                    objectClazz.clazzName);
            return putObject(object);
        }

        @Override
        public void close() {
            // We clear the pool to release all the strong references
            appendStatement("$S.clear()", OBJECT_POOL_NAME);
            // The reference relationship is changed, we should update the heap
            updateHeap();
        }
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    GeneratedObject allocNonRootAndReference(@NonNull GID objectId, long objectSize,
            @NonNull GeneratedObject referer, @NonNull GReference.Type referenceType,
            @NonNull GID referenceId) {
        GeneratedClass refererClazz = referer.getClazz();

        GeneratedObjectClass objectClazz = new GeneratedObjectClass(this, objectId, objectSize);
        GeneratedObject object =
                putObject(new GeneratedObject(this, objectClazz, GObject.Type.ORDINARY, ""));

        String fieldName = GeneratedObject.computeFieldName(referenceType, objectClazz.getId());
        switch (referenceType) {
            case STRONG: {
                // Create a field in our class so that we can reference the object
                refererClazz.addField(FieldSpec
                        .builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the object object by our new field
                appendStatement("$S.$S = new $T()", referer.getIdentifier(), fieldName,
                        objectClazz.clazzName);
                break;
            }

            case SOFT: {
                ClassName softRefClazz = ClassName.get(SoftReference.class);
                // Create a field in our class so that we can reference the object
                refererClazz.addField(FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the object object by our new field
                appendStatement("$S.$S = new $T<$T>(new $T)", referer.getIdentifier(), fieldName,
                        softRefClazz, objectClazz.clazzName, objectClazz.clazzName);
                break;
            }

            case WEAK: {
                ClassName weakRefClazz = ClassName.get(WeakReference.class);
                // Create a field in our class so that we can reference the object
                refererClazz.addField(FieldSpec.builder(weakRefClazz, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the object object by our new field
                appendStatement("$S.$S = new $T<$T>($S)", referer.getIdentifier(), fieldName,
                        weakRefClazz, objectClazz.clazzName, objectClazz.clazzName);
                break;
            }

            default:
                GAssert.panic("Cannot reach here");
                break;
        }

        putReference(new GeneratedReference(this, referenceId, fieldName, referer.getId(),
                object.getId(), referenceType));

        // The reference relationship is changed, we should update the heap
        updateHeap();

        return object;
    }

    void freeObject(@NonNull GeneratedObject object) {
        object.getReferents().forEach(referent -> {
            unlink(getReference(object, referent));
        });
        object.getReferers().forEach(referer -> {
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
        GAssert.set(!object.isRoot(), "The object with id %s is already a root object",
                object.getId());
        // Create a static field in the main class to reference the root object but leave the
        // field as uninstantiated (i.e., initialize to null) when the main class is loaded.
        String fieldName = GeneratedMainClass.computeFieldName(object.getId());
        mainClazz
                .addField(
                        FieldSpec
                                .builder(GeneratedObjectBaseClass.clazzName, fieldName,
                                        Modifier.PUBLIC, Modifier.STATIC)
                                .initializer("null").build());
        // Transfer the ownership of the object to the main class
        appendStatement("$S = $S", fieldName, object.getIdentifier());
        object.setType(GObject.Type.GCROOT);
        // The object turns to a root object, we should update the heap globally
        updateHeap();
    }

    void makeNonRoot(@NonNull GeneratedObject root) {
        GAssert.set(root.isRoot(), "The object with id %s is not yet a root object", root.getId());
        String fieldName = GeneratedMainClass.computeFieldName(root.getId());
        GAssert.set(mainClazz.hasField(fieldName),
                "Cannot remove root reference to %s, as it does not exist", fieldName);
        appendStatement("$S = null", fieldName);
        root.setType(GObject.Type.ORDINARY);
        // The object turns to a non-root object, we should update the heap globally
        updateHeap();
    }

    // -========================================================================
    // Reference Addition and Deletion
    // -========================================================================

    List<GeneratedObject> getReferers(@NonNull GeneratedObject referent) {
        GAssert.set(hasObject(referent.getId()), "The referent with id %s does not exist",
                referent.getId());
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
        GAssert.set(hasObject(referer.getId()), "The referer with id %s does not exist",
                referer.getId());
        return referents.get(referer.getId()).stream().map(GeneratedReference::getReferent)
                .collect(Collectors.toList());
    }

    List<GeneratedReference> getReferences(@NonNull GeneratedObject referer) {
        GAssert.set(hasObject(referer.getId()), "The referer with id %s does not exist",
                referer.getId());
        return referents.get(referer.getId());
    }

    boolean hasReference(@NonNull GeneratedObject referer, @NonNull GeneratedObject referent) {
        GAssert.set(hasObject(referer.getId()), "The referer with id %s does not exist",
                referer.getId());
        return referents.get(referer.getId()).stream()
                .anyMatch(ref -> ref.getReferent().getId().equals(referent.getId()));
    }

    @NonNull
    GeneratedReference getReference(@NonNull GeneratedObject referer,
            @NonNull GeneratedObject referent) {
        GAssert.set(hasObject(referer.getId()), "The referer with id %s does not exist",
                referer.getId());
        return referents.get(referer.getId()).stream()
                .filter(ref -> ref.getReferent().getId().equals(referent.getId())).findFirst()
                .orElseGet(() -> {
                    GAssert.panic("The reference from %s to %s does not exist", referer.getId(),
                            referent.getId());
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
        GAssert.panic("The reference with id %s does not exist", referenceId);
        return null; // Unreachable, but required by the compiler
    }

    @NonNull
    GeneratedReference reference(@NonNull GID referenceId, @NonNull GeneratedObject referer,
            @NonNull GeneratedObject referent, @NonNull GReference.Type referenceType) {
        String fieldName = GeneratedObject.computeFieldName(referenceType, referent.getId());
        GeneratedObjectClass refererClazz = referer.getClazz();

        switch (referenceType) {
            case STRONG: {
                // Create a field in our class so that we can reference the referent
                refererClazz.addField(FieldSpec
                        .builder(GeneratedObjectBaseClass.clazzName, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the referent object by our new field
                appendStatement("$S.$S = $S", referer.getIdentifier(), fieldName,
                        referent.getIdentifier());
                break;
            }

            case SOFT: {
                ClassName softRefClazz = ClassName.get(SoftReference.class);
                // Create a field in our class so that we can reference the referent
                refererClazz.addField(FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the referent object by our new field
                appendStatement("$S.$S = new $T<$T>($S)", referer.getIdentifier(), fieldName,
                        softRefClazz, referent.getClazz().clazzName, referent.getIdentifier());
                break;
            }

            case WEAK: {
                ClassName softRefClazz = ClassName.get(WeakReference.class);
                // Create a field in our class so that we can reference the referent
                refererClazz.addField(FieldSpec.builder(softRefClazz, fieldName, Modifier.PUBLIC)
                        .initializer("null").build());
                // Reference the referent object by our new field
                appendStatement("$S.$S = new $T<$T>($S)", referer.getIdentifier(), fieldName,
                        softRefClazz, referent.getClazz().clazzName, referent.getIdentifier());
                break;
            }

            default:
                GAssert.panic("Cannot reach here");
                break;
        }

        // The reference relationship is changed, we should update the heap
        GeneratedReference reference = putReference(new GeneratedReference(this, referenceId,
                fieldName, referer.getId(), referent.getId(), referenceType));
        updateHeap();

        return reference;
    }

    void redirect(GeneratedReference reference, GeneratedObject newReferent) {
        GeneratedObject referer = reference.getReferer();
        GeneratedObject referent = reference.getReferent();

        String fieldName = reference.getField();
        GAssert.set(referer.getClazz().hasField(fieldName),
                "Cannot replace reference's referent %s to %s, as it does not exist",
                reference.getReferent().getId(), newReferent.getId());

        appendStatement("$S = $S", referent.getIdentifier(), newReferent.getIdentifier());
        reference.setReferent(newReferent);

        // The reference relationship is changed, we should update the heap
        updateHeap();
    }

    void unlink(@NonNull GeneratedReference reference) {
        GeneratedObject referer = reference.getReferer();
        GeneratedObject referent = reference.getReferent();
        String fieldName = reference.getField();
        GAssert.set(referer.getClazz().hasField(fieldName),
                "Cannot remove reference to %s, as it does not exist", fieldName);
        appendStatement("$S = null", referent.getIdentifier());

        // Remove the reference from the referents map
        moveReferenceToDead(reference);

        // The reference relationship is changed, we should update the heap
        updateHeap();
    }

    // -========================================================================
    // Helper Methods for Reference and Object Management
    // -========================================================================

    private GeneratedObject putObject(@NonNull GeneratedObject object) {
        GAssert.set(!hasObject(object.getId()), "The object with id %s already exists",
                object.getId());
        referents.put(object.getId(), new LinkedList<>());
        objects.put(object.getId(), object);
        return object;
    }

    private void moveObjectToDead(@NonNull GeneratedObject object) {
        GAssert.set(hasObject(object.getId()), "The object with id %s does not exist",
                object.getId());
        objects.remove(object.getId());
        deadObjects.put(object.getId(), object);
    }

    private GeneratedReference putReference(@NonNull GeneratedReference reference) {
        GeneratedObject referer = reference.getReferer();
        GeneratedObject referent = reference.getReferent();
        GID refererId = referer.getId();
        GID referentId = referent.getId();
        GAssert.set(!hasReference(referer, referent), "The reference from %s to %s already exists",
                refererId, referentId);
        referents.get(refererId).add(reference);
        return reference;
    }

    private void moveReferenceToDead(@NonNull GeneratedReference reference) {
        GID refererId = reference.getReferer().getId();
        GAssert.set(referents.containsKey(refererId), "The referer with id %s does not exist",
                refererId);
        GAssert.set(referents.get(refererId).contains(reference),
                "The reference %s does not exist in the referer %s", reference.getId(), refererId);
        referents.get(refererId).remove(reference);
        deadReferences.put(reference.getId(), reference);
    }

    private void updateHeap() {
        ObjectGraph graph = getState();
        // Update the identifiers of all objects based on the strongest reference
        graph.accept(new ObjectReversePostorderVisitor() {
            private final Set<GID> visited = new HashSet<>();

            @Override
            public void visit(@NonNull ObjectGraph graph, @NonNull GObject obj) {
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

                GAssert.set(strongestRef != null,
                        "The object %s has no referers, which should not happen", obj.getId());

                // We update the reference according to the strongest reference.
                GeneratedObject strongestGenReferer = getObject(strongestRef.getReferer());
                GeneratedReference strongestGenRef = getReference(strongestRef.getId());
                genObj.setIdentifier(
                        strongestGenReferer.getIdentifier() + "." + strongestGenRef.getField());

                visited.add(obj.getId());
            }
        });
    }

    private void appendStatement(@NonNull String stmtFormat, Object... stmtArgs) {
        mainClazz.addStatement(stmtFormat, stmtArgs);
    }
}
