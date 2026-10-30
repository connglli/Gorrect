package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.oracle.HistoryPlan;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Conditional visit identity, active acquisition roots, and cross-channel corruption checks. */
@Tag("hotspot")
class ConditionalCaptureRunTest {
  @TempDir Path directory;
  private static final String REFERENCES =
      """
      gir 1; shape N object 1 1; object 1 N [weak(null)] [7]; root main = 1;
      program { scope { read-root owner N main;
        repeat 4 {
          acquire held N owner 0 { inside: checkpoint; read-data value held 0; }
          write-weak owner 0 owner;
        }
      } outside: checkpoint; }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void capturesOnlyTakenAcquisitionsWithActualVisitNumbers(int feature) throws IOException {
    Path run = directory.resolve("conditional");
    assertPass(RunEngine.run(input(directory, REFERENCES), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token);
    var visits = CheckpointPlan.recorded(GirParser.parse(REFERENCES), trace);
    assertEquals(5, visits.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(i + 1, visits.get(i).occurrence());
      assertEquals(
          2,
          BindingEvidence.read(run.resolve("snapshots/" + (i + 1) + ".gbind"), token, i + 1L)
              .bindings()
              .size());
    }
    assertEquals(
        6,
        new CheckpointPlan(HistoryPlan.plan(GirParser.parse(REFERENCES), OracleLimits.DEFAULT))
            .visits()
            .size());
  }

  @Test
  void matchesNondeterministicCapturePresenceToTheContendedTrace() throws IOException {
    String program =
        """
        gir 1; shape N object 0 0; object 1 N [] []; root guard = 1;
        root pending = 1; root left = null; root right = null;
        program {
          parallel {
            worker a { exchange-root held N pending null; if-present held { write-volatile-root left held; } }
            worker b { exchange-root held N pending null; if-present held { write-volatile-root right held; } }
          }
          scope { read-volatile-root candidate N left; if-present candidate { won: checkpoint; } }
        }
        """;
    Path run = directory.resolve("contended");
    assertPass(RunEngine.run(input(directory, program), selected(21), run));
    assertPass(RunEngine.verify(run));
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void capturesAcquiredArraysWithActualLoopVisits(int feature) throws IOException {
    String program =
        """
        gir 1; shape R refs 1; shape D data 1;
        object 1 R [weak(null)] []; object 2 D [] [42]; root slots = 1; root target = 2;
        program { scope { read-root owner R slots; read-root value D target;
          repeat 3 {
            acquire held D owner 0 { acquired: checkpoint; read-data observed held 0; }
            write-weak owner 0 value;
          }
        } }
        """;
    Path run = directory.resolve("array-acquisition");
    assertPass(RunEngine.run(input(directory, program), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("array-replay")));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    assertEquals(
        3, BindingEvidence.read(run.resolve("snapshots/1.gbind"), token, 1).bindings().size());
    assertEquals(
        3, BindingEvidence.read(run.resolve("snapshots/2.gbind"), token, 2).bindings().size());
  }

  @Test
  void handlesDeterministicAndNeverTakenBranchesWithoutHistoryPredictions() throws IOException {
    String program =
        """
        gir 1; shape N object 0 1; object 1 N [] [7]; root full = 1; root empty = null;
        program { scope {
          read-root held N full; if-present held { taken: checkpoint; }
          read-root absent N empty; if-present absent { skipped: checkpoint; }
        } }
        """;
    Path run = directory.resolve("exact");
    assertPass(RunEngine.run(input(directory, program), selected(21), run));
    assertPass(RunEngine.verify(run));
  }

  @Test
  void damagedControlAndMissingBindingCoverageCannotBecomeGraphPasses() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, REFERENCES), selected(21), run));
    var damaged = copy(directory, run, "control-damage");
    var control = JsonFiles.read(damaged.resolve("traces/control.json"));
    var messages = JsonFiles.GSON.fromJson(control.get("messages"), ControlMessage[].class);
    var message = messages[1];
    messages[1] =
        new ControlMessage(
            message.kind(),
            message.run(),
            message.sequence(),
            message.operation(),
            message.occurrence() + 1,
            message.detail());
    control.add("messages", JsonFiles.GSON.toJsonTree(messages));
    JsonFiles.write(damaged.resolve("traces/control.json"), control);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(damaged).execution());
    var gap = copy(directory, run, "binding-gap");
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var binding = BindingEvidence.read(gap.resolve("snapshots/1.gbind"), token, 1);
    assertTrue(binding.bindings().size() > 1);
    new BindingEvidence(token, 1, binding.thread(), 0, 1, binding.bindings().subList(0, 1))
        .write(gap.resolve("snapshots/1.gbind"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(gap).verdict());
    Files.delete(gap.resolve("snapshots/1.gbind"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(gap).execution());
  }

  @Test
  void namedRootObservationDoesNotInventAcquisitionBindings() throws IOException {
    Path run = directory.resolve("unsupported");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, REFERENCES), profile(directory, 21), run).execution());
    assertFalse(Files.exists(run.resolve("logs/compiler-command.json")));
  }

  private Path selected(int feature) throws IOException {
    var selected = profile(directory, feature);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(selected, raw);
    return selected;
  }
}
