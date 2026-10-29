package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ObserverArtifactsTest {
  @Test
  void validatesRealNativeCaptures() throws Exception {
    for (String directory :
        System.getProperty("gorrect.observer.artifacts").split(Pattern.quote(File.pathSeparator))) {
      ObserverArtifactCheck.verify(Path.of(directory));
      var local = ObserverArtifactCheck.read(Path.of(directory), "local", 4);
      assertTrue(
          local.records().stream()
              .anyMatch(record -> record.kind() == HeapSnapshot.Kind.ROOT && record.b() == 24),
          "Default compatibility target must report stack-root evidence");
    }
  }

  @Test
  void corruptedEvidenceCannotPass() throws Exception {
    String directory =
        System.getProperty("gorrect.observer.artifacts")
            .split(Pattern.quote(File.pathSeparator))[0];
    var initial = ObserverArtifactCheck.read(Path.of(directory), "initial", 1);
    var node =
        initial.classes().stream()
            .filter(type -> type.signature().endsWith("$Node;"))
            .findFirst()
            .orElseThrow();
    int data =
        node.fields().stream()
            .filter(field -> field.name().equals("d0"))
            .findFirst()
            .orElseThrow()
            .index();
    int identity =
        node.fields().stream()
            .filter(field -> field.name().equals("idOccurrence"))
            .findFirst()
            .orElseThrow()
            .index();
    var first =
        initial.records().stream()
            .filter(
                record ->
                    record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                        && record.b() == identity
                        && record.d() == 1)
            .findFirst()
            .orElseThrow();
    long tag = first.a();
    var changedData =
        initial.records().stream()
            .map(
                record ->
                    record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                            && record.a() == tag
                            && record.b() == data
                        ? new HeapSnapshot.Record(
                            record.kind(),
                            record.a(),
                            record.b(),
                            record.c(),
                            123,
                            record.e(),
                            record.f(),
                            record.g())
                        : record)
            .toList();
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(copy(initial, changedData)));
    var missingData =
        initial.records().stream()
            .filter(
                record ->
                    !(record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                        && record.a() == tag
                        && record.b() == data))
            .toList();
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verify(copy(initial, missingData)));
    int reference =
        node.fields().stream()
            .filter(field -> field.name().equals("f0"))
            .findFirst()
            .orElseThrow()
            .index();
    var cleared =
        initial.records().stream()
            .filter(
                record ->
                    !(record.kind() == HeapSnapshot.Kind.REFERENCE
                        && record.a() == tag
                        && record.d() == reference
                        && record.c() == 2))
            .toList();
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(copy(initial, cleared)));
    var duplicate = new ArrayList<>(initial.records());
    duplicate.add(
        new HeapSnapshot.Record(
            first.kind(),
            first.a(),
            first.b(),
            first.c(),
            first.d() + 1,
            first.e(),
            first.f(),
            first.g()));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verify(copy(initial, duplicate)));
    var unknown = new ArrayList<>(initial.records());
    unknown.add(
        new HeapSnapshot.Record(HeapSnapshot.Kind.REFERENCE, tag, tag, 2, 999, -node.id(), 0, 0));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verify(copy(initial, unknown)));
    var global = new ArrayList<>(initial.records());
    global.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, tag, 21, 0, -1, -1, 0, -1));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verify(copy(initial, global)));
    var noVisit =
        initial.records().stream()
            .filter(record -> record.kind() != HeapSnapshot.Kind.CLASS_VISIT)
            .toList();
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verify(copy(initial, noVisit)));
  }

  private static ExactVerifier.Verdict verify(HeapSnapshot snapshot) {
    return SnapshotProjector.project(snapshot, ObserverArtifactCheck.LAYOUT)
        .verify(ObserverArtifactCheck.expected(false))
        .verdict();
  }

  private static HeapSnapshot copy(HeapSnapshot original, List<HeapSnapshot.Record> records) {
    return new HeapSnapshot(
        original.run(),
        original.checkpoint(),
        original.status(),
        original.jvmtiError(),
        original.capabilities(),
        original.gcStartsBefore(),
        original.gcFinishesBefore(),
        original.gcStartsAfter(),
        original.gcFinishesAfter(),
        original.classes(),
        original.arrays(),
        original.methods(),
        records);
  }
}
