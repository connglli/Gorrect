package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
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
import org.junit.jupiter.params.provider.ValueSource;

/** Actual generated class provenance, parked role coordinates and offline no-fallback checks. */
@Tag("hotspot")
class RetainedCaptureRunTest {
  @TempDir Path directory;
  static final String PROGRAM =
      """
      gir 1; shape N object 0 1; root main = null;
      program { scope { allocate caller N; write-data caller 0 7;
        read-root empty N main; checkpoint;
        repeat 2 { parallel {
          worker a { allocate held N; checkpoint; release held; checkpoint; }
          worker b { read-root empty N main; checkpoint; release empty; checkpoint; }
        } }
      } checkpoint; }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void certifiesGeneratedCallerTwoWorkersNullsAndTerminalMainWithFreshReplay(int feature)
      throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(feature), run));
    assertPass(RunEngine.verify(run));
    long token = token(run);
    var code =
        CodeEvidence.read(run.resolve("traces/code.gcode"), token, CodeEvidence.Stage.STARTUP);
    assertArrayEquals(Files.readAllBytes(run.resolve("generated/classes/Case.class")), code.code());
    var cut = FrameEvidence.read(run.resolve("snapshots/2.gframe"), token, 2);
    assertEquals(
        List.of(FrameEvidence.Role.EXECUTE, FrameEvidence.Role.WORKER, FrameEvidence.Role.WORKER),
        cut.participants().stream().map(FrameEvidence.Participant::role).toList());
    var terminal = FrameEvidence.read(run.resolve("snapshots/7.gframe"), token, 7);
    assertEquals(FrameEvidence.Role.MAIN, terminal.participants().getFirst().role());
    assertEquals(
        List.of(), BindingEvidence.read(run.resolve("snapshots/7.gbind"), token, 7).bindings());
    assertEquals(
        0, Artifacts.snapshot(run, token, 2).capabilities() & HeapSnapshot.LOCAL_VARIABLES);
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertNotEquals(token, token(replay));
    assertPass(RunEngine.verify(replay));
    assertTrue(
        Files.readString(run.resolve("observation-capabilities.json")).contains("retained-class"));
  }

  @Test
  void emptyProgramStillCertifiesMainAndCannotOmitFinalStatus() throws IOException {
    Path run = directory.resolve("empty");
    Path profile = selected(21);
    var settings = JsonFiles.read(profile);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("tagging")));
    JsonFiles.write(profile, settings);
    assertPass(RunEngine.run(input(directory, "gir 1; program {}"), profile, run));
    assertEquals(
        FrameEvidence.Role.MAIN,
        FrameEvidence.read(run.resolve("snapshots/1.gframe"), token(run), 1)
            .participants()
            .getFirst()
            .role());
    Files.delete(run.resolve("traces/code-final.gcode"));
    var result = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
  }

  @Test
  void missingFramesAndFinalStatusPreserveEarlierIndependentRefutations() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    Path missing = copy(directory, run, "missing");
    Files.delete(missing.resolve("snapshots/2.gframe"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
    Path violated = copy(directory, run, "violated");
    Path raw = violated.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(raw));
    var records = new ArrayList<>(snapshot.records());
    for (int index = 0; index < records.size(); index++) {
      var record = records.get(index);
      if (record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD && record.d() == 7)
        records.set(
            index,
            new HeapSnapshot.Record(
                record.kind(),
                record.a(),
                record.b(),
                record.c(),
                8,
                record.e(),
                record.f(),
                record.g()));
    }
    Files.write(
        raw,
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                snapshot.checkpoint(),
                snapshot.status(),
                0,
                snapshot.capabilities(),
                snapshot.gcStartsBefore(),
                snapshot.gcFinishesBefore(),
                snapshot.gcStartsAfter(),
                snapshot.gcFinishesAfter(),
                snapshot.classes(),
                snapshot.arrays(),
                snapshot.methods(),
                records)));
    Files.delete(violated.resolve("traces/code-final.gcode"));
    var result = RunEngine.verify(violated);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution());
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict());
  }

  @ParameterizedTest
  @ValueSource(strings = {"weak", "soft"})
  void firstClassWrapperAliasesKeepCanonicalStateUnderRetainedProjection(String kind)
      throws IOException {
    String program =
        """
        gir 1; shape N object 0 1; shape W %s;
        object 1 N [] [7]; root anchor = 1; root box = null;
        program { scope {
          read-root target N anchor; allocate-ref wrapper W target; write-root box wrapper; checkpoint;
          read-root alias W box; same-object same wrapper alias;
          clear-ref alias; is-cleared cleared wrapper; checkpoint;
        } checkpoint; }
        """
            .formatted(kind);
    Path run = directory.resolve("wrapper");
    assertPass(RunEngine.run(input(directory, program), selected(21), run));
    assertTrue(Files.exists(run.resolve("traces/references.gtyped")));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("wrapper-replay")));
  }

  @Test
  void coveredCallerMismatchSurvivesAnUnrelatedMissingWorkerOrigin() throws IOException {
    Path original = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), original));
    for (String damage : List.of("payload", "origin", "both")) {
      Path changed = copy(directory, original, damage);
      long token = token(changed);
      var participants =
          io.connglli.gorrect.protocol.CaptureEvidence.read(
                  changed.resolve("snapshots/2.gcut"), token, 2)
              .participants();
      long worker = participants.get(1).thread();
      Path path = changed.resolve("snapshots/2.gsnap");
      var raw = SnapshotCodec.read(Files.readAllBytes(path));
      var records = new ArrayList<HeapSnapshot.Record>();
      for (var record : raw.records()) {
        if (!damage.equals("payload")
            && record.kind() == HeapSnapshot.Kind.ROOT
            && record.b() == 24
            && record.c() == worker) continue;
        if (!damage.equals("origin")
            && record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
            && record.c() == 74
            && record.d() == 7)
          record =
              new HeapSnapshot.Record(
                  record.kind(),
                  record.a(),
                  record.b(),
                  record.c(),
                  8,
                  record.e(),
                  record.f(),
                  record.g());
        records.add(record);
      }
      Files.write(
          path,
          SnapshotCodec.write(
              new HeapSnapshot(
                  raw.run(),
                  raw.checkpoint(),
                  raw.status(),
                  raw.jvmtiError(),
                  raw.capabilities(),
                  raw.gcStartsBefore(),
                  raw.gcFinishesBefore(),
                  raw.gcStartsAfter(),
                  raw.gcFinishesAfter(),
                  raw.classes(),
                  raw.arrays(),
                  raw.methods(),
                  records)));
      assertEquals(
          damage.equals("origin")
              ? ExactVerifier.Verdict.INCONCLUSIVE
              : ExactVerifier.Verdict.VIOLATED,
          RunEngine.verify(changed).verdict(),
          damage);
    }
  }

  @Test
  void explicitUnreviewedLaunchEvidenceCannotCertifyRetainedMetadata() throws IOException {
    Path original = directory.resolve("launch-original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), original));
    for (String damage : List.of("agent", "environment")) {
      Path changed = copy(directory, original, "launch-" + damage);
      if (damage.equals("agent")) {
        Path path = changed.resolve("target.properties");
        var facts = new java.util.Properties();
        try (var input = Files.newInputStream(path)) {
          facts.load(input);
        }
        int count = Integer.parseInt(facts.getProperty("vm.arguments.count"));
        facts.setProperty("vm.argument." + count, "-agentlib:unreviewed");
        facts.setProperty("vm.arguments.count", Integer.toString(count + 1));
        try (var output = Files.newOutputStream(path)) {
          facts.store(output, "contradictory effective input");
        }
      } else {
        var manifest = JsonFiles.read(changed.resolve("manifest.json"));
        manifest.getAsJsonObject("environment").addProperty("LD_PRELOAD", "unreviewed.so");
        JsonFiles.write(changed.resolve("manifest.json"), manifest);
      }
      var result = RunEngine.verify(changed);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    }
  }

  @Test
  void duplicateArgumentEnvelopeCannotHideAnIndependentNamedRootMismatch() throws IOException {
    Path profile = selected(21);
    var settings = JsonFiles.read(profile);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("tagging")));
    JsonFiles.write(profile, settings);
    Path run = directory.resolve("duplicate-evidence");
    assertPass(
        RunEngine.run(
            input(
                directory,
                "gir 1; shape N object 0 1; object 1 N [] [7]; root main = 1; program { checkpoint;"
                    + " }"),
            profile,
            run));
    Path snapshot = run.resolve("snapshots/1.gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(snapshot));
    var records =
        raw.records().stream()
            .map(
                record ->
                    record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                            && record.c() == 74
                            && record.d() == 7
                        ? new HeapSnapshot.Record(
                            record.kind(),
                            record.a(),
                            record.b(),
                            record.c(),
                            8,
                            record.e(),
                            record.f(),
                            record.g())
                        : record)
            .toList();
    Files.write(
        snapshot,
        SnapshotCodec.write(
            new HeapSnapshot(
                raw.run(),
                raw.checkpoint(),
                raw.status(),
                raw.jvmtiError(),
                raw.capabilities(),
                raw.gcStartsBefore(),
                raw.gcFinishesBefore(),
                raw.gcStartsAfter(),
                raw.gcFinishesAfter(),
                raw.classes(),
                raw.arrays(),
                raw.methods(),
                records)));
    Files.writeString(
        run.resolve("target.properties"),
        "\nvm.argument.0=duplicate\n",
        java.nio.file.StandardOpenOption.APPEND);
    var result = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
  }

  private Path selected(int feature) throws IOException {
    Path path = profile(directory, feature);
    var selected = JsonFiles.read(path);
    selected.addProperty("observation", "roots-diagnostic");
    selected.addProperty("bindingMetadata", "retained-class");
    selected.add("requirements", JsonFiles.GSON.toJsonTree(List.of("tagging", "local-bindings")));
    var limits = selected.getAsJsonObject("limits");
    limits.addProperty("startupSeconds", 30);
    limits.addProperty("runSeconds", 60);
    limits.addProperty("historyTransitions", 1_000_000);
    JsonFiles.write(path, selected);
    return path;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.number(JsonFiles.read(run.resolve("manifest.json")).get("run"));
  }
}
