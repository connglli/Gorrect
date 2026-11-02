package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.FrameEvidence;
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
class MethodRunTest {
  static final String SOURCE =
      """
      gir 1; shape N object 1 1; root saved=null;
      method mutate(ref N node, long value) {
        allocate local N; collect; write-data node 0 value; write-ref node 0 local;
      }
      method wrapper(ref N node, long value) { call mutate(node, value); }
      method empty() {}
      program {
        allocate callerOnly N; allocate passed N;
        call empty();
        repeat 2 { call wrapper(passed, 42); }
        if-nonzero 0 { call empty(); } else { call empty(); }
        write-ref callerOnly 0 passed; write-root saved callerOnly; checkpoint;
      }
      """;

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void realStaticCallsRetainCallerParametersAndLocalsAcrossGcWithExactClassReplay(int feature)
      throws IOException {
    Path run = run(feature, "run");
    assertPass(RunEngine.verify(run));
    methodCapability(run, true);
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run));
    assertEquals(
        6,
        trace.events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.CALL_ENTER)
            .count());
    assertEquals(
        6,
        trace.events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.CALL_EXIT)
            .count());
    var certificate =
        CodeEvidence.read(run.resolve("traces/code.gcode"), token(run), CodeEvidence.Stage.STARTUP);
    assertEquals(
        3,
        certificate.methods().stream()
            .filter(method -> method.name().startsWith("helper"))
            .count());
    assertTrue(
        Files.readString(run.resolve("observation-capabilities.json")).contains("method-calls"));
    var facts = ControlledLaunch.targetFacts(run);
    var collections =
        HotSpotCollections.read(
            run.resolve("traces/workers.jfr"),
            token(run),
            Long.parseLong(facts.getProperty("process.pid")),
            Long.parseLong(facts.getProperty("vm.start")));
    assertEquals(2, collections.requests().size());
    assertTrue(
        collections.requests().stream()
            .allMatch(
                request ->
                    request.returned()
                        && request.worker() == 0
                        && request.group() == 0
                        && request.round() == 0));
    assertTrue(collections.cycles().size() >= 2);
  }

  @Test
  void traceCorruptionCannotHideEmptyCallsBranchesOrWrongCallSites() throws IOException {
    Path original = run(21, "original");
    var trace = ExecutionTrace.read(original.resolve("traces/execution.gtrace"), token(original));
    for (var kind :
        List.of(
            ExecutionTrace.Kind.CALL_ENTER,
            ExecutionTrace.Kind.CALL_EXIT,
            ExecutionTrace.Kind.BRANCH)) {
      Path damaged = copy(directory, original, kind.name());
      var events = new ArrayList<>(trace.events());
      assertTrue(events.removeIf(event -> event.kind() == kind));
      new ExecutionTrace(trace.run(), true, events)
          .write(damaged.resolve("traces/execution.gtrace"));
      assertEquals(
          ExactVerifier.Verdict.VIOLATED, RunEngine.verify(damaged).verdict(), kind.toString());
      if (kind != ExecutionTrace.Kind.CALL_ENTER) methodCapability(damaged, false);
    }
    Path changed = copy(directory, original, "wrong-site");
    var events = new ArrayList<>(trace.events());
    var event = events.get(2);
    events.set(2, new ExecutionTrace.Event(event.operation() + 1, event.kind(), event.value()));
    new ExecutionTrace(trace.run(), true, events).write(changed.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(changed).verdict());
  }

  @Test
  void certifiedHelpersCannotRemainInOrdinaryOrTerminalFrameSamples() throws IOException {
    Path original = run(21, "original");
    var code =
        CodeEvidence.read(
            original.resolve("traces/code.gcode"), token(original), CodeEvidence.Stage.STARTUP);
    long helper =
        code.methods().stream()
            .filter(method -> method.name().equals("helper1"))
            .findFirst()
            .orElseThrow()
            .token();
    for (int checkpoint : List.of(1, 2)) {
      for (boolean beforeBracket : List.of(false, true)) {
        Path damaged = copy(directory, original, "helper-" + checkpoint + "-" + beforeBracket);
        Path file = damaged.resolve("snapshots/" + checkpoint + ".gframe");
        var sample = FrameEvidence.read(file, token(damaged), checkpoint);
        var participant = sample.participants().getFirst();
        var active = new ArrayList<>(beforeBracket ? participant.before() : participant.after());
        active.add(new FrameEvidence.Frame(helper, active.size(), 0));
        new FrameEvidence(
                sample.run(),
                sample.checkpoint(),
                sample.status(),
                sample.jvmtiError(),
                sample.beforeEpoch(),
                sample.afterEpoch(),
                List.of(
                    new FrameEvidence.Participant(
                        participant.worker(),
                        participant.thread(),
                        participant.role(),
                        beforeBracket ? active : participant.before(),
                        beforeBracket ? participant.after() : active)))
            .write(file);
        assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(damaged).verdict());
        methodCapability(damaged, false);
      }
    }
  }

  @Test
  void incompleteTracesAndMissingTerminalProofCannotVerifyMethodCalls() throws IOException {
    Path original = run(21, "original");
    for (String damage : List.of("trace", "frame", "code")) {
      Path damaged = copy(directory, original, damage);
      if (damage.equals("trace")) {
        var trace = ExecutionTrace.read(damaged.resolve("traces/execution.gtrace"), token(damaged));
        new ExecutionTrace(trace.run(), false, trace.events())
            .write(damaged.resolve("traces/execution.gtrace"));
      } else if (damage.equals("frame")) Files.delete(damaged.resolve("snapshots/2.gframe"));
      else Files.delete(damaged.resolve("traces/code-final.gcode"));
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(damaged).verdict());
      methodCapability(damaged, false);
    }
  }

  @Test
  void jvmtiProviderIsRejectedBeforeCompilationIncludingUnusedDefinitions() throws IOException {
    Path selected = profile(directory, 21);
    Path run = directory.resolve("unsupported");
    var report =
        RunEngine.run(input(directory, "gir 1; method unused() {} program {}"), selected, run);
    assertEquals(RunReport.Outcome.UNSUPPORTED, report.execution());
    assertTrue(Files.notExists(run.resolve("generated/Case.java")));
  }

  private Path run(int feature, String name) throws IOException {
    Path selected = profile(directory, feature);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion")));
    JsonFiles.write(selected, settings);
    Path run = directory.resolve(name);
    assertPass(RunEngine.run(input(directory, SOURCE), selected, run));
    return run;
  }

  private static void methodCapability(Path run, boolean verified) throws IOException {
    var capabilities =
        JsonFiles.GSON.fromJson(
            Files.readString(run.resolve("observation-capabilities.json")),
            com.google.gson.JsonArray.class);
    var method =
        capabilities.asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(capability -> capability.get("mechanism").getAsString().equals("method-calls"))
            .findFirst()
            .orElseThrow();
    assertTrue(method.get("exercised").getAsBoolean());
    assertEquals(verified, method.get("verified").getAsBoolean());
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }
}
