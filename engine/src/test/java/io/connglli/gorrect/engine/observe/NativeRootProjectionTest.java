package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.NativeRootPlan;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeRootProjectionTest {
  private static final Shape SHAPE = new Shape("D", Shape.Kind.DATA_ARRAY, 0, 0);
  private static final ObservationLayout LAYOUT =
      new ObservationLayout(
          List.of(),
          List.of(SHAPE),
          new ObservationLayout.Roots("LCase$Roots;", Map.of("r", "main")),
          List.of(),
          List.of(),
          ObservationLayout.REQUIRED,
          "jvmti",
          null,
          new NativeRootPlan(List.of("left", "right")));

  @Test
  void resourcePlanIsCanonicalAndBoundedBeforeWriting(@TempDir Path directory) throws Exception {
    Path planFile = directory.resolve("native-roots.properties");
    var plan = new NativeRootPlan(List.of("left", "right"));
    plan.write(planFile);
    assertEquals(plan, NativeRootPlan.read(planFile));
    for (String text :
        List.of(
            "version=1\nroots=left,left\n",
            "version=1\nroots=left\nextra=true\n",
            "version=2\nroots=left\n")) {
      Files.writeString(planFile, text);
      assertThrows(IOException.class, () -> NativeRootPlan.read(planFile));
    }
    Path oversized = directory.resolve("oversized.properties");
    assertThrows(
        IOException.class,
        () -> new NativeRootPlan(List.of("x".repeat(NativeRootPlan.MAX_BYTES))).write(oversized));
    assertFalse(Files.exists(oversized));
  }

  @Test
  void aliasMultisetAndOpaqueHandleReuseUseActualTags() throws Exception {
    var evidence = evidence(100, false);
    assertEquals(evidence, NativeRootEvidence.read(evidence.encode(), 1, 1));
    assertEquals(89 + 64 * 2, evidence.encode().length);
    assertTrue(evidence.complete());
    var projection = project(snapshot(100, 2), evidence);
    assertEquals(ExactVerifier.Verdict.SATISFIED, projection.verify(expected(1)).verdict());
    assertTrue(projection.nativeRootsCovered());
    assertEquals(
        Value.Reference.strong(ObjectId.initial(1)),
        projection.graph().orElseThrow().roots().get("left"));
  }

  @Test
  void completeActualDisagreementIsAValidationFailure() {
    assertTrue(project(snapshot(200, 2), evidence(200, false)).nativeRootsCovered());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        project(snapshot(200, 2), evidence(200, false)).verify(expected(1)).verdict());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "extra", "wrong-target", "wrong-cut", "cleanup", "changed-bracket"})
  void gapsCannotPassAndPreserveIndependentlyCoveredRefutations(String damage) {
    var raw = snapshot(100, 2);
    var proof = evidence(100, false);
    switch (damage) {
      case "missing" -> proof = null;
      case "extra" -> raw = snapshot(100, 3);
      case "wrong-target" -> proof = evidence(200, false);
      case "wrong-cut" ->
          proof =
              new NativeRootEvidence(
                  1,
                  1,
                  7,
                  1,
                  false,
                  NativeRootPlan.MAX_GENERATIONS,
                  1,
                  0,
                  1,
                  0,
                  proof.before(),
                  proof.after());
      case "cleanup" -> proof = evidence(100, true);
      case "changed-bracket" ->
          proof =
              new NativeRootEvidence(
                  1,
                  1,
                  7,
                  1,
                  false,
                  NativeRootPlan.MAX_GENERATIONS,
                  0,
                  0,
                  0,
                  0,
                  proof.before(),
                  evidence(200, false).after());
      default -> throw new AssertionError(damage);
    }
    var projection = project(raw, proof);
    assertFalse(projection.graph().isPresent());
    assertFalse(projection.nativeRootsCovered());
    assertTrue(projection.covered().isPresent());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, projection.verify(expected(1)).verdict());
    assertEquals(ExactVerifier.Verdict.VIOLATED, projection.verify(expected(2)).verdict());
  }

  @Test
  void sourceSelectionRequiresEvidenceAndLegacyStillRejectsEveryGlobal() {
    assertFalse(SnapshotProjector.project(snapshot(100, 2), LAYOUT).graph().isPresent());
    var legacy = new ObservationLayout(LAYOUT.objects(), LAYOUT.arrays(), LAYOUT.roots());
    assertTrue(SnapshotProjector.project(snapshot(100, 2), legacy).covered().isEmpty());
  }

  @Test
  void cleanupRequiresItsOwnCompleteEmptyTableAndStructurallyCompleteZeroGlobalWalk() {
    var empty =
        List.of(
            new NativeRootEvidence.Slot(0, 1, 0, 0, 0), new NativeRootEvidence.Slot(1, 2, 0, 0, 0));
    var cleanup =
        new NativeRootEvidence(
            1, 1, 0, 1, true, NativeRootPlan.MAX_GENERATIONS, 0, 0, 0, 0, empty, empty);
    var decoded = SnapshotProjector.projectNativeCleanup(snapshot(100, 0), LAYOUT, cleanup);
    assertTrue(decoded.graph().isPresent());
    assertTrue(decoded.nativeRootsCovered());
    assertEquals(Value.Reference.NULL, decoded.graph().orElseThrow().roots().get("left"));
    assertFalse(
        SnapshotProjector.projectNativeCleanup(snapshot(100, 1), LAYOUT, cleanup)
            .graph()
            .isPresent());
    assertFalse(
        SnapshotProjector.projectNativeCleanup(snapshot(100, 0), LAYOUT, evidence(100, false))
            .graph()
            .isPresent());
    assertFalse(project(snapshot(100, 0), cleanup).graph().isPresent());
    var wrongPhase =
        new NativeRootEvidence(
            1, 1, 0, 1, false, NativeRootPlan.MAX_GENERATIONS, 0, 0, 0, 0, empty, empty);
    assertFalse(
        SnapshotProjector.projectNativeCleanup(snapshot(100, 0), LAYOUT, wrongPhase)
            .graph()
            .isPresent());
    var missingClass = snapshot(100, 0);
    missingClass =
        new HeapSnapshot(
            missingClass.run(),
            missingClass.checkpoint(),
            missingClass.status(),
            missingClass.jvmtiError(),
            missingClass.capabilities(),
            0,
            0,
            0,
            0,
            missingClass.classes(),
            missingClass.arrays(),
            missingClass.methods(),
            missingClass.records().stream()
                .filter(record -> record.kind() != HeapSnapshot.Kind.CLASS_VISIT)
                .toList());
    assertFalse(
        SnapshotProjector.projectNativeCleanup(missingClass, LAYOUT, cleanup).graph().isPresent());
  }

  @Test
  void localOriginGapsDoNotHideIndependentlyCompleteNativeRootCoverage() {
    var layout =
        new ObservationLayout(
            LAYOUT.objects(),
            LAYOUT.arrays(),
            LAYOUT.roots(),
            List.of(),
            List.of(
                new ObservationLayout.LocalBinding(
                    1, 0, "execute", "(Lio/connglli/gorrect/runtime/TargetSession;)V", "b1", "[J")),
            ObservationLayout.REQUIRED,
            "jvmti",
            null,
            LAYOUT.nativeRootPlan());
    var binding = new BindingEvidence(1, 1, 7, 0, 1, List.of(new BindingEvidence.Binding(1, true)));
    var projection =
        SnapshotProjector.projectNative(
            snapshot(100, 2), layout, List.of(binding), null, evidence(100, false));
    assertFalse(projection.graph().isPresent());
    assertTrue(projection.covered().isPresent());
    assertTrue(projection.nativeRootsCovered());
    var badGlobal =
        SnapshotProjector.projectNative(
            snapshot(100, 3), layout, List.of(binding), null, evidence(100, false));
    assertFalse(badGlobal.nativeRootsCovered());
  }

  @Test
  void malformedWireCannotBecomeAnEmptyOrSuccessfulTable() throws Exception {
    byte[] bytes = evidence(100, false).encode();
    assertThrows(
        IOException.class,
        () -> NativeRootEvidence.read(Arrays.copyOf(bytes, bytes.length - 1), 1, 1));
    assertThrows(IOException.class, () -> NativeRootEvidence.read(bytes, 2, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new NativeRootEvidence.Slot(0, 1, 42, 1, 100));
    var sameGeneration =
        List.of(
            new NativeRootEvidence.Slot(0, 1, 42, 2, 100),
            new NativeRootEvidence.Slot(1, 1, 42, 2, 100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NativeRootEvidence(
                1, 1, 7, 1, false, 100, 0, 0, 0, 0, sameGeneration, sameGeneration));
    var duplicatePast =
        List.of(
            new NativeRootEvidence.Slot(0, 4, 0, 0, 0), new NativeRootEvidence.Slot(1, 4, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NativeRootEvidence(
                1, 2, 0, 1, true, 100, 0, 0, 0, 0, duplicatePast, duplicatePast));
    var empty = List.of(new NativeRootEvidence.Slot(0, 4, 0, 0, 0));
    assertTrue(new NativeRootEvidence(1, 2, 0, 1, true, 100, 0, 0, 0, 0, empty, empty).complete());
  }

  private static SnapshotProjector.Projection project(
      HeapSnapshot snapshot, NativeRootEvidence evidence) {
    return SnapshotProjector.projectNative(snapshot, LAYOUT, List.of(), null, evidence);
  }

  private static NativeRootEvidence evidence(long target, boolean cleanup) {
    var slots =
        List.of(
            new NativeRootEvidence.Slot(0, 1, 42, 2, target),
            new NativeRootEvidence.Slot(1, 2, 42, 2, target));
    return new NativeRootEvidence(
        1, 1, 7, 1, cleanup, NativeRootPlan.MAX_GENERATIONS, 0, 0, 0, 0, slots, slots);
  }

  private static Graph expected(int main) {
    return new Graph(
        Map.of(
            ObjectId.initial(1),
            new HeapObject(SHAPE, List.of(), List.of()),
            ObjectId.initial(2),
            new HeapObject(SHAPE, List.of(), List.of())),
        Map.of(
            "main",
            Value.Reference.strong(ObjectId.initial(main)),
            "left",
            Value.Reference.strong(ObjectId.initial(1)),
            "right",
            Value.Reference.strong(ObjectId.initial(1))),
        Map.of());
  }

  private static HeapSnapshot snapshot(long target, int globals) {
    var records = new ArrayList<HeapSnapshot.Record>();
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.OBJECT, 100, 1, 16, 0, 0, 0, 0));
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.OBJECT, 200, 1, 16, 0, 0, 0, 0));
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.REFERENCE, -2, 100, 8, 0, 0, 0, 0));
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.CLASS_VISIT, 2, 0, 0, 0, 0, 0, 0));
    for (int i = 0; i < globals; i++)
      records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, target, 21, 0, -1, -1, 0, -1));
    return new HeapSnapshot(
        1,
        1,
        HeapSnapshot.Status.COMPLETE,
        0,
        HeapSnapshot.TAGGING,
        0,
        0,
        0,
        0,
        List.of(
            new HeapSnapshot.ClassInfo(1, HeapSnapshot.ClassKind.DATA_ARRAY, "[J", List.of()),
            new HeapSnapshot.ClassInfo(
                2,
                HeapSnapshot.ClassKind.ROOTS,
                "LCase$Roots;",
                List.of(new HeapSnapshot.FieldInfo(0, Modifier.STATIC, "r", "[J")))),
        List.of(
            new HeapSnapshot.ArrayIdentity(100, "D", 0, 0, 1),
            new HeapSnapshot.ArrayIdentity(200, "D", 0, 0, 2)),
        List.of(),
        records);
  }
}
