package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.CASE;
import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.fuzz.FuzzCampaign;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Worker recording and seeded campaign behavior, including independent duration limits. */
@Tag("hotspot")
class ConcurrentRunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesParallelWorkersAndBarriersWithIndependentRecorders(int feature) throws IOException {
    var program =
        """
        gir 1; shape N object 1 1;
        object 1 N [null] [7]; object 2 N [null] [9];
        root left = 1; root right = 2;
        program { repeat 2 { parallel {
          worker alpha {
            read-root n N left; write-root left null; collect;
            repeat 20 { allocate fresh N; write-ref n 0 fresh; write-data fresh 0 41; }
            barrier; write-root left n;
          }
          worker beta {
            read-root n N right; write-root right null; collect;
            repeat 20 { allocate fresh N; write-ref n 0 fresh; write-data fresh 0 42; }
            barrier; write-root right n;
          }
        } } checkpoint; }
        """;
    Path output = directory.resolve("parallel");
    assertPass(RunEngine.run(input(directory, program), profile(directory, feature), output));
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    var trace =
        ExecutionTrace.read(
            output.resolve("traces/execution.gtrace"), manifest.get("run").getAsLong());
    assertEquals(Set.of(0, 1, 2), trace.byWorker().keySet());
    assertPass(RunEngine.verify(output));
    Path missing = copy(directory, output, "missing-synchronization");
    Files.delete(missing.resolve("traces/workers.gevidence"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
    Path barrier = copy(directory, output, "missing-barrier");
    var events = new ArrayList<>(trace.events());
    int index =
        IntStream.range(0, events.size())
            .filter(i -> events.get(i).kind() == ExecutionTrace.Kind.BARRIER)
            .findFirst()
            .orElseThrow();
    events.remove(index);
    new ExecutionTrace(trace.run(), true, events).write(barrier.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(barrier).verdict());
    Path invalid = copy(directory, output, "contradictory-joins");
    var evidence = ExecutionEvidence.read(invalid.resolve("traces/workers.gevidence"), trace.run());
    var groups = new ArrayList<>(evidence.groups());
    var group = groups.getFirst();
    groups.set(
        0,
        new ExecutionEvidence.Group(
            group.operation(),
            group.round(),
            group.workers(),
            false,
            group.gcBefore(),
            group.gcAfter(),
            group.compilations()));
    new ExecutionEvidence(trace.run(), true, groups, evidence.windows())
        .write(invalid.resolve("traces/workers.gevidence"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(invalid).execution());
  }

  @Test
  void fuzzRetainsSeedsAndSeparatesCampaignLimitsFromTargetFailures() throws IOException {
    Path profile = profile(directory, 21), output = directory.resolve("campaign");
    var messages = new ByteArrayOutputStream();
    int status =
        FuzzCampaign.run(
            profile, output, "concurrent", 2, Duration.ofSeconds(3), 42, new PrintStream(messages));
    assertTrue(Set.of(0, 3, 5).contains(status), messages.toString());
    var summary = JsonFiles.read(output.resolve("campaign.json"));
    assertEquals(42, summary.get("seed").getAsLong());
    assertEquals("duration", summary.get("stopped").getAsString());
    assertFalse(summary.getAsJsonArray("cases").isEmpty());
    var first = summary.getAsJsonArray("cases").get(0).getAsJsonObject();
    var manifest =
        JsonFiles.read(
            output.resolve(first.get("directory").getAsString()).resolve("manifest.json"));
    assertEquals(
        first.get("seed").getAsLong(),
        manifest.getAsJsonObject("provenance").get("seed").getAsLong());
    Path limited = directory.resolve("limited-case");
    var result =
        RunEngine.runBounded(
            input(directory, CASE), profile, limited, Duration.ZERO, Map.of("seed", 7L));
    assertEquals(RunReport.Outcome.CAMPAIGN_LIMIT, result.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertEquals(5, result.exitStatus());
  }
}
