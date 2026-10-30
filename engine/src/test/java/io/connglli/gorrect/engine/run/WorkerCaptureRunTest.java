package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CaptureEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("hotspot")
class WorkerCaptureRunTest {
  @TempDir Path directory;
  private static final String PROGRAM =
      """
      gir 1; shape N object 0 1;
      program { scope { allocate caller N;
        repeat 2 { parallel {
          worker a { allocate held N; checkpoint; barrier; release held; checkpoint; }
          worker b { allocate held N; checkpoint; barrier; release held; checkpoint; }
        } }
      } checkpoint; }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void capturesCallerAndEveryWorkerAcrossGroupVisitsAndPhases(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    long token = token(run);
    var first = CaptureEvidence.read(run.resolve("snapshots/1.gcut"), token, 1);
    var second = CaptureEvidence.read(run.resolve("snapshots/2.gcut"), token, 2);
    var third = CaptureEvidence.read(run.resolve("snapshots/3.gcut"), token, 3);
    assertEquals(3, first.participants().size());
    assertEquals(
        List.of(1, 1, 1), first.participants().stream().map(p -> p.bindings().size()).toList());
    assertEquals(
        List.of(1, 0, 0), second.participants().stream().map(p -> p.bindings().size()).toList());
    assertEquals(2, second.cut());
    assertEquals(2, second.phase());
    assertEquals(2, third.round());
    assertEquals(2, third.participants().get(1).occurrence());
    assertEquals(2, third.participants().get(1).activation());
    assertTrue(Files.exists(run.resolve("snapshots/5.gbind")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-envelope",
        "participant",
        "site",
        "activation",
        "thread",
        "declaration",
        "origin"
      })
  void incompleteOrConflictingCaptureEvidenceCannotPass(String damage) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    Path changed = copy(directory, run, "changed");
    Path path = changed.resolve("snapshots/1.gcut");
    long token = token(changed);
    var evidence = CaptureEvidence.read(path, token, 1);
    if (damage.equals("missing-envelope")) Files.delete(path);
    else if (damage.equals("activation")) {
      var bytes = ByteBuffer.wrap(Files.readAllBytes(path));
      int offset = 48 + 36 + evidence.participants().getFirst().bindings().size() * 8;
      bytes.putLong(offset + 24, 999);
      Files.write(path, bytes.array());
    } else if (damage.equals("origin")) {
      Path raw = changed.resolve("snapshots/1.gsnap");
      var snapshot = SnapshotCodec.read(Files.readAllBytes(raw));
      long thread = evidence.participants().get(1).thread();
      var records =
          snapshot.records().stream()
              .filter(
                  record ->
                      record.kind() != HeapSnapshot.Kind.ROOT
                          || record.b() != 24
                          || record.c() != thread)
              .toList();
      Files.write(
          raw,
          SnapshotCodec.write(
              new HeapSnapshot(
                  snapshot.run(),
                  snapshot.checkpoint(),
                  snapshot.status(),
                  snapshot.jvmtiError(),
                  snapshot.capabilities(),
                  snapshot.gcStartsBefore(),
                  snapshot.gcFinishesBefore(),
                  snapshot.gcStartsAfter(),
                  snapshot.gcFinishesAfter(),
                  snapshot.classes(),
                  snapshot.arrays(),
                  snapshot.methods(),
                  records)));
    } else {
      var participants = new ArrayList<>(evidence.participants());
      var worker = participants.get(1);
      if (damage.equals("participant")) participants.remove(1);
      else
        participants.set(
            1,
            new CaptureEvidence.Participant(
                worker.worker(),
                worker.operation() + (damage.equals("site") ? 1 : 0),
                worker.occurrence(),
                worker.thread() + (damage.equals("thread") ? 1000 : 0),
                worker.activation() + (damage.equals("activation") ? 1 : 0),
                damage.equals("declaration")
                    ? List.<BindingEvidence.Binding>of()
                    : worker.bindings()));
      new CaptureEvidence(
              evidence.run(),
              evidence.checkpoint(),
              evidence.group(),
              evidence.round(),
              evidence.cut(),
              evidence.phase(),
              participants)
          .write(path);
    }
    var result = RunEngine.verify(changed);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    if (!damage.equals("declaration") && !damage.equals("origin"))
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
  }

  @Test
  void laterMissingEnvelopeDoesNotEraseAnEarlierGraphViolation() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    Path raw = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(raw));
    int field =
        snapshot.classes().stream()
            .flatMap(type -> type.fields().stream())
            .filter(value -> value.name().equals("d0"))
            .findFirst()
            .orElseThrow()
            .index();
    var records =
        snapshot.records().stream()
            .map(
                record ->
                    record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD && record.b() == field
                        ? new HeapSnapshot.Record(
                            record.kind(),
                            record.a(),
                            record.b(),
                            record.c(),
                            99,
                            record.e(),
                            record.f(),
                            record.g())
                        : record)
            .toList();
    Files.write(
        raw,
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                snapshot.checkpoint(),
                snapshot.status(),
                snapshot.jvmtiError(),
                snapshot.capabilities(),
                snapshot.gcStartsBefore(),
                snapshot.gcFinishesBefore(),
                snapshot.gcStartsAfter(),
                snapshot.gcFinishesAfter(),
                snapshot.classes(),
                snapshot.arrays(),
                snapshot.methods(),
                records)));
    Files.delete(run.resolve("snapshots/2.gcut"));
    var result = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution());
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void composesReferenceHistoryAndWorkerCollectionWithCallerRoots(int feature) throws IOException {
    String program =
        """
        gir 1; shape N object 1 1;
        object 1 N [weak(2)] [0]; object 2 N [null] [7]; root main = 1; root guard = 2;
        program { scope { allocate caller N; parallel {
          worker only { read-root owner N main; collect; checkpoint; release owner; }
        } } checkpoint; }
        """;
    Path run = directory.resolve("collection");
    assertPass(RunEngine.run(input(directory, program), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("collection-replay")));
    var evidence = CaptureEvidence.read(run.resolve("snapshots/1.gcut"), token(run), 1);
    assertEquals(
        List.of(1, 1), evidence.participants().stream().map(p -> p.bindings().size()).toList());
  }

  @Test
  void profilesWithoutControlledBindingCoverageRejectWorkerCuts() throws IOException {
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(
                input(directory, PROGRAM), profile(directory, 21), directory.resolve("unsupported"))
            .execution());
  }

  private Path selected(int feature) throws IOException {
    Path path = profile(directory, feature);
    var raw = JsonFiles.read(path);
    raw.addProperty("observation", "roots-diagnostic");
    raw.getAsJsonObject("limits").addProperty("historyTransitions", 10_000_000);
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    JsonFiles.write(path, raw);
    return path;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }
}
