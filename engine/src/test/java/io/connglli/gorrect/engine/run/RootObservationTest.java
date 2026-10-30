package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationCapabilities;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real local roots, independent semantic uses, and retained evidence failure boundaries. */
@Tag("hotspot")
class RootObservationTest {
  @TempDir Path directory;
  private static final String LOCALS =
      """
      gir 1; shape N object 1 1; root main = null;
      program { scope {
        allocate held N; write-ref held 0 held; write-data held 0 7;
        allocate dead N; read-root absent N main;
        checkpoint; collect;
        same-object equal held held; same-object nil absent null;
        read-data value held 0; checkpoint;
      } }
      """;

  @ParameterizedTest
  @CsvSource({
    "17, roots-diagnostic",
    "21, roots-diagnostic",
    "17, roots-natural",
    "21, roots-natural"
  })
  void verifiesLocalGraphsPresenceAndSemanticUses(int feature, String observation)
      throws IOException {
    Path selected = selected(feature, observation), run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, LOCALS), selected, run));
    assertEquals(LOCALS, Files.readString(run.resolve("case.gir")));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    var manifest = JsonFiles.read(run.resolve("manifest.json"));
    long token = manifest.get("run").getAsLong();
    var first = BindingEvidence.read(run.resolve("snapshots/1.gbind"), token, 1);
    var second = BindingEvidence.read(run.resolve("snapshots/2.gbind"), token, 2);
    assertEquals(observation.equals("roots-natural") ? 2 : 3, first.bindings().size());
    assertEquals(observation.equals("roots-natural") ? 0 : 3, second.bindings().size());
    assertTrue(first.bindings().stream().anyMatch(binding -> !binding.present()));
    String source = Files.readString(run.resolve("generated/Case.java"));
    assertEquals(
        observation.equals("roots-diagnostic"), source.contains("Reference.reachabilityFence"));
    assertTrue(
        Files.readString(run.resolve("observation-capabilities.json"))
            .contains("\"verified\": true"));
  }

  @Test
  void missingPhysicalOriginsRemainInconclusiveAndCannotBeReplacedByACapabilityReport()
      throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, LOCALS), selected(21, "roots-diagnostic"), run));
    Path damaged = copy(directory, run, "missing-origins");
    Path file = damaged.resolve("snapshots/1.gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(file));
    var records =
        raw.records().stream().filter(record -> record.kind() != HeapSnapshot.Kind.ROOT).toList();
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
    Files.writeString(damaged.resolve("observation-capabilities.json"), "[{\"verified\":true}]");
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(damaged).verdict());
    var capabilities =
        JsonFiles.GSON.fromJson(
            Files.readString(damaged.resolve("observation-capabilities.json")),
            ObservationCapabilities.Capability[].class);
    for (var capability : capabilities) {
      if (capability.mechanism().equals("alias-checks")) assertTrue(capability.verified());
      if (capability.mechanism().equals("local-bindings")) assertFalse(capability.verified());
    }
    Files.delete(damaged.resolve("snapshots/1.gbind"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(damaged).execution());
  }

  @Test
  void probeExercisesRequestedBindingAndAliasCapabilities() throws IOException {
    Path run = directory.resolve("probe");
    assertPass(RunEngine.probe(selected(21, "roots-natural"), run));
    String report = Files.readString(run.resolve("observation-capabilities.json"));
    assertFalse(report.contains("\"exercised\": false"), report);
    assertFalse(report.contains("\"verified\": false"), report);
  }

  @Test
  void checksWorkerAndSuspendedCallerUsesAcrossCollectionAndBarriers() throws IOException {
    String program =
        """
        gir 1; shape N object 0 2;
        object 1 N [] [0, 0]; object 2 N [] [0, 0]; root left = 1; root right = 2;
        program { scope {
          allocate caller N; write-data caller 0 99;
          parallel {
            worker left {
              read-root owner N left; allocate local N; write-data local 0 7;
              collect; barrier; read-data value local 0; same-object alias local local;
              write-data owner 0 value; write-data owner 1 alias;
            }
            worker right {
              read-root owner N right; allocate local N; write-data local 0 11;
              collect; barrier; read-data value local 0; same-object alias local local;
              write-data owner 0 value; write-data owner 1 alias;
            }
          }
          checkpoint; read-data survived caller 0;
        } }
        """;
    Path run = directory.resolve("workers");
    assertPass(RunEngine.run(input(directory, program), selected(21, "roots-natural"), run));
    assertPass(RunEngine.verify(run));
  }

  @Test
  void comparesDifferentShapesAndCapturesDiagnosticArrayLocals() throws IOException {
    String program =
        """
        gir 1; shape N object 0 0; shape M object 0 0; shape R refs 1;
        program { scope {
          allocate first N; allocate other M; allocate array R;
          write-ref array 0 first; checkpoint;
          same-object different first other; same-object shapes first array;
          read-ref alias N array 0; same-object equal alias first;
        } }
        """;
    Path run = directory.resolve("array-locals");
    var path = selected(21, "roots-diagnostic");
    var raw = JsonFiles.read(path);
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings", "alias-checks")));
    JsonFiles.write(path, raw);
    assertPass(RunEngine.run(input(directory, program), path, run));
  }

  @Test
  void rejectsUnsupportedProfileCombinationsBeforeCompilation() throws IOException {
    Path selected = selected(21, "roots-natural");
    String program = "gir 1; shape R refs 1; program { allocate r R; }";
    Path run = directory.resolve("unsupported");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, program), selected, run).execution());
    assertFalse(Files.exists(run.resolve("logs/compiler-command.json")));
  }

  @Test
  void availabilityDoesNotSatisfyAnUnexercisedRequirement() throws IOException {
    var result =
        RunEngine.run(
            input(directory, "gir 1; program { checkpoint; }"),
            selected(21, "roots-diagnostic"),
            directory.resolve("not-exercised"));
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict());
    assertTrue(
        result
            .unmetRequirements()
            .containsAll(List.of("local-bindings", "semantic-reads", "alias-checks")));
  }

  @Test
  void naturalLoopVisitsDoNotBorrowLivenessFromExpiredOrFutureInstances() throws IOException {
    String program =
        """
        gir 1; shape N object 0 1;
        program { allocate held N;
          repeat 3 { read-data value held 0; same-object self held held; checkpoint; }
        }
        """;
    Path run = directory.resolve("loop-lifetimes");
    assertPass(RunEngine.run(input(directory, program), selected(21, "roots-natural"), run));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    for (int sequence = 1; sequence <= 3; sequence++)
      assertEquals(
          sequence < 3 ? 1 : 0,
          BindingEvidence.read(run.resolve("snapshots/" + sequence + ".gbind"), token, sequence)
              .bindings()
              .size());
  }

  @Test
  void verifiesAliasResultsInsideReferenceAndConcurrentHistories() throws IOException {
    var programs =
        List.of(
            """
            gir 1; shape N object 1 1;
            object 1 N [weak(2)] [0]; object 2 N [null] [7]; root main = 1; root guard = 2;
            program { scope {
              read-root owner N main; acquire held N owner 0 {
                same-object equal held held; write-data owner 0 equal;
              }
            } checkpoint; }
            """,
            """
            gir 1; shape N object 0 1;
            object 1 N [] [0]; object 2 N [] [0]; root left = 1; root right = 2; root pending = null;
            program { parallel {
              worker a { read-root n N left; exchange-root prior N pending n;
                same-object first prior null; write-data n 0 first; }
              worker b { read-root n N right; exchange-root prior N pending n;
                same-object first prior null; write-data n 0 first; }
            } checkpoint; }
            """);
    for (int i = 0; i < programs.size(); i++)
      assertPass(
          RunEngine.run(
              input(directory, programs.get(i)),
              profile(directory, 21),
              directory.resolve("history-" + i)));
  }

  private Path selected(int feature, String observation) throws IOException {
    Path selected = profile(directory, feature);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", observation);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of("tagging", "named-roots", "local-bindings", "alias-checks", "semantic-reads")));
    JsonFiles.write(selected, raw);
    return selected;
  }
}
