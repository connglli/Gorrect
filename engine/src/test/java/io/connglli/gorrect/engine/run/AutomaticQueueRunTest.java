package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("hotspot")
class AutomaticQueueRunTest {
  @TempDir Path directory;
  private static final String PROGRAM =
      """
      gir 1; shape N object 0 1; shape W weak; queue ready W; queue other W;
      object 1 N [] [0]; object 2 N [] [7]; root target = 1; root data = 2; root saved = null;
      program {
        read-root held N target; allocate-ref r W held ready; collect;
        write-root saved r; release r; release held; write-root target null;
        repeat 12 { collect; checkpoint; }
      }
      """;

  private Path selected(boolean retained) throws IOException {
    var path = profile(directory, 17);
    var json = JsonFiles.read(path);
    json.addProperty("observation", "roots-diagnostic");
    if (retained) json.addProperty("bindingMetadata", "retained-class");
    json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m", "-Xshare:off")));
    JsonFiles.write(path, json);
    return path;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualHandlerRunOfflineAndExactClassReplayAgree(boolean retained) throws Exception {
    Path run = directory.resolve("automatic");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(retained), run));
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertEquals(
        Artifacts.hash(run.resolve("generated/classes/Case.class")),
        Artifacts.hash(replay.resolve("generated/classes/Case.class")),
        "replay must reuse exact generated class");
    long token = NamedQueueRunTest.token(run);
    boolean arrival = false;
    for (int point = 1; point <= 13; point++) {
      var queue =
          QueueCapture.read(run.resolve("snapshots/" + point + ".gqueue"), token, point).snapshot();
      var proof =
          QueueConsistency.read(run.resolve("snapshots/" + point + ".gqlock"), token, point);
      assertTrue(proof.complete());
      var type =
          queue.classes().stream()
              .filter(c -> c.signature().equals("LCase$Queue;"))
              .findFirst()
              .orElseThrow();
      int length =
          type.fields().stream()
              .filter(f -> f.name().equals("queueLength"))
              .findFirst()
              .orElseThrow()
              .index();
      arrival |=
          queue.records().stream()
              .anyMatch(
                  record ->
                      record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                          && record.a() == proof.locks().getFirst().queueTag()
                          && record.b() == length
                          && record.c() == 'J'
                          && record.d() > 0);
    }
    assertTrue(
        arrival, "bounded selected-target handler activity, not a portable progress assertion");
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var logical =
        layout.objects().stream()
            .filter(type -> type.shape().name().equals("N"))
            .findFirst()
            .orElseThrow();
    for (String damage : List.of("missing", "truncated", "wrong-lock", "cleanup", "image")) {
      var changed = copy(directory, run, damage);
      var witness = changed.resolve("snapshots/1.gqlock");
      var original = Files.readAllBytes(witness);
      if (damage.equals("missing")) Files.delete(witness);
      if (damage.equals("truncated"))
        Files.write(witness, java.util.Arrays.copyOf(original, original.length - 1));
      if (damage.equals("wrong-lock") || damage.equals("cleanup")) {
        var proof = QueueConsistency.read(original, token, 1);
        var rows = new ArrayList<>(proof.locks());
        var row = rows.getFirst();
        rows.set(
            0,
            new QueueConsistency.Lock(
                row.resource(),
                row.rootsClass(),
                row.rootField(),
                row.kind(),
                row.queueTag(),
                damage.equals("wrong-lock") ? row.queueTag() : row.lockTag(),
                row.ownerBefore(),
                row.ownerAfter(),
                row.entriesBefore(),
                row.entriesAfter(),
                damage.equals("cleanup") ? -1 : row.cleanup()));
        Files.write(
            witness,
            new QueueConsistency(
                    proof.run(),
                    proof.checkpoint(),
                    proof.thread(),
                    proof.threadTag(),
                    proof.startsBefore(),
                    proof.finishesBefore(),
                    proof.startsAfter(),
                    proof.finishesAfter(),
                    rows)
                .encode());
      }
      if (damage.equals("image")) {
        var manifest = JsonFiles.read(changed.resolve("manifest.json"));
        manifest
            .getAsJsonObject("installationArtifacts")
            .addProperty("target/lib/libjli.so", "missing");
        JsonFiles.write(changed.resolve("manifest.json"), manifest);
      }
      var result = RunEngine.verify(changed);
      assertTrue(result.exitStatus() != 0, damage + ": " + result);
      if (!damage.equals("image")) {
        var file = changed.resolve("snapshots/1.gsnap");
        var graph = SnapshotCodec.read(Files.readAllBytes(file));
        var node =
            graph.classes().stream()
                .filter(c -> c.signature().equals(logical.signature()))
                .findFirst()
                .orElseThrow();
        int data =
            node.fields().stream()
                .filter(f -> f.name().equals(logical.data().getFirst()))
                .findFirst()
                .orElseThrow()
                .index();
        var records = new ArrayList<>(graph.records());
        for (int i = 0; i < records.size(); i++) {
          var value = records.get(i);
          if (value.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
              && value.b() == data
              && value.c() == 'J'
              && value.d() == 7
              && value.e() == -node.id()) {
            records.set(
                i,
                new HeapSnapshot.Record(
                    value.kind(),
                    value.a(),
                    value.b(),
                    value.c(),
                    8,
                    value.e(),
                    value.f(),
                    value.g()));
            break;
          }
        }
        Files.write(
            file,
            SnapshotCodec.write(
                new HeapSnapshot(
                    graph.run(),
                    graph.checkpoint(),
                    graph.status(),
                    graph.jvmtiError(),
                    graph.capabilities(),
                    graph.gcStartsBefore(),
                    graph.gcFinishesBefore(),
                    graph.gcStartsAfter(),
                    graph.gcFinishesAfter(),
                    graph.classes(),
                    graph.arrays(),
                    graph.methods(),
                    records)));
        assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(changed).verdict(), damage);
      }
    }
  }

  @Test
  void unknownImagesModesAndNaturalObservationStayUnsupported() throws IOException {
    for (String mode : List.of("missing-sharing", "compiler", "collector", "image", "natural")) {
      Path path = selected(false);
      var json = JsonFiles.read(path);
      if (mode.equals("missing-sharing"))
        json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m")));
      if (mode.equals("compiler"))
        json.add(
            "vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xbatch", "-Xmx128m", "-Xshare:off")));
      if (mode.equals("collector")) json.addProperty("collector", "g1");
      if (mode.equals("image")) json.addProperty("java", System.getProperty("gorrect.java21"));
      if (mode.equals("natural")) json.addProperty("observation", "roots-natural");
      JsonFiles.write(path, json);
      var result =
          RunEngine.run(input(directory, PROGRAM), path, directory.resolve("unsupported-" + mode));
      assertEquals(RunReport.Outcome.UNSUPPORTED, result.execution(), result.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void captureQuotaCannotPassAndDoesNotBecomeAnEmptyObservedQueue(boolean retained)
      throws IOException {
    Path path = selected(retained);
    var json = JsonFiles.read(path);
    var limits = json.getAsJsonObject("limits");
    limits.addProperty("records", 1);
    JsonFiles.write(path, json);
    var program = PROGRAM.replace("repeat 12 { collect; checkpoint; }", "collect; checkpoint;");
    Path run = directory.resolve("quota");
    var result = RunEngine.run(input(directory, program), path, run);
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertTrue(
        result.problems().stream().anyMatch(problem -> problem.contains("RECORD_LIMIT")),
        result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    assertTrue(RunEngine.verify(run).exitStatus() != 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void earlierCoveredContradictionSurvivesAFailedExecutionAndRejectedConsistency(boolean retained)
      throws Exception {
    String program =
        PROGRAM.replace(
            "repeat 12 { collect; checkpoint; }",
            "collect; checkpoint; poll-queue absent other; enqueue-ref failed absent;");
    Path run = directory.resolve("failed-prefix");
    var result = RunEngine.run(input(directory, program), selected(retained), run);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, result.execution(), result.toString());
    var suffixOnly = copy(directory, run, "unlinked-only");
    Files.write(suffixOnly.resolve("snapshots/2.gsnap"), new byte[] {1});
    Files.write(suffixOnly.resolve("snapshots/3.gsnap"), new byte[] {2});
    var invalid = RunEngine.verify(suffixOnly);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, invalid.execution(), invalid.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, invalid.verdict(), invalid.toString());
    assertTrue(
        invalid.problems().stream()
            .anyMatch(problem -> problem.contains("Unlinked capture suffix")),
        invalid.toString());

    var graphFile = run.resolve("snapshots/1.gsnap");
    var graph = SnapshotCodec.read(Files.readAllBytes(graphFile));
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    String signature = layout.objects().getFirst().signature();
    var node =
        graph.classes().stream()
            .filter(type -> type.signature().equals(signature))
            .findFirst()
            .orElseThrow();
    int data =
        node.fields().stream()
            .filter(field -> field.name().equals("d0"))
            .findFirst()
            .orElseThrow()
            .index();
    var records = new ArrayList<>(graph.records());
    boolean altered = false;
    for (int i = 0; i < records.size(); i++) {
      var value = records.get(i);
      if (value.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
          && value.b() == data
          && value.d() == 7
          && value.e() == -node.id()) {
        records.set(
            i,
            new HeapSnapshot.Record(
                value.kind(), value.a(), value.b(), value.c(), 8, value.e(), value.f(), value.g()));
        altered = true;
        break;
      }
    }
    assertTrue(altered);
    Files.write(
        graphFile,
        SnapshotCodec.write(
            new HeapSnapshot(
                graph.run(),
                graph.checkpoint(),
                graph.status(),
                graph.jvmtiError(),
                graph.capabilities(),
                graph.gcStartsBefore(),
                graph.gcFinishesBefore(),
                graph.gcStartsAfter(),
                graph.gcFinishesAfter(),
                graph.classes(),
                graph.arrays(),
                graph.methods(),
                records)));
    Files.delete(run.resolve("snapshots/1.gqlock"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(run).verdict());
    Files.write(run.resolve("snapshots/2.gsnap"), new byte[] {1});
    Files.write(run.resolve("snapshots/3.gsnap"), new byte[] {2});
    var suffix = RunEngine.verify(run);
    assertEquals(ExactVerifier.Verdict.VIOLATED, suffix.verdict(), suffix.toString());
    assertTrue(
        suffix.problems().stream().anyMatch(problem -> problem.contains("Unlinked capture suffix")),
        suffix.toString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void laterRejectedGlobalCutCannotEraseAnEarlierRefutationOrProvideFallback(boolean retained)
      throws Exception {
    String program =
        PROGRAM.replace(
            "repeat 12 { collect; checkpoint; }",
            "collect; checkpoint; collect; checkpoint; poll-queue absent other; enqueue-ref failed"
                + " absent;");
    Path run = directory.resolve("two-cut-prefix");
    var result = RunEngine.run(input(directory, program), selected(retained), run);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, result.execution(), result.toString());
    copy(directory, run, "pristine-prefix");
    corruptPayload(run, 2);
    long token = NamedQueueRunTest.token(run);
    if (retained) {
      var path = run.resolve("snapshots/2.gframe");
      var frame = FrameEvidence.read(path, token, 2);
      var participants = new ArrayList<>(frame.participants());
      var caller = participants.getFirst();
      participants.set(
          0,
          new FrameEvidence.Participant(
              caller.worker(),
              caller.thread() + 1,
              caller.role(),
              caller.before(),
              caller.after()));
      new FrameEvidence(
              frame.run(),
              frame.checkpoint(),
              frame.status(),
              frame.jvmtiError(),
              frame.beforeEpoch(),
              frame.afterEpoch(),
              participants)
          .write(path);
    } else {
      var path = run.resolve("snapshots/2.gsnap");
      var graph = SnapshotCodec.read(Files.readAllBytes(path));
      Files.write(
          path,
          SnapshotCodec.write(
              new HeapSnapshot(
                  graph.run() + 1,
                  graph.checkpoint(),
                  graph.status(),
                  graph.jvmtiError(),
                  graph.capabilities(),
                  graph.gcStartsBefore(),
                  graph.gcFinishesBefore(),
                  graph.gcStartsAfter(),
                  graph.gcFinishesAfter(),
                  graph.classes(),
                  graph.arrays(),
                  graph.methods(),
                  graph.records())));
    }
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        RunEngine.verify(run).verdict(),
        "rejected cut supplied raw fallback");
    corruptPayload(run, 1);
    var verified = RunEngine.verify(run);
    assertEquals(ExactVerifier.Verdict.VIOLATED, verified.verdict(), verified.toString());
    assertEquals(RunReport.Outcome.RUNTIME_ERROR, verified.execution(), verified.toString());
  }

  @ParameterizedTest
  @CsvSource({"legacy,false", "legacy,true", "literal,false", "literal,true"})
  void sharedFailureDiagnosisRetainsLegacyAndLiteralQueueRefutations(String kind, boolean retained)
      throws Exception {
    String source =
        kind.equals("legacy")
            ? """
            gir 1; shape N object 1 1; object 1 N [weak(2)] [7]; object 2 N [null] [0];
            root main = 1; root absent = null;
            program { checkpoint; read-root missing N absent; read-data failed missing 0; }
            """
            : """
            gir 1; shape N object 0 1; shape W weak; queue q W; object 1 N [] [7];
            root main = 1; root absent = null; root saved = null;
            program { allocate-ref r W null q; write-root saved r; release r;
              checkpoint; read-root missing N absent; read-data failed missing 0; }
            """;
    Path run = directory.resolve("shared-prefix-" + kind);
    var original = RunEngine.run(input(directory, source), selected(retained), run);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, original.execution(), original.toString());
    corruptPayload(run, 1);
    var result = RunEngine.verify(run);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
    assertEquals(RunReport.Outcome.RUNTIME_ERROR, result.execution(), result.toString());
  }

  private static void corruptPayload(Path run, int checkpoint) throws IOException {
    var file = run.resolve("snapshots/" + checkpoint + ".gsnap");
    var graph = SnapshotCodec.read(Files.readAllBytes(file));
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var logical =
        layout.objects().stream()
            .filter(type -> type.shape().name().equals("N"))
            .findFirst()
            .orElseThrow();
    var node =
        graph.classes().stream()
            .filter(type -> type.signature().equals(logical.signature()))
            .findFirst()
            .orElseThrow();
    int data =
        node.fields().stream()
            .filter(field -> field.name().equals(logical.data().getFirst()))
            .findFirst()
            .orElseThrow()
            .index();
    var records = new ArrayList<>(graph.records());
    boolean altered = false;
    for (int i = 0; i < records.size(); i++) {
      var value = records.get(i);
      if (value.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
          && value.b() == data
          && value.d() == 7
          && value.e() == -node.id()) {
        records.set(
            i,
            new HeapSnapshot.Record(
                value.kind(), value.a(), value.b(), value.c(), 8, value.e(), value.f(), value.g()));
        altered = true;
        break;
      }
    }
    assertTrue(altered);
    Files.write(
        file,
        SnapshotCodec.write(
            new HeapSnapshot(
                graph.run(),
                graph.checkpoint(),
                graph.status(),
                graph.jvmtiError(),
                graph.capabilities(),
                graph.gcStartsBefore(),
                graph.gcFinishesBefore(),
                graph.gcStartsAfter(),
                graph.gcFinishesAfter(),
                graph.classes(),
                graph.arrays(),
                graph.methods(),
                records)));
  }
}
