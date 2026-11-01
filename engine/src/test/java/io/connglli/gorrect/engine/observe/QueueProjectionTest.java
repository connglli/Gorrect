package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.protocol.QueuePlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueueProjectionTest {
  private record Fixture(HeapSnapshot graph, HeapSnapshot resources, ObservationLayout layout) {}

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void actualPathsRecoverMembershipIndependentlyOfRecordOrder(String vm) {
    var f = fixture(vm);
    for (boolean reverse : List.of(false, true)) {
      var records = new ArrayList<>(f.resources().records());
      if (reverse) Collections.reverse(records);
      var result =
          QueueProjector.project(
              f.graph(),
              new QueueCapture(copy(f.resources(), records, HeapSnapshot.Status.COMPLETE)),
              f.layout(),
              vm);
      assertTrue(result.complete(), result.problems().toString());
      assertEquals(
          new HeapQueue(1, Set.of(new WrapperId(0, 1, 1, -1))), result.queues().get("ready"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void missingMemberPathRetainsWrongIdentityWithoutGuessingEmptyMembership(String vm) {
    var f = fixture(vm);
    var records = new ArrayList<>(f.resources().records());
    records.replaceAll(
        record ->
            record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                    && record.a() == 100
                    && record.b() == 0
                ? primitive(100, 0, 'I', 9)
                : record);
    records.removeIf(
        record ->
            record.kind() == HeapSnapshot.Kind.REFERENCE
                && (vm.equals("hotspot")
                    ? record.a() == 20 && record.d() == 6
                    : record.a() == 300));
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(copy(f.resources(), records, HeapSnapshot.Status.COMPLETE)),
            f.layout(),
            vm);
    assertFalse(result.complete());
    assertEquals(new HeapQueue(9, null), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void certifiedNullRootIsDifferentFromAnUnobservedRoot(String vm) {
    var f = fixture(vm);
    var records = new ArrayList<>(f.resources().records());
    records.removeIf(record -> record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == -3);
    var absent =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(copy(f.resources(), records, HeapSnapshot.Status.COMPLETE)),
            f.layout(),
            vm);
    assertEquals(new HeapQueue(0, Set.of()), absent.queues().get("ready"));
    assertTrue(absent.complete());
    records.removeIf(record -> record.kind() == HeapSnapshot.Kind.CLASS_VISIT && record.a() == 3);
    var missing =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(copy(f.resources(), records, HeapSnapshot.Status.COMPLETE)),
            f.layout(),
            vm);
    assertFalse(missing.complete());
    assertTrue(missing.queues().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void resourceQuotaKeepsPositiveIdentityButCannotCertifyMemberCoverage(String vm) {
    var f = fixture(vm);
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(
                copy(f.resources(), f.resources().records(), HeapSnapshot.Status.RECORD_LIMIT)),
            f.layout(),
            vm);
    assertFalse(result.complete());
    assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void unknownActualMembersCannotDisappearFromACompleteWalk(String vm) {
    var f = fixture(vm);
    var records = new ArrayList<>(f.resources().records());
    records.add(object(999, 0, -1));
    records.replaceAll(
        record ->
            record.kind() == HeapSnapshot.Kind.REFERENCE
                    && (vm.equals("hotspot")
                        ? record.a() == 100 && record.d() == 1
                        : record.a() == 300)
                ? reference(record.a(), 999, (int) record.c(), (int) record.d())
                : record);
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(copy(f.resources(), records, HeapSnapshot.Status.COMPLETE)),
            f.layout(),
            vm);
    assertFalse(result.complete());
    assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void knownWrongNominalMemberRemainsVisibleForOracleRefutation(String vm) {
    var f = fixture(vm);
    var otherShape = new Shape("S", Shape.Kind.SOFT_REFERENCE, 0, 0);
    var otherType =
        new HeapSnapshot.ClassInfo(
            7,
            HeapSnapshot.ClassKind.SOFT_REFERENCE,
            "LCase$Other;",
            f.graph().classes().get(1).fields());
    var classes = new ArrayList<>(f.graph().classes());
    classes.add(otherType);
    var normal = new ArrayList<>(f.graph().records());
    normal.add(object(21, 7, -1));
    normal.add(primitive(21, 0, 'I', 0));
    normal.add(primitive(21, 1, 'I', 2));
    normal.add(primitive(21, 2, 'J', 1));
    normal.add(primitive(21, 3, 'I', -1));
    var references = new ArrayList<>(f.layout().references());
    references.add(
        new ObservationLayout.ReferenceType(
            "LCase$Other;",
            Value.ReferenceKind.SOFT,
            "idWorker",
            "idSite",
            "idOccurrence",
            "idSlot",
            "referent",
            otherShape));
    var layout =
        new ObservationLayout(
            List.of(),
            List.of(),
            f.layout().roots(),
            references,
            List.of(),
            ObservationLayout.REQUIRED,
            "jvmti",
            f.layout().queuePlan());
    var resourceClasses = new ArrayList<>(f.resources().classes());
    resourceClasses.add(otherType);
    var records = new ArrayList<>(f.resources().records());
    records.add(object(21, 7, -1));
    records.add(primitive(21, 0, 'I', 0));
    records.add(primitive(21, 1, 'I', 2));
    records.add(primitive(21, 2, 'J', 1));
    records.add(primitive(21, 3, 'I', -1));
    records.replaceAll(
        record ->
            record.kind() == HeapSnapshot.Kind.REFERENCE
                    && (vm.equals("hotspot")
                        ? record.a() == 100 && record.d() == 1
                        : record.a() == 300)
                ? reference(record.a(), 21, (int) record.c(), (int) record.d())
                : record);
    if (vm.equals("hotspot")) {
      records.add(reference(21, 201, 2, 5));
      records.add(reference(21, 21, 2, 6));
      normal.add(reference(21, 201, 2, 5));
      normal.add(reference(21, 21, 2, 6));
    }
    var result =
        QueueProjector.project(
            snapshot(classes, normal),
            new QueueCapture(snapshot(resourceClasses, records)),
            layout,
            vm);
    assertTrue(result.complete(), result.problems().toString());
    assertEquals(Set.of(new WrapperId(0, 2, 1, -1)), result.queues().get("ready").members());
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void sharedMemberFactsMustAgreeBeforeCanonicalIdentityIsReused(String vm) {
    for (String damage :
        List.of(
            "known-class",
            "unknown-class",
            "size",
            "length",
            "graph-class-edge",
            "resource-class-edge",
            "resource-class-referrer")) {
      var f = fixture(vm);
      var graphClasses = new ArrayList<>(f.graph().classes());
      var rawClasses = new ArrayList<>(f.resources().classes());
      var graphRecords = new ArrayList<>(f.graph().records());
      var records = new ArrayList<>(f.resources().records());
      var alternative =
          new HeapSnapshot.ClassInfo(
              7,
              HeapSnapshot.ClassKind.WEAK_REFERENCE,
              "LCase$Legacy;",
              f.graph().classes().get(1).fields());
      if (damage.equals("known-class")) {
        graphClasses.add(alternative);
        rawClasses.add(alternative);
      } else if (damage.equals("unknown-class")) {
        rawClasses.add(
            new HeapSnapshot.ClassInfo(
                7,
                HeapSnapshot.ClassKind.WEAK_REFERENCE,
                "LUnexpected$Reference;",
                alternative.fields()));
      }
      if (damage.endsWith("class")) {
        records.replaceAll(
            record ->
                record.kind() == HeapSnapshot.Kind.OBJECT && record.a() == 20
                    ? new HeapSnapshot.Record(record.kind(), 20, 7, record.c(), record.d(), 0, 0, 0)
                    : record);
        records.add(classEdge(20, -7, -7));
      } else if (damage.equals("size") || damage.equals("length")) {
        records.replaceAll(
            record ->
                record.kind() == HeapSnapshot.Kind.OBJECT && record.a() == 20
                    ? new HeapSnapshot.Record(
                        record.kind(),
                        20,
                        record.b(),
                        damage.equals("size") ? record.c() + 8 : record.c(),
                        damage.equals("length") ? 0 : record.d(),
                        0,
                        0,
                        0)
                    : record);
      } else if (damage.equals("graph-class-edge")) {
        graphRecords.add(classEdge(20, -7, -7));
        records.add(classEdge(20, -2, -2));
      } else if (damage.equals("resource-class-edge")) {
        graphRecords.add(classEdge(20, -2, -2));
        records.add(classEdge(20, -7, -7));
      } else {
        graphRecords.add(classEdge(20, -2, -2));
        records.add(classEdge(20, -2, -7));
      }
      var result =
          QueueProjector.project(
              snapshot(graphClasses, graphRecords),
              new QueueCapture(snapshot(rawClasses, records)),
              f.layout(),
              vm);
      assertFalse(result.complete(), vm + "/" + damage + ": " + result.problems());
      assertEquals(new HeapQueue(1, null), result.queues().get("ready"), vm + "/" + damage);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void sharedMemberCellsCannotDisagreeOrBorrowMissingIdentityPrimitives(String vm) {
    for (String damage :
        List.of(
            "ordinary-queue",
            "resource-referent",
            "missing-next",
            "identity-primitive",
            "missing-identity")) {
      var f = fixture(vm);
      var graph = new ArrayList<>(f.graph().records());
      var records = new ArrayList<>(f.resources().records());
      if (damage.equals("ordinary-queue")) {
        graph.removeIf(
            record ->
                record.kind() == HeapSnapshot.Kind.REFERENCE
                    && record.a() == 20
                    && record.d() == 5);
        graph.add(reference(20, 200, 2, 5));
      } else if (damage.equals("resource-referent")) {
        records.add(object(999, 0, -1));
        records.add(reference(20, 999, 2, 4));
      } else if (damage.equals("missing-next")) {
        graph.removeIf(
            record ->
                record.kind() == HeapSnapshot.Kind.REFERENCE
                    && record.a() == 20
                    && record.d() == 6);
        graph.add(reference(20, 20, 2, 6));
        records.removeIf(
            record ->
                record.kind() == HeapSnapshot.Kind.REFERENCE
                    && record.a() == 20
                    && record.d() == 6);
      } else if (damage.equals("identity-primitive")) {
        records.replaceAll(
            record ->
                record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                        && record.a() == 20
                        && record.b() == 1
                    ? primitive(20, 1, 'I', 2)
                    : record);
      } else
        records.removeIf(
            record ->
                record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                    && record.a() == 20
                    && record.b() == 2);
      var result =
          QueueProjector.project(
              snapshot(f.graph().classes(), graph),
              new QueueCapture(snapshot(f.resources().classes(), records)),
              f.layout(),
              vm);
      assertFalse(result.complete(), vm + "/" + damage + ": " + result.problems());
      assertEquals(new HeapQueue(1, null), result.queues().get("ready"), vm + "/" + damage);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void compatiblePresentClassEdgesRemainValid(String vm) {
    var f = fixture(vm);
    var graph = new ArrayList<>(f.graph().records());
    graph.add(classEdge(20, -2, -2));
    var records = new ArrayList<>(f.resources().records());
    records.add(classEdge(20, -2, -2));
    var result =
        QueueProjector.project(
            snapshot(f.graph().classes(), graph),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            vm);
    assertTrue(result.complete(), result.problems().toString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inactiveBackingArrayTargetsCannotCertifyMembership(boolean empty) {
    for (boolean unknown : List.of(false, true)) {
      var f = fixture("openj9");
      var records = new ArrayList<>(f.resources().records());
      if (empty) {
        records.removeIf(
            record -> record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == 300);
        records.replaceAll(
            record ->
                record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                        && record.a() == 100
                        && record.b() == 3
                    ? primitive(100, 3, 'I', 0)
                    : record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                            && record.a() == 100
                            && record.b() == 4
                        ? primitive(100, 4, 'Z', 1)
                        : record);
      }
      if (unknown) records.add(object(999, 0, -1));
      records.add(reference(300, unknown ? 999 : 20, 3, empty ? 0 : 1));
      var result =
          QueueProjector.project(
              f.graph(),
              new QueueCapture(snapshot(f.resources().classes(), records)),
              f.layout(),
              "openj9");
      assertFalse(result.complete(), result.problems().toString());
      assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unrelatedHelperArraysAreNotInterpretedAsTheSelectedQueuesStorage(boolean noStorage) {
    var f = fixture("openj9");
    var records = new ArrayList<>(f.resources().records());
    records.add(object(400, 6, 4));
    records.add(object(999, 0, -1));
    records.add(reference(400, 999, 3, 0));
    if (noStorage) {
      records.removeIf(
          record ->
              record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == 100 && record.d() == 1);
      records.replaceAll(
          record ->
              record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && record.a() == 100
                      && record.b() == 3
                  ? primitive(100, 3, 'I', 0)
                  : record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                          && record.a() == 100
                          && record.b() == 4
                      ? primitive(100, 4, 'Z', 1)
                      : record);
    }
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            "openj9");
    assertTrue(result.complete(), result.problems().toString());
    assertEquals(
        noStorage ? Set.of() : Set.of(new WrapperId(0, 1, 1, -1)),
        result.queues().get("ready").members());
  }

  private static HeapSnapshot.Record classEdge(long owner, long type, long referrer) {
    return new HeapSnapshot.Record(HeapSnapshot.Kind.REFERENCE, owner, type, 1, -1, referrer, 0, 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void selectedQueueClassEdgesMustAgreeBeforeResourceFieldsAreInterpreted(String vm) {
    var f = fixture(vm);
    var records = new ArrayList<>(f.resources().records());
    records.add(classEdge(100, -2, -2));
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            vm);
    assertFalse(result.complete(), result.problems().toString());
    assertTrue(result.queues().isEmpty(), result.queues().toString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectedBackingArrayClassEdgesCannotCertifyEmptyOrNonemptyMembership(boolean empty) {
    var f = fixture("openj9");
    var records = new ArrayList<>(f.resources().records());
    if (empty) {
      records.removeIf(record -> record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == 300);
      records.replaceAll(
          record ->
              record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && record.a() == 100
                      && record.b() == 3
                  ? primitive(100, 3, 'I', 0)
                  : record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                          && record.a() == 100
                          && record.b() == 4
                      ? primitive(100, 4, 'Z', 1)
                      : record);
    }
    records.add(classEdge(300, -2, -2));
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            "openj9");
    assertFalse(result.complete(), result.problems().toString());
    assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void matchingSelectedOwnerEdgesPassAndDetachedOwnerContradictionsStayUnselected(String vm) {
    var f = fixture(vm);
    var records = new ArrayList<>(f.resources().records());
    records.add(classEdge(100, -4, -4));
    if (vm.equals("openj9")) records.add(classEdge(300, -6, -6));
    records.add(object(400, 6, 4));
    records.add(classEdge(400, -2, -2));
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            vm);
    assertTrue(result.complete(), result.problems().toString());
    assertEquals(
        new HeapQueue(1, Set.of(new WrapperId(0, 1, 1, -1))), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void selectedOwnerSharedValuesCannotContradictOpaqueOrdinaryFacts(String vm) {
    for (String damage :
        List.of("reference", "primitive", "object-size", "known-class", "known-hint")) {
      var f = fixture(vm);
      var graph = new ArrayList<>(f.graph().records());
      graph.add(object(100, damage.equals("known-class") ? 2 : 0, -1));
      if (damage.equals("reference")) graph.add(reference(100, 999, 2, 1));
      else if (damage.equals("primitive")) graph.add(primitive(100, 0, 'I', 9));
      else if (damage.equals("known-hint"))
        graph.add(
            new HeapSnapshot.Record(
                HeapSnapshot.Kind.REFERENCE, 100, vm.equals("hotspot") ? 20 : 300, 2, 1, -2, 0, 0));
      else if (damage.equals("object-size"))
        graph.replaceAll(
            record ->
                record.kind() == HeapSnapshot.Kind.OBJECT && record.a() == 100
                    ? new HeapSnapshot.Record(record.kind(), 100, 0, 40, -1, 0, 0, 0)
                    : record);
      var result =
          QueueProjector.project(
              snapshot(f.graph().classes(), graph),
              new QueueCapture(f.resources()),
              f.layout(),
              vm);
      assertFalse(result.complete(), vm + "/" + damage + ": " + result.problems());
      if (damage.equals("reference"))
        assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectedArrayPositiveOrdinaryCellsCannotBeChangedOrOmitted(boolean omitted) {
    var f = fixture("openj9");
    var graph = new ArrayList<>(f.graph().records());
    graph.add(object(300, 0, 4));
    graph.add(reference(300, 20, 3, 0));
    var records = new ArrayList<>(f.resources().records());
    records.removeIf(record -> record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == 300);
    if (omitted) {
      records.replaceAll(
          record ->
              record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && record.a() == 100
                      && record.b() == 3
                  ? primitive(100, 3, 'I', 0)
                  : record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                          && record.a() == 100
                          && record.b() == 4
                      ? primitive(100, 4, 'Z', 1)
                      : record);
    } else {
      records.add(object(999, 0, -1));
      records.add(reference(300, 999, 3, 0));
    }
    var result =
        QueueProjector.project(
            snapshot(f.graph().classes(), graph),
            new QueueCapture(snapshot(f.resources().classes(), records)),
            f.layout(),
            "openj9");
    assertFalse(result.complete(), result.problems().toString());
    assertEquals(new HeapQueue(1, null), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hotspot", "openj9"})
  void matchingOpaqueHeadersAndPositiveSelectedCellsRemainValid(String vm) {
    var f = fixture(vm);
    var graph = new ArrayList<>(f.graph().records());
    graph.add(object(100, 0, -1));
    graph.add(classEdge(100, -4, -4));
    graph.add(reference(100, vm.equals("hotspot") ? 20 : 300, 2, 1));
    graph.add(primitive(100, 0, 'I', 1));
    if (vm.equals("openj9")) {
      graph.add(object(300, 0, 4));
      graph.add(classEdge(300, -6, -6));
      graph.add(reference(300, 20, 3, 0));
    }
    var resources = new ArrayList<>(f.resources().records());
    resources.replaceAll(
        record ->
            record.kind() == HeapSnapshot.Kind.REFERENCE
                    && (record.a() == 100 && record.d() == 1 || record.a() == 300)
                ? new HeapSnapshot.Record(
                    record.kind(),
                    record.a(),
                    record.b(),
                    record.c(),
                    record.d(),
                    record.a() == 100 ? -4 : -6,
                    record.f(),
                    record.g())
                : record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                        && record.a() == 100
                        && record.b() == 0
                    ? new HeapSnapshot.Record(
                        record.kind(), record.a(), record.b(), record.c(), record.d(), -4, 0, 0)
                    : record);
    var result =
        QueueProjector.project(
            snapshot(f.graph().classes(), graph),
            new QueueCapture(snapshot(f.resources().classes(), resources)),
            f.layout(),
            vm);
    assertTrue(result.complete(), result.problems().toString());
    assertEquals(
        new HeapQueue(1, Set.of(new WrapperId(0, 1, 1, -1))), result.queues().get("ready"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "valid",
        "missing",
        "wrong-run",
        "wrong-thread",
        "wrong-thread-tag",
        "wrong-root-class",
        "wrong-root-index",
        "wrong-queue",
        "wrong-lock",
        "wrong-owner",
        "failed-cleanup",
        "missing-lock-class",
        "missing-caller-root",
        "wrong-lock-field",
        "partial-resource",
        "wrong-mode",
        "extra-resource",
        "wrong-interval",
        "identity-with-missing",
        "second-resource-bad-lock",
        "null-root-missing",
        "wrong-checkpoint",
        "missing-thread-class",
        "missing-thread-class-edge",
        "caller-queue-alias",
        "caller-lock-alias",
        "other-thread-class-only"
      })
  void automaticMembershipNeedsActualAllResourceCustodyAndRetainsIndependentIdentity(
      String damage) {
    var f = fixture("hotspot");
    var types = new ArrayList<>(f.resources().classes());
    var queue = types.get(3);
    var fields = new ArrayList<>(queue.fields());
    fields.add(field(3, 18, "lock", "Ljava/lang/ref/ReferenceQueue$Lock;"));
    types.set(3, new HeapSnapshot.ClassInfo(queue.id(), queue.kind(), queue.signature(), fields));
    if (!damage.equals("missing-lock-class"))
      types.add(
          new HeapSnapshot.ClassInfo(
              7, HeapSnapshot.ClassKind.OBJECT, "Ljava/lang/ref/ReferenceQueue$Lock;", List.of()));
    if (!damage.equals("missing-thread-class"))
      types.add(
          new HeapSnapshot.ClassInfo(
              8, HeapSnapshot.ClassKind.OBJECT, "Ljava/lang/Thread;", List.of()));
    var records = new ArrayList<>(f.resources().records());
    records.add(visit(7));
    records.add(visit(8));
    records.add(object(400, 7, -1));
    records.add(object(500, 8, -1));
    if (!damage.equals("missing-thread-class-edge")) records.add(reference(500, -8, 1, -1));
    long callerTag =
        damage.equals("caller-queue-alias")
            ? 100
            : damage.equals("caller-lock-alias")
                ? 400
                : damage.equals("other-thread-class-only") ? 501 : 500;
    if (callerTag != 500) {
      if (callerTag == 501) records.add(object(501, 8, -1));
      records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, callerTag, 25, 1, 0, 0, 0, 0));
    }
    records.add(reference(100, damage.equals("wrong-lock-field") ? 401 : 400, 2, 3));
    if (!damage.equals("missing-caller-root"))
      records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, 500, 25, 1, 0, 0, 0, 0));
    if (damage.equals("identity-with-missing")) {
      records.removeIf(
          record ->
              record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                  && record.a() == 100
                  && record.b() == 0);
      records.add(primitive(100, 0, 'I', 9));
    }
    if (damage.equals("null-root-missing"))
      records.removeIf(
          record ->
              record.kind() == HeapSnapshot.Kind.REFERENCE && record.a() == -3 && record.d() == 0);
    boolean secondResource = damage.equals("second-resource-bad-lock");
    if (secondResource) {
      types.set(
          2,
          new HeapSnapshot.ClassInfo(
              3,
              HeapSnapshot.ClassKind.ROOTS,
              QueuePlan.ROOTS,
              List.of(field(0, 9, "q1", QueuePlan.TYPE), field(1, 9, "q2", QueuePlan.TYPE))));
      records.add(reference(-3, 101, 8, 1));
      records.add(object(101, 4, -1));
      records.add(primitive(101, 0, 'I', 2));
      records.add(primitive(101, 2, 'J', 0));
      records.add(object(401, 7, -1));
      records.add(reference(101, 401, 2, 3));
    }
    var row =
        new QueueConsistency.Lock(
            1,
            damage.equals("wrong-root-class") ? 4 : 3,
            damage.equals("wrong-root-index") ? 1 : 0,
            1,
            damage.equals("wrong-queue") ? 101 : 100,
            damage.equals("wrong-lock") ? 401 : 400,
            damage.equals("wrong-owner") ? 501 : callerTag,
            callerTag,
            1,
            1,
            damage.equals("failed-cleanup") ? -1 : 0);
    var rows = new ArrayList<>(List.of(row));
    if (damage.equals("extra-resource"))
      rows.add(new QueueConsistency.Lock(2, 3, 1, 1, 101, 401, 500, 500, 1, 1, 0));
    if (secondResource)
      rows.add(new QueueConsistency.Lock(2, 3, 1, 1, 101, 402, 500, 500, 1, 1, 0));
    QueueConsistency proof =
        damage.equals("missing")
                || damage.equals("identity-with-missing")
                || damage.equals("null-root-missing")
            ? null
            : new QueueConsistency(
                damage.equals("wrong-run") ? 2 : 1,
                damage.equals("wrong-checkpoint") ? 2 : 1,
                damage.equals("wrong-thread") ? 2 : 1,
                damage.equals("wrong-thread-tag") ? 501 : callerTag,
                damage.equals("wrong-interval") ? 1 : 0,
                0,
                damage.equals("wrong-interval") ? 1 : 0,
                0,
                rows);
    var original = f.layout();
    var resources = new ArrayList<>(original.queuePlan().resources());
    if (secondResource) resources.add(new QueuePlan.Resource(2, "second", "W"));
    var layout =
        new ObservationLayout(
            original.objects(),
            original.arrays(),
            original.roots(),
            original.references(),
            original.bindings(),
            original.bindingSelection(),
            original.bindingMetadata(),
            new QueuePlan(resources, true));
    var raw = snapshot(types, records);
    if (damage.equals("partial-resource"))
      raw = copy(raw, records, HeapSnapshot.Status.RECORD_LIMIT);
    var result =
        QueueProjector.project(
            f.graph(),
            new QueueCapture(raw),
            layout,
            damage.equals("wrong-mode") ? "openj9" : "hotspot",
            proof,
            1);
    assertEquals(damage.equals("valid"), result.complete(), result.problems().toString());
    assertEquals(
        damage.equals("identity-with-missing") ? 9 : damage.equals("null-root-missing") ? 0 : 1,
        result.queues().get("ready").identity());
    assertEquals(
        damage.equals("valid") ? Set.of(new WrapperId(0, 1, 1, -1)) : null,
        result.queues().get("ready").members());
  }

  private static Fixture fixture(String vm) {
    var shape = new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0);
    var normalClasses =
        List.of(
            new HeapSnapshot.ClassInfo(1, HeapSnapshot.ClassKind.ROOTS, "LCase$Roots;", List.of()),
            new HeapSnapshot.ClassInfo(
                2,
                HeapSnapshot.ClassKind.WEAK_REFERENCE,
                "LCase$W;",
                List.of(
                    field(0, 17, "idWorker", "I"),
                    field(1, 17, "idSite", "I"),
                    field(2, 17, "idOccurrence", "J"),
                    field(3, 17, "idSlot", "I"),
                    field(4, 2, "referent", "Ljava/lang/Object;"),
                    field(5, 2, "queue", "Ljava/lang/ref/ReferenceQueue;"),
                    field(6, 2, "next", "Ljava/lang/ref/Reference;"))));
    var layout =
        new ObservationLayout(
            List.of(),
            List.of(),
            new ObservationLayout.Roots("LCase$Roots;", Map.of()),
            List.of(
                new ObservationLayout.ReferenceType(
                    "LCase$W;",
                    Value.ReferenceKind.WEAK,
                    "idWorker",
                    "idSite",
                    "idOccurrence",
                    "idSlot",
                    "referent",
                    shape)),
            List.of(),
            ObservationLayout.REQUIRED,
            "jvmti",
            new QueuePlan(List.of(new QueuePlan.Resource(1, "ready", "W"))));
    var normalRecords =
        List.of(
            visit(1),
            object(20, 2, -1),
            primitive(20, 0, 'I', 0),
            primitive(20, 1, 'I', 1),
            primitive(20, 2, 'J', 1),
            primitive(20, 3, 'I', -1));
    var classes = new ArrayList<>(normalClasses);
    classes.add(
        new HeapSnapshot.ClassInfo(
            3,
            HeapSnapshot.ClassKind.ROOTS,
            QueuePlan.ROOTS,
            List.of(field(0, 9, "q1", QueuePlan.TYPE))));
    classes.add(
        new HeapSnapshot.ClassInfo(
            4,
            HeapSnapshot.ClassKind.OBJECT,
            QueuePlan.TYPE,
            vm.equals("hotspot")
                ? List.of(
                    field(0, 17, "idQueue", "I"),
                    field(1, 2, "head", "Ljava/lang/ref/Reference;"),
                    field(2, 2, "queueLength", "J"))
                : List.of(
                    field(0, 17, "idQueue", "I"),
                    field(1, 2, "references", "[Ljava/lang/ref/Reference;"),
                    field(2, 2, "head", "I"),
                    field(3, 2, "tail", "I"),
                    field(4, 2, "empty", "Z"))));
    classes.add(
        new HeapSnapshot.ClassInfo(
            5,
            HeapSnapshot.ClassKind.OBJECT,
            "Ljava/lang/ref/ReferenceQueue;",
            vm.equals("hotspot")
                ? List.of(
                    field(0, 24, "NULL", "Ljava/lang/ref/ReferenceQueue;"),
                    field(1, 24, "ENQUEUED", "Ljava/lang/ref/ReferenceQueue;"))
                : List.of()));
    classes.add(
        new HeapSnapshot.ClassInfo(
            6, HeapSnapshot.ClassKind.REFERENCE_ARRAY, "[Ljava/lang/ref/Reference;", List.of()));
    var records = new ArrayList<HeapSnapshot.Record>();
    records.add(visit(3));
    records.add(visit(5));
    records.add(object(100, 4, -1));
    records.add(object(20, 2, -1));
    records.add(primitive(20, 0, 'I', 0));
    records.add(primitive(20, 1, 'I', 1));
    records.add(primitive(20, 2, 'J', 1));
    records.add(primitive(20, 3, 'I', -1));
    records.add(reference(-3, 100, 8, 0));
    records.add(primitive(100, 0, 'I', 1));
    if (vm.equals("hotspot")) {
      records.add(object(200, 0, -1));
      records.add(object(201, 0, -1));
      records.add(reference(-5, 200, 8, 0));
      records.add(reference(-5, 201, 8, 1));
      records.add(reference(100, 20, 2, 1));
      records.add(primitive(100, 2, 'J', 1));
      records.add(reference(20, 201, 2, 5));
      records.add(reference(20, 20, 2, 6));
    } else {
      records.add(object(300, 6, 4));
      records.add(reference(100, 300, 2, 1));
      records.add(primitive(100, 2, 'I', 0));
      records.add(primitive(100, 3, 'I', 1));
      records.add(primitive(100, 4, 'Z', 0));
      records.add(reference(300, 20, 3, 0));
    }
    var graphRecords = new ArrayList<>(normalRecords);
    if (vm.equals("hotspot")) {
      graphRecords.add(reference(20, 201, 2, 5));
      graphRecords.add(reference(20, 20, 2, 6));
    }
    return new Fixture(snapshot(normalClasses, graphRecords), snapshot(classes, records), layout);
  }

  private static HeapSnapshot snapshot(
      List<HeapSnapshot.ClassInfo> classes, List<HeapSnapshot.Record> records) {
    return new HeapSnapshot(
        1,
        1,
        HeapSnapshot.Status.COMPLETE,
        0,
        17,
        0,
        0,
        0,
        0,
        classes,
        List.of(),
        List.of(),
        records);
  }

  private static HeapSnapshot copy(
      HeapSnapshot original, List<HeapSnapshot.Record> records, HeapSnapshot.Status status) {
    return new HeapSnapshot(
        1, 1, status, 0, 17, 0, 0, 0, 0, original.classes(), List.of(), List.of(), records);
  }

  private static HeapSnapshot.FieldInfo field(
      int index, int modifiers, String name, String descriptor) {
    return new HeapSnapshot.FieldInfo(index, modifiers, name, descriptor);
  }

  private static HeapSnapshot.Record object(long tag, int type, long length) {
    return new HeapSnapshot.Record(HeapSnapshot.Kind.OBJECT, tag, type, 32, length, 0, 0, 0);
  }

  private static HeapSnapshot.Record primitive(long tag, int index, char type, long value) {
    return new HeapSnapshot.Record(
        HeapSnapshot.Kind.PRIMITIVE_FIELD, tag, index, type, value, 0, 0, 0);
  }

  private static HeapSnapshot.Record reference(long owner, long target, int kind, int index) {
    return new HeapSnapshot.Record(
        HeapSnapshot.Kind.REFERENCE, owner, target, kind, index, 0, 0, 0);
  }

  private static HeapSnapshot.Record visit(int type) {
    return new HeapSnapshot.Record(HeapSnapshot.Kind.CLASS_VISIT, type, 0, 0, 0, 0, 0, 0);
  }
}
