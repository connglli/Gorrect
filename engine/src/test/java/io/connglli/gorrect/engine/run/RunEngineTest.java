package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.CASE;
import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.assertSocketRemoved;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static io.connglli.gorrect.engine.run.RunTestSupport.rewriteSnapshot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Strong graph execution, observation integrity, and unsupported input boundaries. */
@Tag("hotspot")
class RunEngineTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesGeneratedCodeAndVerifiesOffline(int feature) throws IOException {
    Path profile = profile(directory, feature),
        input = input(directory, CASE),
        run = directory.resolve("run");
    assertPass(RunEngine.run(input, profile, run));
    assertSocketRemoved(run);
    String source = Files.readString(run.resolve("generated/Case.java"));
    assertTrue(source.contains(".f0"));
    assertTrue(source.contains("new Object[3]"));
    assertFalse(source.contains("GraphMachine"));
    Files.delete(profile);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertSocketRemoved(replay);
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
    assertNotEquals(
        JsonFiles.read(run.resolve("manifest.json")).get("run"),
        JsonFiles.read(replay.resolve("manifest.json")).get("run"));
    assertFalse(Files.exists(replay.resolve("logs/compiler-command.json")));
  }

  @Test
  void classifiesDamagedAndIncompleteEvidence() throws IOException {
    Path original = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, CASE), profile(directory, 21), original));
    Path dataDamage = copy(directory, original, "data-damage");
    rewriteSnapshot(dataDamage, false);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(dataDamage).verdict());
    Path mixed = copy(directory, dataDamage, "violation-and-gap");
    byte[] partial = Files.readAllBytes(mixed.resolve("snapshots/2.gsnap"));
    // In format 1 the status follows magic/version and the two 64-bit identity tokens.
    ByteBuffer.wrap(partial).putInt(24, HeapSnapshot.Status.RECORD_LIMIT.ordinal());
    Files.write(mixed.resolve("snapshots/2.gsnap"), partial);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(mixed).verdict());
    Path missing = copy(directory, original, "missing-value");
    rewriteSnapshot(missing, true);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
    Path truncated = copy(directory, original, "truncated");
    Files.write(truncated.resolve("snapshots/1.gsnap"), new byte[] {1});
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(truncated).execution());
    Path traceDamage = copy(directory, original, "trace-damage");
    long run = JsonFiles.read(traceDamage.resolve("manifest.json")).get("run").getAsLong();
    var trace = ExecutionTrace.read(traceDamage.resolve("traces/execution.gtrace"), run);
    var events = new ArrayList<>(trace.events());
    int index =
        IntStream.range(0, events.size())
            .filter(i -> events.get(i).kind() == ExecutionTrace.Kind.SCALAR_READ)
            .findFirst()
            .orElseThrow();
    var event = events.get(index);
    events.set(index, new ExecutionTrace.Event(event.operation(), event.kind(), event.value() + 1));
    new ExecutionTrace(run, true, events).write(traceDamage.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(traceDamage).verdict());
    new ExecutionTrace(run, false, events).write(traceDamage.resolve("traces/execution.gtrace"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(traceDamage).execution());
    Path executable = copy(directory, original, "executable-damage");
    Files.write(executable.resolve("generated/classes/Case.class"), new byte[] {0});
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(executable).execution());
    assertThrows(
        IOException.class,
        () -> RunEngine.replay(executable, directory.resolve("rejected-replay")));
  }

  @Test
  void reportsUnsupportedBindingsLimitsAndInputErrors() throws IOException {
    Path profile = profile(directory, 21);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(
                input(
                    directory,
                    "gir 1; shape N object 0 0; root r = null; program { read-root n N r;"
                        + " checkpoint; }"),
                profile,
                directory.resolve("locals"))
            .execution());
    assertEquals(
        RunReport.Outcome.INVALID_WORKLOAD,
        RunEngine.run(input(directory, "gir 77; program {}"), profile, directory.resolve("invalid"))
            .execution());
    assertEquals(
        RunReport.Outcome.CHECKING_LIMIT,
        RunEngine.run(
                input(directory, "gir 1; program { repeat 1000000 {} }"),
                profile,
                directory.resolve("budget"))
            .execution());
    var object = JsonFiles.read(profile);
    object.getAsJsonObject("limits").addProperty("records", 1);
    JsonFiles.write(profile, object);
    var partial = RunEngine.run(input(directory, CASE), profile, directory.resolve("partial"));
    assertEquals(RunReport.Outcome.COMPLETED, partial.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, partial.verdict());
    object.getAsJsonObject("limits").addProperty("records", 10000);
    object.getAsJsonObject("limits").addProperty("traceEvents", 1);
    JsonFiles.write(profile, object);
    assertEquals(
        RunReport.Outcome.RUNTIME_ERROR,
        RunEngine.run(input(directory, CASE), profile, directory.resolve("trace-bound"))
            .execution());
    object.add("requirements", JsonFiles.GSON.toJsonTree(List.of("compiled-execution")));
    JsonFiles.write(profile, object);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, CASE), profile, directory.resolve("requirements"))
            .execution());
  }

  @Test
  void probesActualTargetAndDoesNotOverwriteRuns() throws IOException {
    Path profile = profile(directory, 21), run = directory.resolve("probe");
    assertPass(RunEngine.probe(profile, run));
    assertTrue(Files.readString(run.resolve("target.properties")).contains("MarkSweepCompact"));
    assertThrows(IOException.class, () -> RunEngine.probe(profile, run));
  }

  @Test
  void keepsTheSocketOutsideLongRunPaths() throws IOException {
    Path output = directory.resolve("run-" + "x".repeat(160));
    assertPass(RunEngine.probe(profile(directory, 21), output));
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    assertEquals("unix", manifest.get("controlTransport").getAsString());
    assertFalse(Path.of(manifest.get("controlSocket").getAsString()).startsWith(output));
    assertSocketRemoved(output);
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void allocatesBothArrayLayoutsAndNumbersLoopCheckpoints(int feature) throws IOException {
    String program =
        """
        gir 1;
        shape R refs 0;
        shape D data 0;
        root r = null;
        root d = null;
        program {
          repeat 3 {
            allocate refs R;
            write-root r refs;
            release refs;
            allocate data D;
            write-root d data;
            release data;
            checkpoint;
          }
        }
        """;
    assertPass(
        RunEngine.run(
            input(directory, program), profile(directory, feature), directory.resolve("arrays")));
  }
}
