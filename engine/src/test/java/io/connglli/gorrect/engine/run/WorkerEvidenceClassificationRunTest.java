package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Extraction diagnostics and graph refutations remain independent across capture paths. */
@Tag("hotspot")
class WorkerEvidenceClassificationRunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"worker", "history-main", "exact-main"})
  void incompleteWorkerEvidenceCannotEraseOrHideAGraphRefutation(String mode) throws IOException {
    String program =
        mode.equals("worker")
            ? """
            gir 1; shape N object 0 1;
            program { scope { allocate caller N; parallel {
              worker a { allocate held N; checkpoint; release held; }
              worker b { allocate held N; checkpoint; release held; }
            } } }
            """
            : """
            gir 1; shape N object 0 1; object 1 N [] [0]; root main = 1;
            program { parallel { worker only { %s held N main; release held; } } checkpoint; }
            """
                .formatted(mode.equals("history-main") ? "read-volatile-root" : "read-root");
    Path selected = profile(directory, 21);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", "roots-diagnostic");
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    JsonFiles.write(selected, raw);
    Path original = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, program), selected, original));
    assertPass(RunEngine.verify(original));

    Path mismatch = copy(directory, original, "mismatch");
    damagePayload(mismatch);
    var graph = RunEngine.verify(mismatch);
    assertEquals(RunReport.Outcome.COMPLETED, graph.execution(), graph.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, graph.verdict(), graph.toString());

    Path incomplete = copy(directory, original, "incomplete");
    incompleteEvidence(incomplete);
    var gap = RunEngine.verify(incomplete);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, gap.execution(), gap.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, gap.verdict(), gap.toString());
    assertIncompleteDiagnostic(gap);

    Path combined = copy(directory, mismatch, "combined");
    incompleteEvidence(combined);
    var both = RunEngine.verify(combined);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, both.execution(), both.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, both.verdict(), both.toString());
    assertIncompleteDiagnostic(both);
    assertTrue(both.problems().containsAll(graph.problems()), both.toString());
  }

  private static void incompleteEvidence(Path run) throws IOException {
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    Path path = run.resolve("traces/workers.gevidence");
    var evidence = ExecutionEvidence.read(path, token);
    new ExecutionEvidence(token, false, evidence.groups(), evidence.windows()).write(path);
  }

  private static void assertIncompleteDiagnostic(RunReport report) {
    assertTrue(
        report.problems().stream()
            .anyMatch(problem -> problem.contains("Worker synchronization evidence is incomplete")),
        report.toString());
  }

  private static void damagePayload(Path run) throws IOException {
    Path path = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(path));
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
        path,
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
