package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Empty contents follow actual type/length evidence, not an assumed callback or expected ID. */
class EmptyDataArrayProjectionTest {
  private static final Shape SHAPE = new Shape("D", Shape.Kind.DATA_ARRAY, 0, 0);
  private static final ObservationLayout LAYOUT =
      new ObservationLayout(
          List.of(),
          List.of(SHAPE),
          new ObservationLayout.Roots("LCase$Roots;", Map.of("r", "main")));
  private static final Graph EXPECTED =
      new Graph(
          Map.of(ObjectId.initial(1), new HeapObject(SHAPE, List.of(), List.of())),
          Map.of("main", Value.Reference.strong(ObjectId.initial(1))),
          Map.of());

  @Test
  void observedEmptyLongArrayNeedsNoValuesCallback() {
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        verdict(snapshot(0, "[J", HeapSnapshot.Status.COMPLETE, records())));
    var marked = records();
    marked.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_VALUES, 100, 0, 74, 0, 0, 0, 0));
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        verdict(snapshot(0, "[J", HeapSnapshot.Status.COMPLETE, marked)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-owner",
        "missing-identity",
        "wrong-length",
        "wrong-type",
        "incomplete",
        "wrong-marker-type",
        "wrong-marker-count",
        "extra-element",
        "wrong-type-with-marker"
      })
  void missingOrContradictoryEvidenceCannotEstablishAnEmptyArray(String damage) {
    var records = records();
    int length = 0;
    String signature = "[J";
    var status = HeapSnapshot.Status.COMPLETE;
    switch (damage) {
      case "missing-owner" -> records.removeIf(record -> record.kind() == HeapSnapshot.Kind.OBJECT);
      case "wrong-length" -> length = 1;
      case "wrong-type" -> signature = "[I";
      case "incomplete" -> status = HeapSnapshot.Status.RECORD_LIMIT;
      case "wrong-marker-type" ->
          records.add(
              new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_VALUES, 100, 0, 73, 0, 0, 0, 0));
      case "wrong-marker-count" ->
          records.add(
              new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_VALUES, 100, 1, 74, 0, 0, 0, 0));
      case "extra-element" ->
          records.add(
              new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_ELEMENT, 100, 0, 74, 7, 1, 0, 0));
      case "wrong-type-with-marker" -> {
        signature = "[I";
        records.add(
            new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_VALUES, 100, 0, 74, 0, 0, 0, 0));
      }
      case "missing-identity" -> {}
      default -> throw new AssertionError(damage);
    }
    var raw = snapshot(length, signature, status, records);
    if (damage.equals("missing-identity"))
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
              List.of(),
              raw.methods(),
              raw.records());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verdict(raw), damage);
  }

  @Test
  void nonemptyLongArrayStillRequiresItsValuesMarker() {
    var shape = new Shape("D", Shape.Kind.DATA_ARRAY, 0, 1);
    var layout = new ObservationLayout(List.of(), List.of(shape), LAYOUT.roots());
    var records = records();
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ARRAY_ELEMENT, 100, 0, 74, 7, 1, 0, 0));
    var raw = snapshot(1, "[J", HeapSnapshot.Status.COMPLETE, records);
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        SnapshotProjector.project(raw, layout)
            .verify(
                new Graph(
                    Map.of(ObjectId.initial(1), new HeapObject(shape, List.of(), List.of(7L))),
                    EXPECTED.roots(),
                    Map.of()))
            .verdict());
  }

  private static ExactVerifier.Verdict verdict(HeapSnapshot snapshot) {
    return SnapshotProjector.project(snapshot, LAYOUT).verify(EXPECTED).verdict();
  }

  private static ArrayList<HeapSnapshot.Record> records() {
    return new ArrayList<>(
        List.of(
            new HeapSnapshot.Record(HeapSnapshot.Kind.OBJECT, 100, 1, 16, 0, 0, 0, 0),
            new HeapSnapshot.Record(HeapSnapshot.Kind.REFERENCE, -2, 100, 8, 0, 0, 0, 0),
            new HeapSnapshot.Record(HeapSnapshot.Kind.CLASS_VISIT, 2, 0, 0, 0, 0, 0, 0)));
  }

  private static HeapSnapshot snapshot(
      int length, String signature, HeapSnapshot.Status status, List<HeapSnapshot.Record> records) {
    var actual = new ArrayList<>(records);
    for (int index = 0; index < actual.size(); index++) {
      var record = actual.get(index);
      if (record.kind() == HeapSnapshot.Kind.OBJECT)
        actual.set(
            index,
            new HeapSnapshot.Record(
                record.kind(),
                record.a(),
                record.b(),
                record.c(),
                length,
                record.e(),
                record.f(),
                record.g()));
    }
    return new HeapSnapshot(
        1,
        1,
        status,
        0,
        HeapSnapshot.TAGGING,
        0,
        0,
        0,
        0,
        List.of(
            new HeapSnapshot.ClassInfo(1, HeapSnapshot.ClassKind.DATA_ARRAY, signature, List.of()),
            new HeapSnapshot.ClassInfo(
                2,
                HeapSnapshot.ClassKind.ROOTS,
                "LCase$Roots;",
                List.of(new HeapSnapshot.FieldInfo(0, Modifier.STATIC, "r", "[J")))),
        List.of(new HeapSnapshot.ArrayIdentity(100, "D", 0, 0, 1)),
        List.of(),
        actual);
  }
}
