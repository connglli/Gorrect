package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Projects raw evidence without reconstructing values from the oracle. */
public final class SnapshotProjector {
  private static final long CLASS = 1,
      FIELD = 2,
      ARRAY_ELEMENT = 3,
      STATIC_FIELD = 8,
      JNI_GLOBAL = 21,
      STACK_LOCAL = 24;
  private static final long INT = 73, LONG = 74;

  /**
   * Capture projection and its coverage diagnostics. Empty graph means unusable coverage rather
   * than an observed empty heap. Binding profiles additionally require validated physical origins
   * for every non-null binding; named-root capture alone supplies no stack coverage.
   */
  public record Projection(Optional<Graph> graph, List<String> problems) {
    public Projection {
      problems = List.copyOf(problems);
    }

    /**
     * Compares deterministic strong-reference obligations when a usable graph is present. Missing
     * projection or incomplete binding coverage yields INCONCLUSIVE; neither case is treated as a
     * missing-object mismatch. Invalid expected strong graphs raise IllegalArgumentException.
     */
    public ExactVerifier.Result verify(Graph expected) {
      if (graph.isEmpty())
        return new ExactVerifier.Result(ExactVerifier.Verdict.INCONCLUSIVE, problems);
      if (!graph.orElseThrow().handles().keySet().equals(expected.handles().keySet()))
        return new ExactVerifier.Result(
            ExactVerifier.Verdict.INCONCLUSIVE,
            List.of("Stack roots are not mapped to GIR bindings by this profile"));
      return ExactVerifier.verify(expected, graph.orElseThrow(), true);
    }
  }

  private record Slot(long owner, long index) {}

  private record LocalSlot(long method, int slot) {}

  private record LocalName(String method, String descriptor, String name, String type) {}

  private record Frame(long method, long depth) {}

  private record Resolved(
      Shape shape,
      List<HeapSnapshot.FieldInfo> references,
      List<HeapSnapshot.FieldInfo> data,
      HeapSnapshot.FieldInfo worker,
      HeapSnapshot.FieldInfo site,
      HeapSnapshot.FieldInfo occurrence) {}

  private SnapshotProjector() {}

  /**
   * Returns a named-root graph only when capture coverage and layout evidence suffice. Missing,
   * conflicting, or unmapped evidence produces diagnostics and no graph, not a validation mismatch.
   */
  public static Projection project(HeapSnapshot snapshot, ObservationLayout layout) {
    return project(snapshot, layout, null);
  }

  /** Maps explicit presence and raw stack origins without consulting expected identities. */
  public static Projection project(
      HeapSnapshot snapshot, ObservationLayout layout, BindingEvidence bindings) {
    try {
      var decoder = new Decoder(snapshot, layout);
      var graph = decoder.decode();
      var handles =
          bindings == null ? Map.<Integer, Value.Reference>of() : decoder.bindings(bindings);
      return new Projection(
          Optional.of(new Graph(graph.objects(), graph.roots(), handles)), List.of());
    } catch (IllegalArgumentException exception) {
      return new Projection(Optional.empty(), List.of(exception.getMessage()));
    }
  }

  private static final class Decoder {
    private final HeapSnapshot snapshot;
    private final ObservationLayout layout;
    private final Map<Integer, HeapSnapshot.ClassInfo> classes = new HashMap<>();
    private final Map<Integer, Resolved> objectTypes = new HashMap<>();
    private final Map<String, Shape> arrays = new HashMap<>();
    private final Map<Long, HeapSnapshot.ArrayIdentity> arrayIds = new HashMap<>();
    private final Map<Long, HeapSnapshot.Record> objects = new HashMap<>();
    private final Map<Slot, HeapSnapshot.Record> primitives = new HashMap<>();
    private final Map<Slot, HeapSnapshot.Record> references = new HashMap<>();
    private final Map<Long, HeapSnapshot.Record> arrayValues = new HashMap<>();
    private final Map<Slot, HeapSnapshot.Record> elements = new HashMap<>();
    private final Set<Long> visitedClasses = new HashSet<>();
    private final Map<Long, ObjectId> identities = new HashMap<>();
    private final Map<Long, Value.Reference> wrappers = new HashMap<>();

    Decoder(HeapSnapshot snapshot, ObservationLayout layout) {
      this.snapshot = snapshot;
      this.layout = layout;
    }

    Graph decode() {
      resolveLayouts();
      indexRecords();
      resolveObjects();
      resolveWrappers();
      validateFieldRecords();
      return buildGraph();
    }

    private Map<Integer, Value.Reference> bindings(BindingEvidence evidence) {
      require(
          evidence.run() == snapshot.run() && evidence.checkpoint() == snapshot.checkpoint(),
          "Binding capture identity mismatch");
      require(
          evidence.worker() == 0 && evidence.activation() == 1, "Unsupported binding activation");
      if (evidence.bindings().isEmpty()) return Map.of();
      var declarations = new HashMap<Integer, ObservationLayout.LocalBinding>();
      for (var binding : layout.bindings())
        require(
            declarations.putIfAbsent(binding.declaration(), binding) == null,
            "Duplicate binding layout");
      var methods = new HashMap<Long, HeapSnapshot.MethodInfo>();
      for (var method : snapshot.methods()) {
        require(methods.putIfAbsent(method.token(), method) == null, "Duplicate method token");
        var locals = new HashSet<HeapSnapshot.LocalVariable>();
        for (var local : method.locals())
          require(
              local.start() >= 0
                  && local.length() >= 0
                  && local.slot() >= 0
                  && local.slot() <= 65_535
                  && locals.add(local),
              "Invalid or duplicate local metadata");
      }

      var origins = bindingOrigins(evidence, methods);
      var result = new HashMap<Integer, Value.Reference>();
      for (var binding : evidence.bindings()) {
        var declaration = declarations.get(binding.declaration());
        require(
            declaration != null && declaration.worker() == evidence.worker(),
            "Unknown binding declaration");
        var key =
            new LocalName(
                declaration.method(),
                declaration.descriptor(),
                declaration.variable(),
                declaration.type());
        var matches = origins.getOrDefault(key, Set.of());
        if (!binding.present()) {
          require(matches.isEmpty(), "Nullness conflicts with a physical binding root");
          result.put(binding.declaration(), Value.Reference.NULL);
          continue;
        }
        require(
            (snapshot.capabilities() & HeapSnapshot.LOCAL_VARIABLES) != 0,
            "Local-variable metadata is unavailable");
        require(
            matches.size() == 1,
            "Missing or ambiguous physical root for binding " + binding.declaration());
        ObjectId id = identities.get(matches.iterator().next().a());
        require(id != null, "Unmapped binding target");
        result.put(binding.declaration(), Value.Reference.strong(id));
      }
      return result;
    }

    private Map<LocalName, Set<HeapSnapshot.Record>> bindingOrigins(
        BindingEvidence evidence, Map<Long, HeapSnapshot.MethodInfo> methods) {
      var variables = new HashMap<LocalSlot, List<HeapSnapshot.LocalVariable>>();
      for (var method : methods.values())
        for (var local : method.locals())
          variables
              .computeIfAbsent(
                  new LocalSlot(method.token(), local.slot()), key -> new ArrayList<>())
              .add(local);

      var result = new HashMap<LocalName, Set<HeapSnapshot.Record>>();
      var slots = new HashMap<LocalSlot, HeapSnapshot.Record>();
      Frame frame = null;
      long location = -1;
      for (var root : snapshot.records()) {
        if (root.kind() != HeapSnapshot.Kind.ROOT
            || root.b() != STACK_LOCAL
            || root.c() != evidence.thread()
            || root.d() < 0
            || root.e() < 0
            || root.e() > 65_535
            || root.g() < 0) continue;
        var method = methods.get(root.f());
        if (method == null || !method.owner().equals("LCase;") || !method.name().equals("execute"))
          continue;
        var current = new Frame(root.f(), root.d());
        if (frame == null) {
          frame = current;
          location = root.g();
        }
        require(
            frame.equals(current) && location == root.g(), "Ambiguous generated-method activation");
        var slot = new LocalSlot(root.f(), (int) root.e());
        var previous = slots.putIfAbsent(slot, root);
        require(
            previous == null || previous.equals(root), "Conflicting physical stack-slot evidence");
        if (previous != null) continue;

        // The generated caller invokes execute once; multiple frames cannot share activation 1.
        // Index once per physical slot, rather than scanning every root for every binding.
        boolean found = false;
        for (var local : variables.getOrDefault(slot, List.of())) {
          if (root.g() < local.start() || root.g() - local.start() >= local.length()) continue;
          require(!found, "Overlapping local-variable scopes for stack slot");
          found = true;
          var key =
              new LocalName(method.name(), method.descriptor(), local.name(), local.descriptor());
          result.computeIfAbsent(key, ignored -> new HashSet<>()).add(root);
        }
      }
      return result;
    }

    private void resolveLayouts() {
      require(
          snapshot.status() == HeapSnapshot.Status.COMPLETE && snapshot.jvmtiError() == 0,
          "Heap capture is incomplete: " + snapshot.status());
      require(
          (snapshot.capabilities() & HeapSnapshot.TAGGING) != 0,
          "Object tagging evidence is unavailable");
      require(
          layout.references().isEmpty()
              || (snapshot.capabilities() & HeapSnapshot.REFERENCE_FIELDS) != 0,
          "Non-strengthening reference-field capture is unavailable");

      // Resolve logical field roles against actual VM metadata, never declaration-order guesses.
      for (var type : snapshot.classes()) {
        require(
            type.id() > 0 && classes.putIfAbsent(type.id(), type) == null,
            "Duplicate or invalid class ID");
        var indices = new HashSet<Integer>();
        var names = new HashSet<String>();
        for (var field : type.fields())
          require(
              field.index() >= 0 && indices.add(field.index()) && names.add(field.name()),
              "Duplicate or invalid field metadata");
      }

      for (var object : layout.objects()) {
        var type = type(object.signature());
        require(
            type.kind() == HeapSnapshot.ClassKind.OBJECT
                && object.shape().kind() == Shape.Kind.OBJECT,
            "Object layout kind mismatch");
        require(
            object.references().size() == object.shape().references()
                && object.data().size() == object.shape().data(),
            "Logical slot count mismatch");
        var expected = new HashSet<String>();
        var refs = fields(type, object.references(), true, expected);
        var data = fields(type, object.data(), false, expected);
        var worker = identityField(type, object.worker(), "I", expected);
        var site = identityField(type, object.site(), "I", expected);
        var occurrence = identityField(type, object.occurrence(), "J", expected);
        require(
            expected.size() == type.fields().size(), "Unclassified fields in " + type.signature());
        require(
            objectTypes.putIfAbsent(
                    type.id(), new Resolved(object.shape(), refs, data, worker, site, occurrence))
                == null,
            "Duplicate object layout");
      }

      for (var shape : layout.arrays()) {
        require(
            shape.kind() != Shape.Kind.OBJECT && arrays.putIfAbsent(shape.name(), shape) == null,
            "Duplicate or invalid array layout");
      }

      for (var identity : snapshot.arrays()) {
        require(
            identity.tag() > 0 && arrayIds.putIfAbsent(identity.tag(), identity) == null,
            "Duplicate array tag");
        objectId(identity.worker(), identity.site(), identity.occurrence());
      }
    }

    private void indexRecords() {
      for (var record : snapshot.records()) {
        switch (record.kind()) {
          case OBJECT ->
              require(
                  record.a() > 0
                      && record.c() > 0
                      && objects.putIfAbsent(record.a(), record) == null,
                  "Duplicate or invalid object record");
          case REFERENCE -> {
            if (record.c() == FIELD || record.c() == ARRAY_ELEMENT || record.c() == STATIC_FIELD)
              put(references, new Slot(record.a(), record.d()), record);
          }
          case PRIMITIVE_FIELD -> put(primitives, new Slot(record.a(), record.b()), record);
          case ARRAY_VALUES -> put(arrayValues, record.a(), record);
          case ARRAY_ELEMENT -> put(elements, new Slot(record.a(), record.b()), record);
          case CLASS_VISIT -> visitedClasses.add(record.a());
          case ROOT -> {
            /* Retained in the raw snapshot for root-origin diagnostics. */
          }
        }
      }

      require(
          snapshot.records().stream()
              .noneMatch(
                  record -> record.kind() == HeapSnapshot.Kind.ROOT && record.b() == JNI_GLOBAL),
          "Unexpected JNI global root to a workload object");
    }

    private void resolveObjects() {
      // Resolve all physical tags before linking edges, preserving cycles and shared targets.
      var used = new HashSet<ObjectId>();
      for (var entry : objects.entrySet()) {
        long tag = entry.getKey();
        var record = entry.getValue();
        var type = classes.get(integer(record.b()));
        // Reference bookkeeping can lead to opaque VM queue objects. Logical fields still require
        // mapped node or wrapper targets; opaque records never become guessed graph objects.
        if (type == null || isWrapper(type)) continue;
        ObjectId id;
        if (type.kind() == HeapSnapshot.ClassKind.OBJECT) {
          var resolved = objectTypes.get(type.id());
          require(resolved != null && record.d() == -1, "Unmapped object layout at tag " + tag);
          id =
              objectId(
                  primitive(tag, resolved.worker(), INT),
                  primitive(tag, resolved.site(), INT),
                  primitive(tag, resolved.occurrence(), LONG));
        } else {
          var binding = arrayIds.get(tag);
          require(binding != null, "Array has no logical identity binding at tag " + tag);
          var shape = arrays.get(binding.shape());
          require(shape != null, "Unknown array shape " + binding.shape());
          boolean reference = shape.kind() == Shape.Kind.REFERENCE_ARRAY;
          require(
              type.kind()
                  == (reference
                      ? HeapSnapshot.ClassKind.REFERENCE_ARRAY
                      : HeapSnapshot.ClassKind.DATA_ARRAY),
              "Array kind mismatch");
          require(
              record.d() == (reference ? shape.references() : shape.data()),
              "Array length mismatch");
          id = objectId(binding.worker(), binding.site(), binding.occurrence());
        }
        require(used.add(id), "Duplicate logical object identity " + id);
        identities.put(tag, id);
      }
    }

    private void resolveWrappers() {
      for (var layout : layout.references()) {
        var type = type(layout.signature());
        require(
            isWrapper(type)
                && (type.kind() == HeapSnapshot.ClassKind.SOFT_REFERENCE)
                    == (layout.kind() == Value.ReferenceKind.SOFT),
            "Wrapper class kind mismatch");
        var roles = new HashSet<String>();
        var worker = identityField(type, layout.worker(), "I", roles);
        var site = identityField(type, layout.site(), "I", roles);
        var occurrence = identityField(type, layout.occurrence(), "J", roles);
        var slot = identityField(type, layout.slot(), "I", roles);
        var referent = field(type, layout.referent(), roles);
        require(
            !Modifier.isStatic(referent.modifiers())
                && referent.descriptor().equals("Ljava/lang/Object;"),
            "Invalid referent metadata");
        for (var entry : objects.entrySet()) {
          long tag = entry.getKey();
          if (entry.getValue().b() != type.id()) continue;
          var id =
              new WrapperId(
                  integer(primitive(tag, worker, INT)),
                  integer(primitive(tag, site, INT)),
                  primitive(tag, occurrence, LONG),
                  integer(primitive(tag, slot, INT)));
          var edge = references.get(new Slot(tag, referent.index()));
          ObjectId target = edge == null ? null : identities.get(edge.b());
          require(edge == null || target != null, "Unmapped non-null referent");
          wrappers.put(tag, new Value.Reference(layout.kind(), target, id));
        }
      }
      require(
          wrappers.values().stream().map(Value.Reference::wrapper).distinct().count()
              == wrappers.size(),
          "Duplicate wrapper incarnation");
    }

    private Graph buildGraph() {
      var heap = new HashMap<ObjectId, HeapObject>();
      for (var entry : objects.entrySet()) {
        long tag = entry.getKey();
        var type = classes.get(integer(entry.getValue().b()));
        if (type == null || isWrapper(type)) continue;
        Shape shape;
        var refs = new ArrayList<Value.Reference>();
        var data = new ArrayList<Long>();
        if (type.kind() == HeapSnapshot.ClassKind.OBJECT) {
          var resolved = objectTypes.get(type.id());
          shape = resolved.shape();
          for (var field : resolved.references()) refs.add(reference(tag, field.index(), FIELD));
          for (var field : resolved.data()) data.add(primitive(tag, field, LONG));
        } else {
          shape = arrays.get(arrayIds.get(tag).shape());
          if (shape.kind() == Shape.Kind.REFERENCE_ARRAY) {
            for (int index = 0; index < shape.references(); index++)
              refs.add(reference(tag, index, ARRAY_ELEMENT));
            for (var slot : references.keySet())
              if (slot.owner() == tag)
                require(
                    slot.index() >= 0 && slot.index() < shape.references(),
                    "Reference-array index outside length");
          } else {
            var marker = arrayValues.get(tag);
            require(
                marker != null && marker.b() == shape.data() && marker.c() == LONG,
                "Missing or invalid primitive-array marker");
            for (int index = 0; index < shape.data(); index++) {
              var value = elements.get(new Slot(tag, index));
              require(value != null && value.c() == LONG, "Missing or invalid long-array element");
              data.add(value.d());
            }
            for (var slot : elements.keySet())
              if (slot.owner() == tag)
                require(
                    slot.index() >= 0 && slot.index() < shape.data(),
                    "Long-array index outside length");
          }
        }
        heap.put(identities.get(tag), new HeapObject(shape, refs, data));
      }

      // An unvisited holder cannot establish null roots merely by lacking reference records.
      var rootType = type(layout.roots().signature());
      require(
          rootType.kind() == HeapSnapshot.ClassKind.ROOTS
              && visitedClasses.contains((long) rootType.id()),
          "Named-root class was not visited");

      var roots = new HashMap<String, Value.Reference>();
      for (var field : rootType.fields()) {
        require(Modifier.isStatic(field.modifiers()), "Root holder has instance fields");
        if (isReference(field.descriptor())) {
          String name = layout.roots().fields().get(field.name());
          require(
              name != null && !roots.containsKey(name),
              "Unmapped or duplicate named root " + field.name());
          roots.put(name, reference(-rootType.id(), field.index(), STATIC_FIELD));
        }
      }

      require(roots.size() == layout.roots().fields().size(), "Declared root field is missing");

      return new Graph(heap, roots, Map.of());
    }

    private HeapSnapshot.ClassInfo type(String signature) {
      var matches =
          classes.values().stream().filter(type -> type.signature().equals(signature)).toList();
      require(matches.size() == 1, "Missing or ambiguous class " + signature);
      return matches.get(0);
    }

    private void validateFieldRecords() {
      for (var record : snapshot.records()) {
        if (record.kind() != HeapSnapshot.Kind.REFERENCE
            && record.kind() != HeapSnapshot.Kind.PRIMITIVE_FIELD) continue;
        long owner = record.a();
        HeapSnapshot.ClassInfo type = null;
        if (owner < 0) type = classes.get(integer(-owner));
        else if (objects.containsKey(owner)) type = classes.get(integer(objects.get(owner).b()));
        // Foreign incoming references are evidence about origins, not logical object fields.
        if (type == null) continue;
        long index = record.kind() == HeapSnapshot.Kind.REFERENCE ? record.d() : record.b();
        if (record.kind() == HeapSnapshot.Kind.REFERENCE && owner > 0) {
          if (record.c() == CLASS) {
            require(record.b() == -type.id(), "Object-to-class reference mismatch");
            continue;
          }
          if (type.kind() == HeapSnapshot.ClassKind.REFERENCE_ARRAY) {
            require(record.c() == ARRAY_ELEMENT, "Unexpected reference-array edge kind");
            continue;
          }
          require(
              (type.kind() == HeapSnapshot.ClassKind.OBJECT || isWrapper(type))
                  && record.c() == FIELD,
              "Unexpected object edge kind");
        }
        if (record.kind() == HeapSnapshot.Kind.REFERENCE
            && record.c() != FIELD
            && record.c() != STATIC_FIELD) continue;
        long fieldIndex = index;
        var field =
            type.fields().stream()
                .filter(value -> value.index() == fieldIndex)
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("Unmapped field index " + fieldIndex));
        require(Modifier.isStatic(field.modifiers()) == (owner < 0), "Field storage kind mismatch");
        if (record.kind() == HeapSnapshot.Kind.REFERENCE)
          require(isReference(field.descriptor()), "Reference recorded for primitive field");
        else require(!isReference(field.descriptor()), "Primitive recorded for reference field");
      }
    }

    private List<HeapSnapshot.FieldInfo> fields(
        HeapSnapshot.ClassInfo type, List<String> names, boolean reference, Set<String> expected) {
      var result = new ArrayList<HeapSnapshot.FieldInfo>();
      for (String name : names) {
        var field = field(type, name, expected);
        require(
            !Modifier.isStatic(field.modifiers())
                && (reference ? isReference(field.descriptor()) : field.descriptor().equals("J")),
            "Wrong field type or modifier: " + name);
        result.add(field);
      }
      return result;
    }

    private HeapSnapshot.FieldInfo identityField(
        HeapSnapshot.ClassInfo type, String name, String descriptor, Set<String> expected) {
      var field = field(type, name, expected);
      require(
          !Modifier.isStatic(field.modifiers())
              && Modifier.isFinal(field.modifiers())
              && field.descriptor().equals(descriptor),
          "Invalid identity field " + name);
      return field;
    }

    private HeapSnapshot.FieldInfo field(
        HeapSnapshot.ClassInfo type, String name, Set<String> expected) {
      require(expected.add(name), "Overlapping field roles: " + name);
      return type.fields().stream()
          .filter(field -> field.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Missing field " + name));
    }

    private long primitive(long tag, HeapSnapshot.FieldInfo field, long type) {
      // Zero is a value too: missing primitive evidence must never become a default value.
      var record = primitives.get(new Slot(tag, field.index()));
      require(
          record != null && record.c() == type,
          "Missing or mistyped primitive field " + field.name());
      return record.d();
    }

    private Value.Reference reference(long owner, long index, long kind) {
      var record = references.get(new Slot(owner, index));
      // JVMTI omits null edges. Callers establish a complete walk, a visited owner, and a valid
      // slot.
      if (record == null) return Value.Reference.NULL;
      require(record.c() == kind, "Reference kind mismatch");
      var id = identities.get(record.b());
      var wrapper = wrappers.get(record.b());
      if (wrapper != null) return wrapper;
      require(id != null, "Unmapped non-null reference target " + record.b());
      return Value.Reference.strong(id);
    }
  }

  private static boolean isWrapper(HeapSnapshot.ClassInfo type) {
    return type.kind() == HeapSnapshot.ClassKind.SOFT_REFERENCE
        || type.kind() == HeapSnapshot.ClassKind.WEAK_REFERENCE;
  }

  private static boolean isReference(String descriptor) {
    return descriptor.startsWith("L") || descriptor.startsWith("[");
  }

  private static int integer(long value) {
    require(
        value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, "Integer metadata outside range");
    return (int) value;
  }

  private static ObjectId objectId(long worker, long site, long occurrence) {
    return new ObjectId(integer(worker), integer(site), occurrence);
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }

  private static <K> void put(
      Map<K, HeapSnapshot.Record> records, K key, HeapSnapshot.Record record) {
    var previous = records.putIfAbsent(key, record);
    // Some VMs deliver primitive values once per incoming reference. Retain all raw records,
    // accepting repeated evidence only when every captured bit agrees.
    require(previous == null || previous.equals(record), "Conflicting field or element records");
  }
}
