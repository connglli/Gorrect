package io.connglli.gorrect.engine.oracle;

import static io.connglli.gorrect.engine.oracle.ExactVerifier.Verdict.INCONCLUSIVE;
import static io.connglli.gorrect.engine.oracle.ExactVerifier.Verdict.SATISFIED;
import static io.connglli.gorrect.engine.oracle.ExactVerifier.Verdict.VIOLATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactVerifierTest {
  private static final ObjectId ONE = ObjectId.initial(1);
  private static final ObjectId TWO = ObjectId.initial(2);
  private static final ObjectId THREE = ObjectId.initial(3);
  private static final Shape NODE = new Shape("N", Shape.Kind.OBJECT, 2, 1);
  private static final HeapObject FIRST =
      new HeapObject(NODE, List.of(ref(TWO), ref(TWO)), List.of(10L));
  private static final HeapObject SECOND =
      new HeapObject(NODE, List.of(ref(ONE), Value.Reference.NULL), List.of(20L));
  private static final Graph EXPECTED =
      new Graph(
          Map.of(ONE, FIRST, TWO, SECOND),
          Map.of("r", ref(ONE), "empty", Value.Reference.NULL),
          Map.of());

  @Test
  void checksRequiredContentsAndAllowsRetainedGarbage() {
    assertVerdict(SATISFIED, EXPECTED);
    var objects = new HashMap<>(EXPECTED.objects());
    objects.put(
        THREE, new HeapObject(NODE, List.of(ref(THREE), Value.Reference.NULL), List.of(123L)));
    assertVerdict(SATISFIED, new Graph(objects, EXPECTED.roots(), Map.of()));
  }

  @Test
  void detectsCorruptionEvenWhenReachabilityIsUnchanged() {
    assertVerdict(
        VIOLATED, replace(ONE, new HeapObject(NODE, List.of(ref(ONE), ref(TWO)), FIRST.data())));
    assertVerdict(VIOLATED, replace(TWO, new HeapObject(NODE, SECOND.references(), List.of(21L))));
    assertVerdict(
        VIOLATED,
        replace(
            ONE,
            new HeapObject(
                NODE,
                List.of(new Value.Reference(Value.ReferenceKind.WEAK, TWO), ref(TWO)),
                FIRST.data())));
    assertVerdict(
        VIOLATED,
        replace(
            TWO,
            new HeapObject(
                NODE,
                List.of(ref(ONE), new Value.Reference(Value.ReferenceKind.SOFT, null)),
                SECOND.data())));
  }

  @Test
  void detectsMissingObjectsSlotsAndUnexpectedEdges() {
    assertVerdict(VIOLATED, new Graph(Map.of(ONE, FIRST), EXPECTED.roots(), Map.of()));
    assertVerdict(
        VIOLATED,
        replace(ONE, new HeapObject(NODE, List.of(Value.Reference.NULL, ref(TWO)), FIRST.data())));
    assertVerdict(VIOLATED, replace(ONE, new HeapObject(NODE, List.of(ref(TWO)), FIRST.data())));
    assertVerdict(
        VIOLATED,
        replace(ONE, new HeapObject(NODE, List.of(ref(TWO), ref(TWO), ref(ONE)), FIRST.data())));
    assertVerdict(VIOLATED, replace(TWO, new HeapObject(NODE, SECOND.references(), List.of())));
    assertVerdict(
        VIOLATED, replace(TWO, new HeapObject(NODE, SECOND.references(), List.of(20L, 0L))));
    assertVerdict(
        VIOLATED, replace(ONE, new HeapObject(NODE, List.of(ref(THREE), ref(TWO)), FIRST.data())));
  }

  @Test
  void detectsWrongShapeRootsAndLiveHandles() {
    assertVerdict(
        VIOLATED,
        replace(
            ONE,
            new HeapObject(
                new Shape("Different", Shape.Kind.OBJECT, 2, 1),
                FIRST.references(),
                FIRST.data())));
    assertVerdict(VIOLATED, new Graph(EXPECTED.objects(), Map.of("r", ref(ONE)), Map.of()));
    assertVerdict(
        VIOLATED,
        new Graph(
            EXPECTED.objects(), Map.of("r", ref(TWO), "empty", Value.Reference.NULL), Map.of()));
    var roots = new HashMap<>(EXPECTED.roots());
    roots.put("unexpected", ref(TWO));
    assertVerdict(VIOLATED, new Graph(EXPECTED.objects(), roots, Map.of()));
    assertVerdict(VIOLATED, new Graph(EXPECTED.objects(), EXPECTED.roots(), Map.of(99, ref(TWO))));
    var withHandle = new Graph(EXPECTED.objects(), EXPECTED.roots(), Map.of(5, ref(TWO)));
    assertEquals(VIOLATED, ExactVerifier.verify(withHandle, EXPECTED, true).verdict());
  }

  @Test
  void incompleteEvidenceCannotPassOrEstablishAMismatch() {
    assertEquals(INCONCLUSIVE, ExactVerifier.verify(EXPECTED, EXPECTED, false).verdict());
    assertEquals(
        INCONCLUSIVE,
        ExactVerifier.verify(EXPECTED, new Graph(Map.of(), Map.of(), Map.of()), false).verdict());
  }

  @Test
  void malformedExpectedModelsAreRejected() {
    var dangling = new Graph(Map.of(), Map.of("r", ref(ONE)), Map.of());
    assertThrows(
        IllegalArgumentException.class, () -> ExactVerifier.verify(dangling, dangling, true));
    var unsupported =
        new Graph(
            Map.of(), Map.of("r", new Value.Reference(Value.ReferenceKind.WEAK, null)), Map.of());
    assertThrows(
        IllegalArgumentException.class, () -> ExactVerifier.verify(unsupported, unsupported, true));
  }

  @Test
  void boundsDiagnosticsWithoutHidingTheVerdict() {
    var roots = new HashMap<String, Value.Reference>();
    for (int i = 0; i < 200; i++) roots.put("r" + i, Value.Reference.NULL);
    var result =
        ExactVerifier.verify(
            new Graph(Map.of(), roots, Map.of()), new Graph(Map.of(), Map.of(), Map.of()), true);
    assertEquals(VIOLATED, result.verdict());
    assertEquals(100, result.differences().size());
    assertTrue(result.differences().getFirst().startsWith("root r0:"));
  }

  @Test
  void enumeratesSmallGraphsAndMutationsAgainstIndependentReachability() {
    var targets = List.of(Value.Reference.NULL, ref(ONE), ref(TWO));
    for (int code = 0; code < 81; code++) {
      int value = code;
      var references = new ArrayList<Value.Reference>();
      for (int slot = 0; slot < 4; slot++) {
        references.add(targets.get(value % 3));
        value /= 3;
      }
      var first = new HeapObject(NODE, references.subList(0, 2), List.of(10L));
      var second = new HeapObject(NODE, references.subList(2, 4), List.of(20L));
      boolean secondReachable = first.references().contains(ref(TWO));
      var required = secondReachable ? Map.of(ONE, first, TWO, second) : Map.of(ONE, first);
      var expected = new Graph(required, Map.of("r", ref(ONE)), Map.of());
      var observed = new Graph(Map.of(ONE, first, TWO, second), expected.roots(), Map.of());
      assertEquals(SATISFIED, ExactVerifier.verify(expected, observed, true).verdict());
      assertEquals(expected, observed.required());
      for (var id : List.of(ONE, TWO)) {
        for (int index = 0; index < 2; index++) {
          var old = observed.objects().get(id);
          var changed = new ArrayList<>(old.references());
          changed.set(index, changed.get(index).target() == null ? ref(ONE) : Value.Reference.NULL);
          var objects = new HashMap<>(observed.objects());
          objects.put(id, new HeapObject(NODE, changed, old.data()));
          var verdict = id.equals(ONE) || secondReachable ? VIOLATED : SATISFIED;
          assertEquals(
              verdict,
              ExactVerifier.verify(expected, new Graph(objects, observed.roots(), Map.of()), true)
                  .verdict());
        }
      }
    }
  }

  private static Graph replace(ObjectId id, HeapObject object) {
    var objects = new HashMap<>(EXPECTED.objects());
    objects.put(id, object);
    return new Graph(objects, EXPECTED.roots(), EXPECTED.handles());
  }

  private static void assertVerdict(ExactVerifier.Verdict verdict, Graph observed) {
    assertEquals(verdict, ExactVerifier.verify(EXPECTED, observed, true).verdict());
  }

  private static Value.Reference ref(ObjectId id) {
    return Value.Reference.strong(id);
  }
}
