package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BindingProjectionTest {
  private static final Shape SHAPE = new Shape("N", Shape.Kind.OBJECT, 0, 1);
  private static final String DESCRIPTOR = "(Lio/connglli/gorrect/runtime/TargetSession;)V";
  private static final ObservationLayout LAYOUT =
      new ObservationLayout(
          List.of(
              new ObservationLayout.ObjectType(
                  "LCase$S0;", SHAPE, List.of(), List.of("d0"), "worker", "site", "occurrence")),
          List.of(),
          new ObservationLayout.Roots("LCase$Roots;", Map.of("r0", "main")),
          List.of(),
          List.of(
              new ObservationLayout.LocalBinding(7, 0, "execute", DESCRIPTOR, "b7", "LCase$S0;")));
  private static final BindingEvidence PRESENT =
      new BindingEvidence(1, 1, 11, 0, 1, List.of(new BindingEvidence.Binding(7, true)));

  @Test
  void resolvesActualPhysicalRootInsteadOfLookingUpTheExpectedIdentity() {
    var raw = snapshot(root(100, 11, 2, 901, 20));
    var expected = expected(Value.Reference.strong(new ObjectId(0, 7, 1)));
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        SnapshotProjector.project(raw, LAYOUT, PRESENT).verify(expected).verdict());
    // Both objects remain captured. A wrong root must not be repaired by finding the wanted ID.
    var wrong = snapshot(root(200, 11, 2, 901, 20));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        SnapshotProjector.project(wrong, LAYOUT, PRESENT).verify(expected).verdict());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "thread", "slot", "location", "method", "ambiguous"})
  void refusesIncompleteOrAmbiguousOrigins(String damage) {
    var roots = new ArrayList<HeapSnapshot.Record>();
    switch (damage) {
      case "missing" -> {}
      case "thread" -> roots.add(root(100, 12, 2, 901, 20));
      case "slot" -> roots.add(root(100, 11, 3, 901, 20));
      case "location" -> roots.add(root(100, 11, 2, 901, 200));
      case "method" -> roots.add(root(100, 11, 2, 902, 20));
      case "ambiguous" -> {
        roots.add(root(100, 11, 2, 901, 20));
        roots.add(root(200, 11, 2, 901, 20));
      }
      default -> throw new AssertionError(damage);
    }
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        SnapshotProjector.project(
                snapshot(roots.toArray(HeapSnapshot.Record[]::new)), LAYOUT, PRESENT)
            .verify(expected(Value.Reference.strong(new ObjectId(0, 7, 1))))
            .verdict());
  }

  @Test
  void nullRequiresExplicitPresenceEvidenceRatherThanAbsenceOfARoot() {
    var absent =
        new BindingEvidence(1, 1, 11, 0, 1, List.of(new BindingEvidence.Binding(7, false)));
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        SnapshotProjector.project(snapshot(), LAYOUT, absent)
            .verify(expected(Value.Reference.NULL))
            .verdict());
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        SnapshotProjector.project(snapshot(), LAYOUT, PRESENT)
            .verify(expected(Value.Reference.NULL))
            .verdict());
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        SnapshotProjector.project(snapshot(root(100, 11, 2, 901, 20)), LAYOUT, absent)
            .verify(expected(Value.Reference.NULL))
            .verdict());
  }

  @ParameterizedTest
  @ValueSource(strings = {"complete", "missing", "thread", "slot", "activation", "wrong-target"})
  void projectsCallerAndWorkerFromTheirOwnPhysicalFrames(String damage) {
    String workerDescriptor =
        "(Lio/connglli/gorrect/runtime/TargetSession;Lio/connglli/gorrect/runtime/WorkerContext;)V";
    var bindings = new ArrayList<>(LAYOUT.bindings());
    bindings.add(
        new ObservationLayout.LocalBinding(
            8, 1, ExecutionEvidence.workerMethod(1), workerDescriptor, "b8", "LCase$S0;"));
    var layout =
        new ObservationLayout(
            LAYOUT.objects(), LAYOUT.arrays(), LAYOUT.roots(), LAYOUT.references(), bindings);
    var raw = snapshot(root(100, 11, 2, 901, 20));
    var records = new ArrayList<>(raw.records());
    if (!damage.equals("missing"))
      records.add(
          root(
              damage.equals("wrong-target") ? 100 : 200,
              damage.equals("thread") ? 13 : 12,
              damage.equals("slot") ? 4 : 3,
              902,
              20));
    if (damage.equals("activation"))
      records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, 200, 24, 12, 4, 3, 902, 20));
    var methods = new ArrayList<>(raw.methods());
    methods.add(
        new HeapSnapshot.MethodInfo(
            902,
            "LCase;",
            ExecutionEvidence.workerMethod(1),
            workerDescriptor,
            List.of(new HeapSnapshot.LocalVariable(0, 100, 3, "b8", "LCase$S0;"))));
    raw =
        new HeapSnapshot(
            raw.run(),
            raw.checkpoint(),
            raw.status(),
            raw.jvmtiError(),
            raw.capabilities(),
            0,
            0,
            0,
            0,
            raw.classes(),
            raw.arrays(),
            methods,
            records);
    var worker = new BindingEvidence(1, 1, 12, 1, 3, List.of(new BindingEvidence.Binding(8, true)));
    var graph = expected(Value.Reference.strong(new ObjectId(0, 7, 1)));
    var expected =
        new Graph(
            graph.objects(),
            graph.roots(),
            Map.of(
                7,
                Value.Reference.strong(new ObjectId(0, 7, 1)),
                8,
                Value.Reference.strong(new ObjectId(0, 8, 1))));
    // Both actual object records are present even when a stack origin is damaged.
    var actualObjects = new HashMap<>(expected.objects());
    actualObjects.put(new ObjectId(0, 8, 1), new HeapObject(SHAPE, List.of(), List.of(43L)));
    expected = new Graph(actualObjects, expected.roots(), expected.handles());
    assertEquals(
        damage.equals("complete")
            ? ExactVerifier.Verdict.SATISFIED
            : damage.equals("wrong-target")
                ? ExactVerifier.Verdict.VIOLATED
                : ExactVerifier.Verdict.INCONCLUSIVE,
        SnapshotProjector.projectParticipants(raw, layout, List.of(PRESENT, worker))
            .verify(expected)
            .verdict());
  }

  private static Graph expected(Value.Reference binding) {
    return new Graph(
        Map.of(new ObjectId(0, 7, 1), new HeapObject(SHAPE, List.of(), List.of(42L))),
        Map.of("main", Value.Reference.NULL),
        Map.of(7, binding));
  }

  private static HeapSnapshot.Record root(
      long tag, long thread, long slot, long method, long location) {
    return new HeapSnapshot.Record(
        HeapSnapshot.Kind.ROOT, tag, 24, thread, 3, slot, method, location);
  }

  private static HeapSnapshot snapshot(HeapSnapshot.Record... roots) {
    var fields =
        List.of(
            new HeapSnapshot.FieldInfo(0, Modifier.FINAL, "worker", "I"),
            new HeapSnapshot.FieldInfo(1, Modifier.FINAL, "site", "I"),
            new HeapSnapshot.FieldInfo(2, Modifier.FINAL, "occurrence", "J"),
            new HeapSnapshot.FieldInfo(3, 0, "d0", "J"));
    var classes =
        List.of(
            new HeapSnapshot.ClassInfo(1, HeapSnapshot.ClassKind.OBJECT, "LCase$S0;", fields),
            new HeapSnapshot.ClassInfo(
                2,
                HeapSnapshot.ClassKind.ROOTS,
                "LCase$Roots;",
                List.of(
                    new HeapSnapshot.FieldInfo(0, Modifier.STATIC, "r0", "Ljava/lang/Object;"))));
    var records = new ArrayList<HeapSnapshot.Record>();
    for (int i = 0; i < 2; i++) {
      long tag = 100L * (i + 1);
      records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.OBJECT, tag, 1, 48, -1, 0, 0, 0));
      for (int field = 0; field < 4; field++) {
        long value =
            switch (field) {
              case 0 -> 0;
              case 1 -> 7 + i;
              case 2 -> 1;
              default -> 42 + i;
            };
        records.add(
            new HeapSnapshot.Record(
                HeapSnapshot.Kind.PRIMITIVE_FIELD,
                tag,
                field,
                field < 2 ? 73 : 74,
                value,
                -1,
                0,
                0));
      }
    }
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.CLASS_VISIT, 2, 0, 0, 0, 0, 0, 0));
    records.addAll(List.of(roots));
    var method =
        new HeapSnapshot.MethodInfo(
            901,
            "LCase;",
            "execute",
            DESCRIPTOR,
            List.of(new HeapSnapshot.LocalVariable(0, 100, 2, "b7", "LCase$S0;")));
    return new HeapSnapshot(
        1,
        1,
        HeapSnapshot.Status.COMPLETE,
        0,
        HeapSnapshot.TAGGING | HeapSnapshot.LOCAL_VARIABLES,
        0,
        0,
        0,
        0,
        classes,
        List.of(),
        List.of(method),
        records);
  }
}
