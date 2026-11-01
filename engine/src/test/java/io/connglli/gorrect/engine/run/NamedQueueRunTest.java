package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.oracle.HistoryLimits;
import io.connglli.gorrect.engine.oracle.HistoryOracle;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.QueuePlan;
import io.connglli.gorrect.protocol.ReferenceHistory;
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

@Tag("hotspot")
class NamedQueueRunTest {
  @TempDir Path directory;
  static final String PROGRAM =
      """
      gir 1; shape N object 0 1; shape W weak; queue ready W;
      object 1 N [] [7]; root data = 1; root saved = null;
      program {
        scope {
          allocate-ref wrapped W null ready; allocate-ref witness W wrapped;
          enqueue-ref added wrapped; write-root saved witness;
        }
        collect;
        scope { read-root observed W saved; is-cleared state observed;
          acquire held W observed { same-object same held null; }
        }
        checkpoint;
        scope { read-root observed W saved; enqueue-ref failed observed;
          is-cleared state observed;
        }
        checkpoint;
        poll-queue delivered ready; write-root saved delivered;
        enqueue-ref consumed delivered; release delivered;
        poll-queue; checkpoint;
      }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void actualOwnedWrappersSurviveCollectionAndQueueConsumption(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(feature, false), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    long token = token(run);
    var history = QueueHistory.read(run.resolve("traces/queues.qhistory"), token);
    assertEquals(6, history.events().size());
    assertTrue(
        history.events().stream()
            .anyMatch(event -> event.kind() == QueueHistory.Kind.POLL && event.result() == 1));
    assertTrue(Files.exists(run.resolve("snapshots/1.gqueue")));
    assertEquals(
        HeapSnapshot.Status.COMPLETE,
        QueueCapture.read(run.resolve("snapshots/1.gqueue"), token, 1).snapshot().status());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceLifetimeProofCannotEnableUnqualifiedAutomaticCapture(boolean retained)
      throws IOException {
    String program =
        "gir 1; shape W weak; queue q W; root permanent = null; program {"
            + " allocate-ref target W null; allocate-ref r W target q; write-root permanent r; }";
    var result =
        RunEngine.run(
            input(directory, program),
            selected(21, retained),
            directory.resolve("unsupported-automatic"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    assertTrue(
        result.unmetRequirements().stream()
            .anyMatch(problem -> problem.contains("Automatic queue")),
        result.toString());
  }

  @Test
  void softOwnedWrappersUseTheSamePortableQueueContract() throws IOException {
    Path run = directory.resolve("soft");
    assertPass(
        RunEngine.run(
            input(directory, PROGRAM.replace("shape W weak", "shape W soft")),
            selected(21, false),
            run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("soft-replay")));
  }

  @Test
  void enqueueOnlySelectsAnExplicitZeroResourcePlan() throws IOException {
    String program =
        """
        gir 1; shape N object 0 0; shape W weak;
        object 1 N [] []; root main = 1;
        program { scope { read-root held N main; allocate-ref wrapper W held;
          enqueue-ref failed wrapper; is-cleared cleared wrapper; } }
        """;
    Path run = directory.resolve("enqueue-only");
    assertPass(RunEngine.run(input(directory, program), selected(21, false), run));
    assertPass(RunEngine.verify(run));
    assertTrue(QueuePlan.read(run.resolve("generated/queues.properties")).resources().isEmpty());
    assertTrue(Files.exists(run.resolve("snapshots/1.gqueue")));
  }

  @Test
  void retainedMetadataComposesWithTheSameOwnershipWalk() throws IOException {
    Path run = directory.resolve("retained");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21, true), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("retained-replay")));
    assertTrue(Files.exists(run.resolve("snapshots/1.gframe")));
    assertTrue(Files.exists(run.resolve("snapshots/1.gqueue")));
  }

  @Test
  void missingOwnershipCannotHideACoveredOrdinaryPayloadMismatch() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21, false), run));
    Path changed = copy(directory, run, "changed");
    Files.delete(changed.resolve("snapshots/1.gqueue"));
    Path file = changed.resolve("snapshots/1.gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(file));
    var records = new ArrayList<>(raw.records());
    boolean altered = false;
    for (int i = 0; i < records.size(); i++) {
      var record = records.get(i);
      if (record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
          && record.c() == 'J'
          && record.d() == 7) {
        records.set(
            i,
            new HeapSnapshot.Record(
                record.kind(),
                record.a(),
                record.b(),
                record.c(),
                8,
                record.e(),
                record.f(),
                record.g()));
        altered = true;
        break;
      }
    }
    assertTrue(altered);
    Files.write(
        file,
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
    var result = RunEngine.verify(changed);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());

    // The queue-controlled launch path must preserve the common capability-failure contract.
    for (String key : List.of("java.feature", "observer.capabilities"))
      for (boolean violation : List.of(false, true)) {
        Path malformed = copy(directory, violation ? changed : run, key + "-" + violation);
        var facts = Artifacts.target(malformed);
        facts.setProperty(key, "unknown");
        try (var output = Files.newOutputStream(malformed.resolve("target.properties"))) {
          facts.store(output, "Malformed capability evidence");
        }
        var verified = RunEngine.verify(malformed);
        assertEquals(RunReport.Outcome.EXTRACTION_ERROR, verified.execution(), verified.toString());
        assertEquals(
            violation ? ExactVerifier.Verdict.VIOLATED : ExactVerifier.Verdict.INCONCLUSIVE,
            verified.verdict(),
            verified.toString());
        assertTrue(
            verified.unmetRequirements().containsAll(List.of("tagging", "named-roots")),
            verified.toString());
        assertEquals(
            "[]", Files.readString(malformed.resolve("observation-capabilities.json")).trim());
      }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing-history", "wrong-constructor-queue", "wrong-receiver", "false-poll"})
  void damagedApiEvidenceCannotPass(String damage) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21, false), run));
    Path changed = copy(directory, run, "changed");
    Path file = changed.resolve("traces/queues.qhistory");
    if (damage.equals("missing-history")) Files.delete(file);
    else {
      var original = QueueHistory.read(file, token(changed));
      var events = new ArrayList<>(original.events());
      for (int i = 0; i < events.size(); i++) {
        var event = events.get(i);
        if (damage.equals("wrong-constructor-queue") && event.kind() == QueueHistory.Kind.CREATE) {
          events.set(
              i,
              new QueueHistory.Event(
                  0,
                  event.sequence(),
                  event.operation(),
                  event.kind(),
                  -1,
                  event.result(),
                  event.wrapper()));
          break;
        }
        if (damage.equals("wrong-receiver") && event.kind() == QueueHistory.Kind.ENQUEUE) {
          var id = event.wrapper();
          events.set(
              i,
              new QueueHistory.Event(
                  0,
                  event.sequence(),
                  event.operation(),
                  event.kind(),
                  event.queue(),
                  event.result(),
                  new io.connglli.gorrect.protocol.ReferenceHistory.Identity(
                      0, id.site() + 100, id.occurrence(), id.slot())));
          break;
        }
        if (damage.equals("false-poll") && event.kind() == QueueHistory.Kind.POLL) {
          events.set(
              i,
              new QueueHistory.Event(
                  0, event.sequence(), event.operation(), event.kind(), event.queue(), 0, null));
          break;
        }
      }
      new QueueHistory(original.run(), original.complete(), events).write(file);
    }
    assertTrue(RunEngine.verify(changed).exitStatus() != 0);
  }

  @Test
  void incompleteRuntimeQueuePrefixDoesNotBecomeAFalseRefutation() throws IOException {
    String program =
        """
        gir 1; shape W weak; queue q W;
        program { poll-queue delivered q; enqueue-ref failed delivered; }
        """;
    Path run = directory.resolve("runtime-prefix");
    var original = RunEngine.run(input(directory, program), selected(21, false), run);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, original.execution(), original.toString());
    Path changed = copy(directory, run, "missing-queue-prefix");
    long id = token(changed);
    var queue = QueueHistory.read(changed.resolve("traces/queues.qhistory"), id);
    assertEquals(false, queue.complete());
    assertEquals(1, queue.events().size());
    var missing = new QueueHistory(id, false, List.of());
    missing.write(changed.resolve("traces/queues.qhistory"));
    var result =
        HistoryOracle.checkFailure(
            GirParser.parse(program),
            ExecutionTrace.read(changed.resolve("traces/execution.gtrace"), id),
            ReferenceHistory.readTyped(changed.resolve("traces/references.gtyped"), id),
            missing,
            List.of(),
            HistoryLimits.DEFAULT);
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.problems().toString());
    assertEquals(false, result.invalidWorkload());
    var verified = RunEngine.verify(changed);
    assertEquals(RunReport.Outcome.RUNTIME_ERROR, verified.execution(), verified.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, verified.verdict(), verified.toString());
  }

  private Path selected(int feature, boolean retained) throws IOException {
    Path path = profile(directory, feature);
    var json = JsonFiles.read(path);
    json.addProperty("observation", "roots-diagnostic");
    if (retained) json.addProperty("bindingMetadata", "retained-class");
    json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    JsonFiles.write(path, json);
    return path;
  }

  static long token(Path run) throws IOException {
    return JsonFiles.number(JsonFiles.read(run.resolve("manifest.json")).get("run"));
  }
}
