package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueuePlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Qualified physical queue layouts. Portable semantics see identities/member sets only. */
public final class QueueProjector {
  private static final int CLASS = 1, FIELD = 2, ELEMENT = 3, STATIC = 8;
  private static final int STATIC_MODIFIER = 8, FINAL_MODIFIER = 16;

  /** Facts are component-wise covered; errors never manufacture an empty member set. */
  public record Result(Map<String, HeapQueue> queues, List<String> problems) {
    public Result {
      queues = Map.copyOf(queues);
      problems = List.copyOf(problems);
    }

    public boolean complete() {
      return problems.isEmpty();
    }
  }

  private record Slot(long owner, long index) {}

  private final HeapSnapshot graph, raw;
  private final ObservationLayout layout;
  private final Map<Integer, HeapSnapshot.ClassInfo> classes = new HashMap<>();
  private final Map<Long, HeapSnapshot.Record> objects = new HashMap<>();
  private final Map<Long, HeapSnapshot.Record> graphObjects = new HashMap<>();
  private final Map<Integer, HeapSnapshot.ClassInfo> graphClasses = new HashMap<>();
  private final Map<Slot, HeapSnapshot.Record> graphReferences = new HashMap<>(),
      graphPrimitives = new HashMap<>();
  private final Map<Long, List<HeapSnapshot.Record>> graphClassEdges = new HashMap<>(),
      classEdges = new HashMap<>(),
      arrayEdges = new HashMap<>(),
      graphArrayEdges = new HashMap<>();
  private final Map<Slot, HeapSnapshot.Record> references = new HashMap<>(),
      primitives = new HashMap<>();
  private final Set<Long> visited = new HashSet<>();
  private Map<Long, WrapperId> wrappers;
  private final String vm;

  private QueueProjector(
      HeapSnapshot graph, HeapSnapshot raw, ObservationLayout layout, String vm) {
    for (var type : graph.classes()) graphClasses.put(type.id(), type);
    for (var record : graph.records()) {
      if (record.kind() == HeapSnapshot.Kind.OBJECT) graphObjects.put(record.a(), record);
      else if (record.kind() == HeapSnapshot.Kind.REFERENCE && record.c() == CLASS)
        graphClassEdges.computeIfAbsent(record.a(), unused -> new ArrayList<>()).add(record);
      else if (record.kind() == HeapSnapshot.Kind.REFERENCE
          && (record.c() == FIELD || record.c() == ELEMENT || record.c() == STATIC)) {
        graphReferences.put(new Slot(record.a(), record.d()), record);
        if (record.c() == ELEMENT)
          graphArrayEdges.computeIfAbsent(record.a(), unused -> new ArrayList<>()).add(record);
      } else if (record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD)
        graphPrimitives.put(new Slot(record.a(), record.b()), record);
    }
    this.graph = graph;
    this.raw = raw;
    this.layout = layout;
    this.vm = vm;
  }

  public static Result project(
      HeapSnapshot graph, QueueCapture capture, ObservationLayout layout, String vm) {
    var problems = new ArrayList<String>();
    var facts = new LinkedHashMap<String, HeapQueue>();
    try {
      var raw = capture.snapshot();
      require(layout.queuePlan() != null, "Queue policy is not selected");
      require(
          raw.run() == graph.run() && raw.checkpoint() == graph.checkpoint(),
          "Queue capture identity mismatch");
      require(
          graph.status() == HeapSnapshot.Status.COMPLETE && graph.jvmtiError() == 0,
          "Global graph walk is partial or failed");
      require(
          raw.jvmtiError() == 0
              && (raw.status() == HeapSnapshot.Status.COMPLETE
                  || raw.status() == HeapSnapshot.Status.RECORD_LIMIT),
          "Queue walk failed");
      boolean completeWalk = raw.status() == HeapSnapshot.Status.COMPLETE;
      if (!completeWalk) problems.add("Queue resource record quota exhausted");
      require(
          raw.gcStartsBefore() == graph.gcStartsBefore()
              && raw.gcFinishesBefore() == graph.gcFinishesBefore()
              && raw.gcStartsAfter() == graph.gcStartsAfter()
              && raw.gcFinishesAfter() == graph.gcFinishesAfter(),
          "Queue/graph walk intervals disagree");
      var decoder = new QueueProjector(graph, raw, layout, vm);
      decoder.index();
      for (var type : graph.classes()) {
        var paired = decoder.type(type.signature());
        require(
            paired.kind() == type.kind() && paired.fields().equals(type.fields()),
            "Graph/resource class metadata disagree");
      }
      var roots = decoder.type(QueuePlan.ROOTS);
      require(
          roots.kind() == HeapSnapshot.ClassKind.ROOTS
              && roots.fields().size() == layout.queuePlan().resources().size(),
          "Queue root metadata mismatch");
      for (var resource : layout.queuePlan().resources()) {
        Integer identity = null;
        Set<WrapperId> members = null;
        try {
          int index = decoder.field(roots, resource.field(), QueuePlan.TYPE, true).index();
          boolean recorded = decoder.references.containsKey(new Slot(-roots.id(), index));
          require(
              recorded || completeWalk && decoder.visited.contains((long) roots.id()),
              "Queue root coverage is unavailable");
          long tag = decoder.reference(-roots.id(), index, STATIC);
          if (tag == 0) {
            facts.put(resource.name(), new HeapQueue(0, Set.of()));
            continue;
          }
          var object = decoder.object(tag);
          var queueType = decoder.type(QueuePlan.TYPE);
          require(object.b() == queueType.id() && object.d() == -1, "Wrong queue resource class");
          var idField = decoder.field(queueType, "idQueue", "I", false);
          require((idField.modifiers() & FINAL_MODIFIER) != 0, "Mutable queue identity field");
          try {
            long value = decoder.primitive(tag, idField.index(), 'I');
            require(
                value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE,
                "Invalid queue identity bits");
            identity = (int) value;
          } catch (IllegalArgumentException missing) {
            problems.add(resource.name() + ": " + missing.getMessage());
          }
          try {
            require(completeWalk, "Queue member path recording is incomplete");
            members =
                vm.equals("hotspot")
                    ? decoder.linked(tag, queueType, resource)
                    : vm.equals("openj9") ? decoder.ring(tag, queueType, resource) : throwUnknown();
          } catch (IllegalArgumentException missing) {
            problems.add(resource.name() + ": " + missing.getMessage());
          }
        } catch (IllegalArgumentException missing) {
          problems.add(resource.name() + ": " + missing.getMessage());
        }
        if (identity != null || members != null)
          facts.put(resource.name(), new HeapQueue(identity, members));
      }
    } catch (IllegalArgumentException malformed) {
      problems.add(malformed.getMessage());
    }
    return new Result(facts, problems);
  }

  private static Set<WrapperId> throwUnknown() {
    throw new IllegalArgumentException("Unknown queue VM adapter");
  }

  private void index() {
    var names = new HashSet<String>();
    for (var type : raw.classes()) {
      require(
          type.id() > 0
              && classes.putIfAbsent(type.id(), type) == null
              && names.add(type.signature()),
          "Duplicate queue class metadata");
      var indices = new HashSet<Integer>();
      var fields = new HashSet<String>();
      for (var field : type.fields())
        require(
            field.index() >= 0 && indices.add(field.index()) && fields.add(field.name()),
            "Ambiguous queue field metadata");
    }
    for (var record : raw.records()) {
      switch (record.kind()) {
        case OBJECT ->
            require(
                record.a() > 0
                    && record.c() > 0
                    && record.b() >= 0
                    && record.b() <= Integer.MAX_VALUE
                    && record.d() >= -1
                    && record.d() <= Integer.MAX_VALUE
                    && objects.putIfAbsent(record.a(), record) == null,
                "Duplicate queue object record");
        case REFERENCE -> {
          if (record.c() == CLASS)
            classEdges.computeIfAbsent(record.a(), unused -> new ArrayList<>()).add(record);
          if (record.c() == FIELD || record.c() == ELEMENT || record.c() == STATIC) {
            require(
                references.putIfAbsent(new Slot(record.a(), record.d()), record) == null,
                "Duplicate queue reference coordinate");
            if (record.c() == ELEMENT)
              arrayEdges.computeIfAbsent(record.a(), unused -> new ArrayList<>()).add(record);
          }
        }
        case PRIMITIVE_FIELD ->
            require(
                primitives.putIfAbsent(new Slot(record.a(), record.b()), record) == null,
                "Duplicate queue primitive coordinate");
        case CLASS_VISIT -> visited.add(record.a());
        case ROOT, ARRAY_VALUES, ARRAY_ELEMENT -> {}
      }
    }
    for (var entry : references.entrySet()) {
      long owner = entry.getKey().owner();
      var record = entry.getValue();
      HeapSnapshot.ClassInfo type =
          owner < 0 && owner >= -Integer.MAX_VALUE
              ? classes.get((int) -owner)
              : objects.containsKey(owner) ? classes.get((int) objects.get(owner).b()) : null;
      if (type == null) continue; // Irrelevant incoming references may have opaque owners.
      if (record.c() == ELEMENT) {
        var object = objects.get(owner);
        require(
            object != null && object.d() >= 0 && record.d() >= 0 && record.d() < object.d(),
            "Queue helper element coordinate is out of bounds");
      } else {
        var field =
            type.fields().stream().filter(candidate -> candidate.index() == record.d()).findFirst();
        require(
            field.isPresent()
                && (field.orElseThrow().descriptor().startsWith("L")
                    || field.orElseThrow().descriptor().startsWith("["))
                && ((field.orElseThrow().modifiers() & STATIC_MODIFIER) != 0)
                    == (record.c() == STATIC),
            "Unknown queue reference field coordinate");
      }
    }
    for (var entry : primitives.entrySet()) {
      long owner = entry.getKey().owner();
      var object = objects.get(owner);
      var type = object == null ? null : classes.get((int) object.b());
      if (type == null) continue;
      var field =
          type.fields().stream()
              .filter(candidate -> candidate.index() == entry.getKey().index())
              .findFirst();
      require(
          field.isPresent()
              && field.orElseThrow().descriptor().length() == 1
              && field.orElseThrow().descriptor().charAt(0) == entry.getValue().c(),
          "Unknown queue primitive field coordinate");
    }
  }

  private HeapSnapshot.ClassInfo type(String signature) {
    return classes.values().stream()
        .filter(type -> type.signature().equals(signature))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Missing queue class " + signature));
  }

  private HeapSnapshot.FieldInfo field(
      HeapSnapshot.ClassInfo type, String name, String descriptor, boolean isStatic) {
    var field =
        type.fields().stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing queue field " + name));
    require(
        field.descriptor().equals(descriptor)
            && ((field.modifiers() & STATIC_MODIFIER) != 0) == isStatic,
        "Queue field kind mismatch: " + name);
    return field;
  }

  private HeapSnapshot.Record object(long tag) {
    var object = objects.get(tag);
    require(object != null, "Missing queue/helper owner");
    // Every selected owner needs a coherent actual class before its fields/length can prove
    // anything. Class-wide capture also has detached helpers, which decoding never selects.
    var type = classes.get((int) object.b());
    if (classEdges.containsKey(tag) || graphClassEdges.containsKey(tag)) {
      require(type != null, "Selected owner has an unknown class");
      checkClassEdges(tag, type.id(), classEdges);
      checkClassEdges(tag, type.id(), graphClassEdges);
    }
    var shared = graphObjects.get(tag);
    if (shared != null) {
      require(
          shared.c() == object.c()
              && shared.d() == object.d()
              && shared.e() == object.e()
              && shared.f() == object.f()
              && shared.g() == object.g(),
          "Selected owner object facts disagree between captures");
      // Ordinary policy intentionally leaves resource/helper classes opaque (class zero).
      if (shared.b() != 0)
        require(
            type != null && type.equals(graphClasses.get((int) shared.b())),
            "Selected owner known class facts disagree between captures");
    }
    return object;
  }

  private long reference(long owner, long index, int kind) {
    if (owner > 0) object(owner);
    var record = references.get(new Slot(owner, index));
    checkSharedReference(owner, index, record);
    if (record == null)
      return 0; // Qualified policy/full walk and observed owner prove physical null.
    require(record.c() == kind && record.b() > 0, "Invalid queue reference");
    return record.b();
  }

  private long primitive(long owner, int index, char type) {
    object(owner);
    var record = primitives.get(new Slot(owner, index));
    require(record != null && record.c() == type, "Missing queue primitive field");
    var shared = graphPrimitives.get(new Slot(owner, index));
    require(
        shared == null || sameSelectedValue(shared, record),
        "Selected owner primitive facts disagree between captures");
    checkKnownOwnerHint(owner, shared);
    checkKnownOwnerHint(owner, record);
    return record.d();
  }

  private void checkSharedReference(long owner, long index, HeapSnapshot.Record actual) {
    var shared = graphReferences.get(new Slot(owner, index));
    require(
        shared == null || sameSelectedValue(shared, actual),
        "Selected owner reference facts disagree between captures");
    checkKnownOwnerHint(owner, shared);
    checkKnownOwnerHint(owner, actual);
  }

  // Ordinary policy suppresses opaque owner class hints. Zero is unknown, never a null target;
  // concrete owner/field/kind/target/value facts must still agree across the same walk.
  private static boolean sameSelectedValue(HeapSnapshot.Record shared, HeapSnapshot.Record actual) {
    return actual != null
        && shared.kind() == actual.kind()
        && shared.a() == actual.a()
        && shared.b() == actual.b()
        && shared.c() == actual.c()
        && shared.d() == actual.d()
        && (shared.e() == 0 || actual.e() == 0 || shared.e() == actual.e())
        && shared.f() == actual.f()
        && shared.g() == actual.g();
  }

  private void checkKnownOwnerHint(long owner, HeapSnapshot.Record record) {
    if (owner <= 0 || record == null || record.e() == 0) return;
    var known = objects.get(owner);
    if (known != null && known.b() > 0)
      require(record.e() == -known.b(), "Selected owner class hint contradicts its actual class");
  }

  private WrapperId member(long tag) {
    var object = object(tag);
    var graphObject = graphObjects.get(tag);
    // Canonical IDs come from the ordinary capture, so the paired physical object must agree
    // before its ID can label a resource edge. A class dictionary alone does not establish this.
    require(object.equals(graphObject), "Graph/resource member object metadata disagree");
    var graphType = graphClasses.get((int) graphObject.b());
    var objectType = classes.get((int) object.b());
    require(
        graphType != null && graphType.equals(objectType),
        "Graph/resource member class metadata disagree");
    checkClassEdges(tag, graphType.id(), graphClassEdges);
    for (var field : objectType.fields()) {
      if ((field.modifiers() & STATIC_MODIFIER) != 0) continue;
      var slot = new Slot(tag, field.index());
      if (field.descriptor().startsWith("L") || field.descriptor().startsWith("["))
        require(
            Objects.equals(graphReferences.get(slot), references.get(slot)),
            "Graph/resource member reference cells disagree");
      else {
        var value = graphPrimitives.get(slot);
        require(
            value != null && value.equals(primitives.get(slot)),
            "Graph/resource member primitive cells disagree or are missing");
      }
    }
    if (wrappers == null) wrappers = SnapshotProjector.wrapperIdentities(graph, layout);
    var id = wrappers.get(tag);
    require(id != null, "Queue contains an unknown actual wrapper");
    require(
        objectType != null
            && (objectType.kind() == HeapSnapshot.ClassKind.SOFT_REFERENCE
                || objectType.kind() == HeapSnapshot.ClassKind.WEAK_REFERENCE),
        "Wrong queue member metadata kind");
    // Preserve known wrong-type/other-queue identities for the oracle to refute, rather than
    // converting a definite membership contradiction into missing observation coverage.
    return id;
  }

  private static void checkClassEdges(
      long owner, int type, Map<Long, List<HeapSnapshot.Record>> edges) {
    for (var edge : edges.getOrDefault(owner, List.of()))
      require(
          edge.b() == -type && (edge.e() == 0 || edge.e() == -type),
          "Selected object class edge contradicts its actual object class");
  }

  private Set<WrapperId> linked(
      long tag, HeapSnapshot.ClassInfo queueType, QueuePlan.Resource resource) {
    var base = type("Ljava/lang/ref/ReferenceQueue;");
    require(visited.contains((long) base.id()), "Queue sentinel owner was not observed");
    long enqueued =
        reference(
            -base.id(),
            field(base, "ENQUEUED", "Ljava/lang/ref/ReferenceQueue;", true).index(),
            STATIC);
    long none =
        reference(
            -base.id(),
            field(base, "NULL", "Ljava/lang/ref/ReferenceQueue;", true).index(),
            STATIC);
    require(
        enqueued > 0 && none > 0 && enqueued != none && tag != enqueued && tag != none,
        "Missing or aliased queue sentinels");
    long length = primitive(tag, field(queueType, "queueLength", "J", false).index(), 'J');
    long head =
        reference(tag, field(queueType, "head", "Ljava/lang/ref/Reference;", false).index(), FIELD);
    require(length >= 0 && length <= raw.records().size(), "Invalid linked queue length");
    var members = new HashSet<WrapperId>();
    var seen = new HashSet<Long>();
    long current = head;
    while (current != 0) {
      require(seen.add(current), "Cyclic queue chain");
      require(members.add(member(current)), "Duplicate queue member identity");
      var type = classes.get((int) object(current).b());
      require(
          reference(
                  current,
                  field(type, "queue", "Ljava/lang/ref/ReferenceQueue;", false).index(),
                  FIELD)
              == enqueued,
          "Queue member lacks actual enqueued sentinel");
      long next =
          reference(
              current, field(type, "next", "Ljava/lang/ref/Reference;", false).index(), FIELD);
      if (next == current) break;
      require(next != 0, "Queue chain lacks self-tail termination");
      current = next;
    }
    require(members.size() == length, "Queue length/chain coverage disagrees");
    return Set.copyOf(members);
  }

  private Set<WrapperId> ring(
      long tag, HeapSnapshot.ClassInfo queueType, QueuePlan.Resource resource) {
    long array =
        reference(
            tag,
            field(queueType, "references", "[Ljava/lang/ref/Reference;", false).index(),
            FIELD);
    long head = primitive(tag, field(queueType, "head", "I", false).index(), 'I');
    long tail = primitive(tag, field(queueType, "tail", "I", false).index(), 'I');
    long empty = primitive(tag, field(queueType, "empty", "Z", false).index(), 'Z');
    require(empty == 0 || empty == 1, "Invalid queue empty flag");
    if (array == 0) {
      require(empty == 1 && head == 0 && tail == 0, "Missing nonempty queue backing array");
      return Set.of();
    }
    var object = object(array);
    var type = classes.get((int) object.b());
    require(
        type != null
            && type.signature().equals("[Ljava/lang/ref/Reference;")
            && type.kind() == HeapSnapshot.ClassKind.REFERENCE_ARRAY
            && object.d() > 0,
        "Wrong queue backing-array metadata");
    long length = object.d();
    require(
        head >= 0 && tail >= 0 && head < length && tail < length,
        "Queue ring indices out of bounds");
    require(empty == 0 || head == tail, "Queue empty/ring indices disagree");
    long count = empty == 1 ? 0 : head == tail ? length : (tail - head + length) % length;
    require(count <= raw.records().size(), "Queue active range exceeds recording coverage");
    // This qualified implementation clears consumed/unused slots. Inspect only this queue's
    // selected storage: class-wide capture also contains unrelated and detached helper arrays.
    for (var edge : graphArrayEdges.getOrDefault(array, List.of()))
      checkSharedReference(array, edge.d(), references.get(new Slot(array, edge.d())));
    for (var edge : arrayEdges.getOrDefault(array, List.of())) {
      require(edge.b() > 0, "Invalid queue backing-array target");
      require(
          (edge.d() - head + length) % length < count,
          "Unexpected non-null inactive queue backing-array slot");
    }
    var members = new HashSet<WrapperId>();
    for (long offset = 0; offset < count; offset++) {
      long value = reference(array, (head + offset) % length, ELEMENT);
      require(value != 0 && members.add(member(value)), "Missing or duplicate active queue slot");
    }
    return Set.copyOf(members);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
