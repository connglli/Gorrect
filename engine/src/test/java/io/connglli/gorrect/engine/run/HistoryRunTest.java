package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Conditional and contended histories through real target capture and offline verification. */
@Tag("hotspot")
class HistoryRunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void verifiesConditionalReferencesWithoutStrengtheningCapture(int feature) throws IOException {
    var program =
        """
        gir 1; shape N object 3 1;
        object 1 N [soft(2), weak(2), null] [0];
        object 2 N [3, null, null] [7]; object 3 N [null, null, null] [9];
        root main = 1; root protected = 2; root saved = null;
        program {
          scope {
            read-root owner N main;
            acquire held N owner 0 {
              read-ref child N held 0; write-root saved child; collect;
              is-cleared state owner 1; write-data owner 0 state;
            }
            clear-ref owner 0;
            acquire absent N owner 0 { write-data absent 0 99; }
            repeat 3 {
              write-weak owner 2 owner;
              acquire self N owner 2 { write-data self 0 42; }
              clear-ref owner 2; poll-queue;
            }
          }
          checkpoint;
          write-root protected null; collect; poll-queue; checkpoint;
        }
        """;
    Path output = directory.resolve("references"), selected = profile(directory, feature);
    assertPass(RunEngine.run(input(directory, program), selected, output));
    assertPass(RunEngine.verify(output));
    assertPass(RunEngine.replay(output, directory.resolve("reference-replay")));
    String source = Files.readString(output.resolve("generated/Case.java"));
    assertTrue(source.contains(".get();"));
    assertTrue(source.contains("Reference.reachabilityFence"));
    long run = JsonFiles.read(output.resolve("manifest.json")).get("run").getAsLong();
    var history = ReferenceHistory.read(output.resolve("traces/references.ghistory"), run);
    var acquisitions =
        history.events().stream().filter(e -> e.kind() == ReferenceHistory.Kind.ACQUIRE).toList();
    assertNotNull(acquisitions.getFirst().target());
    assertNull(acquisitions.get(1).target());
    assertEquals(
        3,
        history.events().stream()
            .filter(e -> e.kind() == ReferenceHistory.Kind.INSTALL)
            .map(e -> e.wrapper())
            .distinct()
            .count());
    Path damaged = copy(directory, output, "reference-damage");
    var events = new ArrayList<>(history.events());
    var first = events.getFirst();
    events.set(
        0,
        new ReferenceHistory.Event(
            first.sequence(),
            first.kind(),
            first.strength(),
            first.wrapper(),
            new ReferenceHistory.Target(0, 0, 99)));
    new ReferenceHistory(run, true, events).write(damaged.resolve("traces/references.ghistory"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(damaged).verdict());
    Path missing = copy(directory, output, "reference-gap");
    Files.delete(missing.resolve("traces/references.ghistory"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
    Path partial = copy(directory, output, "reference-incomplete");
    new ReferenceHistory(run, false, history.events())
        .write(partial.resolve("traces/references.ghistory"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(partial).execution());
    try (var input = getClass().getResourceAsStream("/cases/references.gir")) {
      String example =
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
      assertPass(
          RunEngine.run(
              input(directory, example), selected, directory.resolve("reference-example")));
    }
  }

  @Test
  void reportsReferenceSearchLimitsAndUnsupportedCombinations() throws IOException {
    Path selected = profile(directory, 21);
    String references =
        "gir 1; shape N object 1 0; object 1 N [weak(2)] []; object 2 N [null] []; root r = 1;"
            + " program { checkpoint; }";
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("limits").addProperty("historyStates", 1);
    JsonFiles.write(selected, raw);
    var limited =
        RunEngine.run(input(directory, references), selected, directory.resolve("reference-bound"));
    assertEquals(RunReport.Outcome.COMPLETED, limited.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, limited.verdict());
    String arrays = references.replace("program", "shape A refs 0; program");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, arrays), selected, directory.resolve("array-references"))
            .execution());
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("reference-reclamation")));
    JsonFiles.write(selected, raw);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, references), selected, directory.resolve("reclamation"))
            .execution());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesContendedOwnershipAndChecksWorkerIdentityEvidence(int feature) throws IOException {
    String program;
    try (var resource = getClass().getResourceAsStream("/cases/contended.gir")) {
      program = new String(Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8);
    }
    Path selected = profile(directory, feature), output = directory.resolve("contended");
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("limits").addProperty("historyTransitions", 1_000_000);
    JsonFiles.write(selected, raw);
    assertPass(RunEngine.run(input(directory, program), selected, output));
    assertPass(RunEngine.verify(output));
    assertPass(RunEngine.replay(output, directory.resolve("ownership-replay")));
    long run = JsonFiles.read(output.resolve("manifest.json")).get("run").getAsLong();
    var history = ReferenceHistory.read(output.resolve("traces/references.ghistory"), run);
    assertEquals(
        Set.of(1, 2),
        history.events().stream().map(ReferenceHistory.Event::worker).collect(Collectors.toSet()));
    assertEquals(1, history.events().stream().filter(event -> event.target() != null).count());
    Path altered = copy(directory, output, "ownership-altered");
    var events = new ArrayList<>(history.events());
    var first = events.getFirst();
    events.set(
        0,
        new ReferenceHistory.Event(
            first.worker(),
            first.sequence(),
            first.kind(),
            first.strength(),
            first.wrapper(),
            new ReferenceHistory.Target(0, 0, 99)));
    new ReferenceHistory(run, true, events).write(altered.resolve("traces/references.ghistory"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(altered).verdict());
    Path missing = copy(directory, output, "ownership-missing");
    Files.delete(missing.resolve("traces/references.ghistory"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void callerAcquisitionProtectsReferentsAcrossPeerCollectionAndBarriers(int feature)
      throws IOException {
    String program =
        """
        gir 1; shape N object 1 1;
        object 1 N [weak(2)] [0]; object 2 N [null] [7]; root r = 1; root guard = 2;
        program {
          scope { read-root owner N r; acquire held N owner 0 {
            write-root guard null;
            parallel {
              worker collector { collect; barrier; collect; }
              worker observer { read-root owner N r; is-cleared first owner 0; barrier; is-cleared second owner 0; }
            }
          } }
          checkpoint;
        }
        """;
    Path selected = profile(directory, feature), output = directory.resolve("protected-reference");
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("limits").addProperty("historyTransitions", 2_000_000);
    JsonFiles.write(selected, raw);
    assertPass(RunEngine.run(input(directory, program), selected, output));
    assertPass(RunEngine.verify(output));
    assertPass(RunEngine.replay(output, directory.resolve("protected-replay")));
    long run = JsonFiles.read(output.resolve("manifest.json")).get("run").getAsLong();
    var trace = ExecutionTrace.read(output.resolve("traces/execution.gtrace"), run);
    var states =
        trace.events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.REFERENCE_STATE)
            .toList();
    assertEquals(2, states.size());
    assertTrue(states.stream().allMatch(event -> event.value() == 0));
    Path corrupt = copy(directory, output, "protected-corrupt");
    var events = new ArrayList<>(trace.events());
    var state = states.getFirst();
    events.set(
        events.indexOf(state),
        new ExecutionTrace.Event(
            state.operation(),
            state.kind(),
            1,
            state.worker(),
            state.group(),
            state.round(),
            state.phase()));
    new ExecutionTrace(run, true, events).write(corrupt.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(corrupt).verdict());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesVolatileFieldsAndAtomicReferenceAndLongSlots(int feature) throws IOException {
    String program =
        """
        gir 1; shape N object 1 1; object 1 N [null] [5]; root r = 1; root saved = null;
        program { scope {
          read-volatile-root owner N r;
          read-volatile-data value owner 0;
          compare-set-data changed owner 0 value 6;
          exchange-data old owner 0 42;
          write-data owner 0 old;
          write-volatile-ref owner 0 owner;
          read-volatile-ref alias N owner 0;
          exchange-ref prior N alias 0 null;
          compare-set-ref linked owner 0 null prior;
          write-volatile-data prior 0 9;
          write-volatile-root saved prior;
        } checkpoint; }
        """;
    assertPass(
        RunEngine.run(
            input(directory, program),
            profile(directory, feature),
            directory.resolve("atomic-fields")));
  }

  @Test
  void probesContendedCapabilityThroughTheHistoryChecker() throws IOException {
    Path selected = profile(directory, 21);
    var raw = JsonFiles.read(selected);
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("contended-histories")));
    JsonFiles.write(selected, raw);
    Path output = directory.resolve("atomic-probe");
    assertPass(RunEngine.probe(selected, output));
    assertTrue(
        JsonFiles.read(output.resolve("manifest.json")).get("concurrentHistory").getAsBoolean());
  }

  @Test
  void rejectsRacesInvalidPathsAndUnprovedSearchesBeforeLaunch() throws IOException {
    String race =
        """
        gir 1; shape N object 0 1; object 1 N [] [0]; root r = 1;
        program { parallel {
          worker a { read-volatile-root n N r; write-data n 0 1; }
          worker b { read-volatile-root n N r; read-data value n 0; }
        } }
        """;
    Path selected = profile(directory, 21), output = directory.resolve("racy");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, race), selected, output).execution());
    assertFalse(Files.exists(output.resolve("logs/compiler-command.json")));
    String invalid =
        "gir 1; shape N object 0 1; root r = null; program { read-volatile-root n N r; write-data n"
            + " 0 1; }";
    assertEquals(
        RunReport.Outcome.INVALID_WORKLOAD,
        RunEngine.run(input(directory, invalid), selected, directory.resolve("invalid-history"))
            .execution());
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("limits").addProperty("historyTransitions", 1);
    JsonFiles.write(selected, raw);
    String bounded =
        "gir 1; shape N object 0 1; object 1 N [] [0]; root r = 1; program { read-volatile-root n N"
            + " r; release n; checkpoint; }";
    assertEquals(
        RunReport.Outcome.CHECKING_LIMIT,
        RunEngine.run(input(directory, bounded), selected, directory.resolve("unproved"))
            .execution());
  }

  @Test
  void identifiesInvalidPreconditionsOnAValidatedConditionalPath() throws IOException {
    String program =
        """
        gir 1; shape N object 1 1; object 1 N [weak(1)] [0]; root r = 1;
        program {
          read-root owner N r;
          acquire held N owner 0 {
            write-ref held 0 null;
            read-ref absent N held 0;
            write-data absent 0 7;
          }
        }
        """;
    Path output = directory.resolve("invalid-reference-path");
    var result = RunEngine.run(input(directory, program), profile(directory, 21), output);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, RunEngine.verify(output).execution());
  }

  @Test
  void probesReferenceCaptureWhenTheProfileRequiresIt() throws IOException {
    Path selected = profile(directory, 21);
    var raw = JsonFiles.read(selected);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(List.of("reference-histories", "reference-queues")));
    JsonFiles.write(selected, raw);
    Path output = directory.resolve("reference-probe");
    assertPass(RunEngine.probe(selected, output));
    assertTrue(Files.exists(output.resolve("traces/references.ghistory")));
  }
}
