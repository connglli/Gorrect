package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static io.connglli.gorrect.engine.run.RunTestSupport.rewriteSnapshot;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cross-channel identity and coverage damage cannot erase independently established violations. */
@Tag("hotspot")
class ConditionalCaptureEvidenceTest {
  @TempDir Path directory;

  private static final String PROGRAM =
      """
      gir 1; shape N object 1 1; object 1 N [weak(null)] [7]; root main = 1;
      program { scope { read-root owner N main;
        repeat 3 {
          acquire held N owner 0 { if-present held { inside: checkpoint; read-data value held 0; } }
          write-weak owner 0 owner;
        }
      } outside: checkpoint; }
      """;

  @Test
  void rejectsCaptureAndControlIdentityDamageBeforeUsingGraphValues() throws IOException {
    Path run = run(false);
    long token = token(run);
    var checks = new ArrayList<Executable>();
    for (String damage :
        List.of(
            "heap-sequence",
            "binding-sequence",
            "binding-thread",
            "binding-worker",
            "binding-activation",
            "control-site",
            "control-sequence",
            "control-extra",
            "control-missing")) {
      Path damaged = copy(directory, run, damage);
      if (damage.equals("heap-sequence"))
        Files.copy(
            damaged.resolve("snapshots/2.gsnap"),
            damaged.resolve("snapshots/1.gsnap"),
            StandardCopyOption.REPLACE_EXISTING);
      else if (damage.equals("binding-sequence"))
        Files.copy(
            damaged.resolve("snapshots/2.gbind"),
            damaged.resolve("snapshots/1.gbind"),
            StandardCopyOption.REPLACE_EXISTING);
      else if (damage.startsWith("binding-")) {
        var binding = BindingEvidence.read(damaged.resolve("snapshots/1.gbind"), token, 1);
        new BindingEvidence(
                token,
                1,
                binding.thread() + (damage.equals("binding-thread") ? 1 : 0),
                damage.equals("binding-worker") ? 1 : 0,
                damage.equals("binding-activation") ? 2 : 1,
                binding.bindings())
            .write(damaged.resolve("snapshots/1.gbind"));
      } else {
        var control = JsonFiles.read(damaged.resolve("traces/control.json"));
        var messages =
            new ArrayList<>(
                List.of(JsonFiles.GSON.fromJson(control.get("messages"), ControlMessage[].class)));
        var first = messages.get(1);
        if (damage.equals("control-extra")) messages.add(1, first);
        else if (damage.equals("control-missing")) messages.remove(1);
        else
          messages.set(
              1,
              new ControlMessage(
                  first.kind(),
                  token,
                  first.sequence() + (damage.equals("control-sequence") ? 1 : 0),
                  damage.equals("control-site") ? messages.get(3).operation() : first.operation(),
                  first.occurrence(),
                  first.detail()));
        control.add("messages", JsonFiles.GSON.toJsonTree(messages));
        JsonFiles.write(damaged.resolve("traces/control.json"), control);
      }
      checks.add(
          () ->
              assertEquals(
                  RunReport.Outcome.EXTRACTION_ERROR,
                  RunEngine.verify(damaged).execution(),
                  damage));
    }
    assertAll(checks);
  }

  @Test
  void unexpectedBindingDeclarationsAreCoverageGaps() throws IOException {
    Path run = run(false);
    long token = token(run);
    var binding = BindingEvidence.read(run.resolve("snapshots/1.gbind"), token, 1);
    var declarations = new ArrayList<>(binding.bindings());
    declarations.add(new BindingEvidence.Binding(999_999, false));
    new BindingEvidence(token, 1, binding.thread(), 0, 1, declarations)
        .write(run.resolve("snapshots/1.gbind"));
    var report = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.COMPLETED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict(), report.toString());
  }

  @Test
  void incompleteOrInconsistentPrefixCannotEstablishAnInvalidWorkload() throws IOException {
    String program =
        """
        gir 1; shape N object 1 1; object 1 N [weak(1)] [0]; root r = 1; root empty = null;
        program { scope { read-root owner N r; acquire held N owner 0 {
          checkpoint; read-root absent N empty; write-data absent 0 7;
        } } }
        """;
    Path run = directory.resolve("failed-execution");
    assertEquals(
        RunReport.Outcome.INVALID_WORKLOAD,
        RunEngine.run(input(directory, program), selected(), run).execution());
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, RunEngine.verify(run).execution());
    var checks = new ArrayList<Executable>();
    for (String gap :
        List.of(
            "heap",
            "binding-file",
            "binding-declarations",
            "control-site",
            "control-visit",
            "control-missing",
            "control-extra")) {
      Path damaged = copy(directory, run, "failed-" + gap);
      if (gap.equals("heap")) Files.delete(damaged.resolve("snapshots/1.gsnap"));
      else if (gap.equals("binding-file")) Files.delete(damaged.resolve("snapshots/1.gbind"));
      else if (gap.equals("binding-declarations")) {
        long token = token(damaged);
        var binding = BindingEvidence.read(damaged.resolve("snapshots/1.gbind"), token, 1);
        new BindingEvidence(token, 1, binding.thread(), 0, 1, List.of())
            .write(damaged.resolve("snapshots/1.gbind"));
      } else {
        var control = JsonFiles.read(damaged.resolve("traces/control.json"));
        var messages =
            new ArrayList<>(
                List.of(JsonFiles.GSON.fromJson(control.get("messages"), ControlMessage[].class)));
        var capture = messages.get(1);
        if (gap.equals("control-missing")) messages.remove(1);
        else if (gap.equals("control-extra")) messages.add(1, capture);
        else
          messages.set(
              1,
              new ControlMessage(
                  capture.kind(),
                  capture.run(),
                  capture.sequence(),
                  gap.equals("control-site") ? 999 : capture.operation(),
                  capture.occurrence() + (gap.equals("control-visit") ? 1 : 0),
                  capture.detail()));
        control.add("messages", JsonFiles.GSON.toJsonTree(messages));
        JsonFiles.write(damaged.resolve("traces/control.json"), control);
      }
      checks.add(
          () -> {
            var report = RunEngine.verify(damaged);
            assertEquals(RunReport.Outcome.RUNTIME_ERROR, report.execution(), gap + ": " + report);
            assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict(), report.toString());
          });
    }
    assertAll(checks);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesEarlierViolationsAlongsideLaterMissingCaptureEvidence(boolean concurrent)
      throws IOException {
    Path run = run(concurrent);
    var checks = new ArrayList<Executable>();
    for (String violation : List.of("scalar", "graph")) {
      for (String extension : List.of("gsnap", "gbind")) {
        Path damaged = copy(directory, run, violation + "-missing-" + extension);
        if (violation.equals("graph")) rewriteSnapshot(damaged, false);
        else corruptScalar(damaged);
        var violationOnly = RunEngine.verify(damaged);
        assertEquals(
            ExactVerifier.Verdict.VIOLATED, violationOnly.verdict(), violationOnly.toString());
        Files.delete(damaged.resolve("snapshots/4." + extension));
        checks.add(
            () -> {
              var report = RunEngine.verify(damaged);
              assertEquals(
                  RunReport.Outcome.EXTRACTION_ERROR, report.execution(), report.toString());
              assertEquals(ExactVerifier.Verdict.VIOLATED, report.verdict(), report.toString());
            });
      }
    }
    assertAll(checks);
  }

  private Path run(boolean concurrent) throws IOException {
    Path selected = selected();
    Path run = directory.resolve(concurrent ? "concurrent" : "sequential");
    assertPass(
        RunEngine.run(
            input(
                directory,
                concurrent
                    ? PROGRAM.replace("read-root owner", "read-volatile-root owner")
                    : PROGRAM),
            selected,
            run));
    return run;
  }

  private Path selected() throws IOException {
    var selected = profile(directory, 21);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(selected, raw);
    return selected;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }

  private static void corruptScalar(Path run) throws IOException {
    long token = token(run);
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token);
    var events = new ArrayList<>(trace.events());
    var read =
        events.stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.SCALAR_READ)
            .findFirst()
            .orElseThrow();
    events.set(
        events.indexOf(read),
        new ExecutionTrace.Event(
            read.operation(),
            read.kind(),
            read.value() + 1,
            read.worker(),
            read.group(),
            read.round(),
            read.phase()));
    new ExecutionTrace(token, true, events).write(run.resolve("traces/execution.gtrace"));
  }
}
