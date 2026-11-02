package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static io.connglli.gorrect.engine.run.RunTestSupport.rewriteSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("hotspot")
class NativeRootRunTest {
  static final String SOURCE =
      """
      gir 1; shape N object 0 1;
      object 1 N [] [42]; object 2 N [] [7];
      root main = 1; root witness = 2; root saved jni-global = null;
      program {
        scope { read-root n N main; write-root saved n; }
        write-root main null; collect; held: checkpoint;
        scope {
          read-root recovered N saved; read-data value recovered 0;
          write-root saved null; read-data surviving recovered 0;
        }
        deleted: checkpoint;
      }
      """;

  @TempDir Path directory;

  static Stream<Arguments> providers() {
    return Stream.of(17, 21)
        .flatMap(
            feature ->
                Stream.of("jvmti", "retained-class")
                    .map(provider -> Arguments.of(feature, provider)));
  }

  @ParameterizedTest
  @MethodSource("providers")
  void runsVerifiesAndReplaysNativeRetention(int feature, String provider) throws IOException {
    Path run = run(feature, provider, "run");
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    var evidence = NativeRootEvidence.read(run.resolve("snapshots/1.gnative"), token(run), 1);
    assertTrue(evidence.before().getFirst().handle() != 0);
    assertTrue(Files.isRegularFile(run.resolve("traces/native-cleanup.gsnap")));
    var cleanup =
        NativeRootEvidence.read(run.resolve("traces/native-cleanup.gnative"), token(run), 4);
    assertTrue(cleanup.cleanup() && cleanup.complete());
    assertEquals(0, cleanup.before().getFirst().handle());
  }

  @Test
  void nativeEvidenceAndCleanupGapsPreserveIndependentJavaRefutations() throws IOException {
    Path run = run(21, "jvmti", "original");
    for (String gap : List.of("capture", "site", "visit", "cleanup", "qualification")) {
      Path damaged = copy(directory, run, gap);
      if (gap.equals("capture")) Files.delete(damaged.resolve("snapshots/1.gnative"));
      else if (gap.equals("cleanup"))
        Files.delete(damaged.resolve("traces/native-cleanup.gnative"));
      else if (gap.equals("qualification")) {
        Path facts = damaged.resolve("target.properties");
        Files.writeString(
            facts,
            Files.readString(facts)
                .replace(
                    "observer.nativeRoots.qualified=true", "observer.nativeRoots.qualified=false"));
      } else {
        var evidence =
            NativeRootEvidence.read(damaged.resolve("snapshots/1.gnative"), token(run), 1);
        Files.write(
            damaged.resolve("snapshots/1.gnative"),
            new NativeRootEvidence(
                    evidence.run(),
                    evidence.checkpoint(),
                    evidence.site() + (gap.equals("site") ? 1 : 0),
                    evidence.visit() + (gap.equals("visit") ? 1 : 0),
                    evidence.cleanup(),
                    evidence.generationLimit(),
                    evidence.startsBefore(),
                    evidence.finishesBefore(),
                    evidence.startsAfter(),
                    evidence.finishesAfter(),
                    evidence.before(),
                    evidence.after())
                .encode());
      }
      var missing = RunEngine.verify(damaged);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, missing.execution(), gap + ": " + missing);
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, missing.verdict(), gap + ": " + missing);
      rewriteSnapshot(damaged, false);
      var refuted = RunEngine.verify(damaged);
      assertEquals(ExactVerifier.Verdict.VIOLATED, refuted.verdict(), gap + ": " + refuted);
    }
  }

  @Test
  void rejectedCutsCannotPoisonLaterNativeChronology() throws IOException {
    Path original = run(21, "jvmti", "original");
    for (String damage : List.of("extra-global", "missing-roots-visit", "valid-first")) {
      Path run = copy(directory, original, damage);
      var evidence = NativeRootEvidence.read(run.resolve("snapshots/1.gnative"), token(run), 1);
      var slot = evidence.before().getFirst();
      nativeRows(
          run,
          1,
          List.of(new NativeRootEvidence.Slot(0, 100, slot.handle(), slot.kind(), slot.tag())));
      var first = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
      var records = new ArrayList<>(first.records());
      if (damage.equals("extra-global"))
        records.add(
            records.stream()
                .filter(record -> record.kind() == HeapSnapshot.Kind.ROOT && record.b() == 21)
                .findFirst()
                .orElseThrow());
      else if (damage.equals("missing-roots-visit")) {
        long roots =
            first.classes().stream()
                .filter(type -> type.kind() == HeapSnapshot.ClassKind.ROOTS)
                .findFirst()
                .orElseThrow()
                .id();
        assertTrue(
            records.removeIf(
                record -> record.kind() == HeapSnapshot.Kind.CLASS_VISIT && record.a() == roots));
      }
      snapshot(run, first, records);
      if (damage.equals("missing-roots-visit")) rewriteSnapshot(run, false);
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(run).verdict(), damage);

      var later = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/2.gsnap")));
      var layout =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
      var roots =
          later.classes().stream()
              .filter(type -> type.signature().equals(layout.roots().signature()))
              .findFirst()
              .orElseThrow();
      String witness =
          layout.roots().fields().entrySet().stream()
              .filter(entry -> entry.getValue().equals("witness"))
              .findFirst()
              .orElseThrow()
              .getKey();
      long field =
          roots.fields().stream()
              .filter(info -> info.name().equals(witness))
              .findFirst()
              .orElseThrow()
              .index();
      long target =
          later.records().stream()
              .filter(
                  record ->
                      record.kind() == HeapSnapshot.Kind.REFERENCE
                          && record.a() == -roots.id()
                          && record.c() == 8
                          && record.d() == field)
              .findFirst()
              .orElseThrow()
              .b();
      nativeRows(run, 2, List.of(new NativeRootEvidence.Slot(0, 1, slot.handle(), 2, target)));
      var laterRecords = new ArrayList<>(later.records());
      laterRecords.add(
          new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, target, 21, 0, -1, -1, 0, -1));
      snapshot(run, later, laterRecords);
      var report = RunEngine.verify(run);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, report.execution(), damage + ": " + report);
      if (damage.equals("valid-first")) {
        assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict(), report.toString());
        for (int index = 0; index < laterRecords.size(); index++) {
          var record = laterRecords.get(index);
          if (record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD && record.d() == 7) {
            laterRecords.set(
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
            break;
          }
        }
        snapshot(run, later, laterRecords);
        report = RunEngine.verify(run);
      }
      assertEquals(ExactVerifier.Verdict.VIOLATED, report.verdict(), damage + ": " + report);
    }
  }

  @Test
  void failedNativePrefixRetainsEarlierRefutationAfterLaterEvidenceDamage() throws IOException {
    Path run = run(21, "jvmti", "original");
    rewriteSnapshot(run, false);
    Files.delete(run.resolve("snapshots/2.gnative"));
    Files.delete(run.resolve("traces/native-cleanup.gnative"));
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run));
    var control = JsonFiles.read(run.resolve("traces/control.json"));
    var messages =
        new ArrayList<>(
            List.of(JsonFiles.GSON.fromJson(control.get("messages"), ControlMessage[].class)));
    var first = messages.get(1);
    int cut = 0;
    while (trace.events().get(cut).kind() != ExecutionTrace.Kind.OPERATION
        || trace.events().get(cut).operation() != first.operation()) cut++;
    new ExecutionTrace(trace.run(), false, trace.events().subList(0, cut + 1))
        .write(run.resolve("traces/execution.gtrace"));
    messages =
        new ArrayList<>(
            List.of(
                messages.getFirst(),
                first,
                new ControlMessage(
                    ControlMessage.Kind.FAILED,
                    first.run(),
                    first.sequence(),
                    0,
                    0,
                    "injected cleanup failure")));
    control.add("messages", JsonFiles.GSON.toJsonTree(messages));
    JsonFiles.write(run.resolve("traces/control.json"), control);
    JsonFiles.write(
        run.resolve("execution.json"),
        RunReport.failure(RunReport.Outcome.RUNTIME_ERROR, "injected cleanup failure"));
    var report = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.RUNTIME_ERROR, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, report.verdict(), report.toString());
    for (String gap : List.of("control", "trace")) {
      Path damaged = copy(directory, run, "prefix-" + gap);
      if (gap.equals("trace")) Files.delete(damaged.resolve("traces/execution.gtrace"));
      else {
        messages.set(
            1,
            new ControlMessage(
                first.kind(),
                first.run(),
                first.sequence(),
                first.operation() + 1,
                first.occurrence(),
                first.detail()));
        control.add("messages", JsonFiles.GSON.toJsonTree(messages));
        JsonFiles.write(damaged.resolve("traces/control.json"), control);
      }
      var unlinked = RunEngine.verify(damaged);
      assertEquals(RunReport.Outcome.RUNTIME_ERROR, unlinked.execution(), gap + ": " + unlinked);
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, unlinked.verdict(), gap + ": " + unlinked);
    }
  }

  private Path run(int feature, String provider, String name) throws IOException {
    Path profile = profile(directory, feature);
    var raw = JsonFiles.read(profile);
    raw.addProperty("observation", "roots-diagnostic");
    raw.addProperty("bindingMetadata", provider);
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion")));
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m", "-Xshare:off")));
    JsonFiles.write(profile, raw);
    Path run = directory.resolve(name);
    assertPass(RunEngine.run(input(directory, SOURCE), profile, run));
    return run;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }

  private static void nativeRows(Path run, long sequence, List<NativeRootEvidence.Slot> rows)
      throws IOException {
    Path path = run.resolve("snapshots/" + sequence + ".gnative");
    var evidence = NativeRootEvidence.read(path, token(run), sequence);
    Files.write(
        path,
        new NativeRootEvidence(
                evidence.run(),
                evidence.checkpoint(),
                evidence.site(),
                evidence.visit(),
                evidence.cleanup(),
                evidence.generationLimit(),
                evidence.startsBefore(),
                evidence.finishesBefore(),
                evidence.startsAfter(),
                evidence.finishesAfter(),
                rows,
                rows)
            .encode());
  }

  private static void snapshot(Path run, HeapSnapshot snapshot, List<HeapSnapshot.Record> records)
      throws IOException {
    Files.write(
        run.resolve("snapshots/" + snapshot.checkpoint() + ".gsnap"),
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
  }
}
