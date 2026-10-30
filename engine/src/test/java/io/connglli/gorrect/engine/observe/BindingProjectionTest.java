package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
