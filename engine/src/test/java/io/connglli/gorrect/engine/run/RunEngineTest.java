package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.fuzz.FuzzCampaign;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunEngineTest {
  @TempDir Path directory;
  private static final String CASE =
      """
      gir 1;
      shape N object 2 1;
      shape R refs 3;
      shape D data 2;
      object 1 N [2, 3] [7];
      object 2 R [1, 1, null] [];
      object 3 D [] [-9223372036854775808, 9223372036854775807];
      root main = 1;
      root saved = null;
      program {
        initial: checkpoint;
        repeat 3 {
          read-root n N main;
          read-ref links R n 0;
          read-ref payload D n 1;
          read-data value n 0;
          allocate child N;
          write-data child 0 value;
          write-ref child 0 n;
          write-ref child 1 child;
          write-root saved child;
          write-ref links 2 child;
          write-data payload 1 value;
          write-data n 0 42;
        }
        mutated: checkpoint;
        write-root main null;
        write-root saved null;
        cleared: checkpoint;
      }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesGeneratedCodeAndVerifiesOffline(int feature) throws IOException {
    Path profile = profile(feature), input = input(CASE), run = directory.resolve("run");
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
    assertPass(RunEngine.run(input(CASE), profile(21), original));
    Path dataDamage = copy(original, "data-damage");
    rewriteSnapshot(dataDamage, false);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(dataDamage).verdict());
    Path mixed = copy(dataDamage, "violation-and-gap");
    byte[] partial = Files.readAllBytes(mixed.resolve("snapshots/2.gsnap"));
    // In format 1 the status follows magic/version and the two 64-bit identity tokens.
    java.nio.ByteBuffer.wrap(partial).putInt(24, HeapSnapshot.Status.RECORD_LIMIT.ordinal());
    Files.write(mixed.resolve("snapshots/2.gsnap"), partial);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(mixed).verdict());
    Path missing = copy(original, "missing-value");
    rewriteSnapshot(missing, true);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
    Path truncated = copy(original, "truncated");
    Files.write(truncated.resolve("snapshots/1.gsnap"), new byte[] {1});
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(truncated).execution());
    Path traceDamage = copy(original, "trace-damage");
    long run = JsonFiles.read(traceDamage.resolve("manifest.json")).get("run").getAsLong();
    var trace = ExecutionTrace.read(traceDamage.resolve("traces/execution.gtrace"), run);
    var events = new ArrayList<>(trace.events());
    int index =
        java.util.stream.IntStream.range(0, events.size())
            .filter(i -> events.get(i).kind() == ExecutionTrace.Kind.SCALAR_READ)
            .findFirst()
            .orElseThrow();
    var event = events.get(index);
    events.set(index, new ExecutionTrace.Event(event.operation(), event.kind(), event.value() + 1));
    new ExecutionTrace(run, true, events).write(traceDamage.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(traceDamage).verdict());
    new ExecutionTrace(run, false, events).write(traceDamage.resolve("traces/execution.gtrace"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(traceDamage).execution());
    Path executable = copy(original, "executable-damage");
    Files.write(executable.resolve("generated/classes/Case.class"), new byte[] {0});
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(executable).execution());
    assertThrows(
        IOException.class,
        () -> RunEngine.replay(executable, directory.resolve("rejected-replay")));
  }

  @Test
  void reportsUnsupportedBindingsLimitsAndInputErrors() throws IOException {
    Path profile = profile(21);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(
                input(
                    "gir 1; shape N object 0 0; root r = null; program { read-root n N r;"
                        + " checkpoint; }"),
                profile,
                directory.resolve("locals"))
            .execution());
    assertEquals(
        RunReport.Outcome.INVALID_WORKLOAD,
        RunEngine.run(input("gir 77; program {}"), profile, directory.resolve("invalid"))
            .execution());
    assertEquals(
        RunReport.Outcome.CHECKING_LIMIT,
        RunEngine.run(
                input("gir 1; program { repeat 1000000 {} }"), profile, directory.resolve("budget"))
            .execution());
    var object = JsonFiles.read(profile);
    object.getAsJsonObject("limits").addProperty("records", 1);
    JsonFiles.write(profile, object);
    var partial = RunEngine.run(input(CASE), profile, directory.resolve("partial"));
    assertEquals(RunReport.Outcome.COMPLETED, partial.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, partial.verdict());
    object.getAsJsonObject("limits").addProperty("records", 10000);
    object.getAsJsonObject("limits").addProperty("traceEvents", 1);
    JsonFiles.write(profile, object);
    assertEquals(
        RunReport.Outcome.RUNTIME_ERROR,
        RunEngine.run(input(CASE), profile, directory.resolve("trace-bound")).execution());
    object.add("requirements", JsonFiles.GSON.toJsonTree(List.of("compiled-execution")));
    JsonFiles.write(profile, object);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(CASE), profile, directory.resolve("requirements")).execution());
  }

  @Test
  void retainsCompilationAndStartupDiagnostics() throws IOException {
    Path profile = profile(21);
    var object = JsonFiles.read(profile);
    var support = object.getAsJsonObject("support");
    String runtime = support.get("runtime").getAsString();
    support.addProperty("runtime", support.get("protocol").getAsString());
    JsonFiles.write(profile, object);
    Path compilation = directory.resolve("compilation");
    assertEquals(
        RunReport.Outcome.COMPILATION_FAILURE,
        RunEngine.run(input(CASE), profile, compilation).execution());
    assertFalse(Files.readString(compilation.resolve("logs/compiler.stderr")).isBlank());
    support.addProperty("runtime", runtime);
    var args = new JsonArray();
    args.add("-XX:ThisOptionDoesNotExist=true");
    object.add("vmArgs", args);
    object.getAsJsonObject("limits").addProperty("startupSeconds", 1);
    JsonFiles.write(profile, object);
    Path startup = directory.resolve("startup");
    assertEquals(
        RunReport.Outcome.STARTUP_FAILURE,
        RunEngine.run(input(CASE), profile, startup).execution());
    assertFalse(Files.readString(startup.resolve("logs/stderr.log")).isBlank());
  }

  @Test
  void probesActualTargetAndDoesNotOverwriteRuns() throws IOException {
    Path profile = profile(21), run = directory.resolve("probe");
    assertPass(RunEngine.probe(profile, run));
    assertTrue(Files.readString(run.resolve("target.properties")).contains("MarkSweepCompact"));
    assertThrows(IOException.class, () -> RunEngine.probe(profile, run));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "crash",
        "hang",
        "failure",
        "protocol",
        "malformed",
        "startup-timeout",
        "silent-handshake"
      })
  void distinguishesProcessFailures(String mode) throws IOException {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path profile = profile(21);
    Path launcher = directory.resolve("java-launcher");
    String classpath =
        Path.of(FailureTarget.class.getProtectionDomain().getCodeSource().getLocation().getPath())
            + java.io.File.pathSeparator
            + Path.of(
                io.connglli.gorrect.protocol.ControlMessage.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath());
    Files.writeString(
        launcher,
        "#!/bin/sh\nexec "
            + quote(System.getProperty("gorrect.java21"))
            + " -cp "
            + quote(classpath)
            + " "
            + FailureTarget.class.getName()
            + " "
            + mode
            + " \"$@\"\n");
    Files.setPosixFilePermissions(
        launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var object = JsonFiles.read(profile);
    object.addProperty("java", launcher.toString());
    var limits = object.getAsJsonObject("limits");
    limits.addProperty("startupSeconds", 1);
    limits.addProperty("runSeconds", 2);
    JsonFiles.write(profile, object);
    var result = RunEngine.run(input(CASE), profile, directory.resolve("injected"));
    var expected =
        switch (mode) {
          case "crash" -> RunReport.Outcome.CRASH;
          case "hang", "startup-timeout", "silent-handshake" -> RunReport.Outcome.TIMEOUT;
          case "failure" -> RunReport.Outcome.RUNTIME_ERROR;
          default -> RunReport.Outcome.PROTOCOL_ERROR;
        };
    assertEquals(expected, result.execution(), result.toString());
    assertSocketRemoved(directory.resolve("injected"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertTrue(Files.isRegularFile(directory.resolve("injected/traces/control.json")));
    assertTrue(Files.isRegularFile(directory.resolve("injected/result.json")));
  }

  @Test
  void recordsInstallationChangesAndVerifiesWithoutTheLauncher() throws IOException {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path profile = profile(21), launcher = directory.resolve("java-launcher");
    String script = "#!/bin/sh\nexec " + quote(System.getProperty("gorrect.java21")) + " \"$@\"\n";
    Files.writeString(launcher, script);
    Files.setPosixFilePermissions(
        launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var object = JsonFiles.read(profile);
    object.addProperty("java", launcher.toString());
    JsonFiles.write(profile, object);
    Path original = directory.resolve("original");
    assertPass(RunEngine.probe(profile, original));
    Files.writeString(launcher, script + "# Changed launcher identity\n");
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(original, replay));
    assertTrue(
        JsonFiles.read(replay.resolve("manifest.json"))
            .getAsJsonArray("changedInstallationArtifacts")
            .contains(new com.google.gson.JsonPrimitive("java")));
    Files.delete(launcher);
    Files.delete(profile);
    assertPass(RunEngine.verify(original));
  }

  private static String quote(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  @Test
  void keepsTheSocketOutsideLongRunPaths() throws IOException {
    Path output = directory.resolve("run-" + "x".repeat(160));
    assertPass(RunEngine.probe(profile(21), output));
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    assertEquals("unix", manifest.get("controlTransport").getAsString());
    assertFalse(Path.of(manifest.get("controlSocket").getAsString()).startsWith(output));
    assertSocketRemoved(output);
  }

  private static void assertSocketRemoved(Path output) throws IOException {
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    Path address = Path.of(manifest.get("controlSocket").getAsString());
    assertFalse(Files.exists(address));
    assertFalse(Files.exists(address.getParent()));
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
    assertPass(RunEngine.run(input(program), profile(feature), directory.resolve("arrays")));
  }

  @Test
  void replayRestoresTheRecordedEnvironment() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.probe(profile(21), run));
    var manifest = JsonFiles.read(run.resolve("manifest.json"));
    // Model an inherited variable from the original engine, absent from this process's environment.
    manifest.getAsJsonObject("environment").addProperty("GORRECT_SAVED_ENVIRONMENT", "retained");
    JsonFiles.write(run.resolve("manifest.json"), manifest);
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertEquals(
        "retained",
        JsonFiles.read(replay.resolve("manifest.json"))
            .getAsJsonObject("environment")
            .get("GORRECT_SAVED_ENVIRONMENT")
            .getAsString());
  }

  private Path profile(int feature) throws IOException {
    Path support = Path.of(System.getProperty("gorrect.support"));
    var object = new JsonObject();
    object.addProperty("version", 1);
    object.addProperty("vm", "hotspot");
    object.addProperty("java", System.getProperty("gorrect.java" + feature));
    object.addProperty("collector", "serial");
    object.addProperty("observation", "named-roots-diagnostic");
    object.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xbatch")));
    var artifacts = new JsonObject();
    artifacts.addProperty("runtime", support.resolve("runtime.jar").toString());
    artifacts.addProperty("protocol", support.resolve("protocol.jar").toString());
    artifacts.addProperty(
        "observer", support.resolve(System.mapLibraryName("gorrect_observer")).toString());
    object.add("support", artifacts);
    var limits = new JsonObject();
    limits.addProperty("records", 10000);
    object.add("limits", limits);
    Path profile = directory.resolve("profile.json");
    JsonFiles.write(profile, object);
    return profile;
  }

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
    Path output = directory.resolve("references"), selected = profile(feature);
    assertPass(RunEngine.run(input(program), selected, output));
    assertPass(RunEngine.verify(output));
    assertPass(RunEngine.replay(output, directory.resolve("reference-replay")));
    String source = Files.readString(output.resolve("generated/Case.java"));
    assertTrue(source.contains(".get();"));
    assertTrue(source.contains("Reference.reachabilityFence"));
    long run = JsonFiles.read(output.resolve("manifest.json")).get("run").getAsLong();
    var history =
        io.connglli.gorrect.protocol.ReferenceHistory.read(
            output.resolve("traces/references.ghistory"), run);
    var acquisitions =
        history.events().stream()
            .filter(e -> e.kind() == io.connglli.gorrect.protocol.ReferenceHistory.Kind.ACQUIRE)
            .toList();
    assertNotNull(acquisitions.getFirst().target());
    assertNull(acquisitions.get(1).target());
    assertEquals(
        3,
        history.events().stream()
            .filter(e -> e.kind() == io.connglli.gorrect.protocol.ReferenceHistory.Kind.INSTALL)
            .map(e -> e.wrapper())
            .distinct()
            .count());
    Path damaged = copy(output, "reference-damage");
    var events = new ArrayList<>(history.events());
    var first = events.getFirst();
    events.set(
        0,
        new io.connglli.gorrect.protocol.ReferenceHistory.Event(
            first.sequence(),
            first.kind(),
            first.strength(),
            first.wrapper(),
            new io.connglli.gorrect.protocol.ReferenceHistory.Target(0, 0, 99)));
    new io.connglli.gorrect.protocol.ReferenceHistory(run, true, events)
        .write(damaged.resolve("traces/references.ghistory"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(damaged).verdict());
    Path missing = copy(output, "reference-gap");
    Files.delete(missing.resolve("traces/references.ghistory"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
    Path partial = copy(output, "reference-incomplete");
    new io.connglli.gorrect.protocol.ReferenceHistory(run, false, history.events())
        .write(partial.resolve("traces/references.ghistory"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(partial).execution());
    try (var input = getClass().getResourceAsStream("/cases/references.gir")) {
      String example =
          new String(
              java.util.Objects.requireNonNull(input).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      assertPass(RunEngine.run(input(example), selected, directory.resolve("reference-example")));
    }
  }

  @Test
  void reportsReferenceSearchLimitsAndUnsupportedCombinations() throws IOException {
    Path selected = profile(21);
    String references =
        "gir 1; shape N object 1 0; object 1 N [weak(2)] []; object 2 N [null] []; root r = 1;"
            + " program { checkpoint; }";
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("limits").addProperty("historyStates", 1);
    JsonFiles.write(selected, raw);
    var limited = RunEngine.run(input(references), selected, directory.resolve("reference-bound"));
    assertEquals(RunReport.Outcome.COMPLETED, limited.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, limited.verdict());
    String parallel = references.replace("checkpoint;", "parallel { worker a {} worker b {} }");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(parallel), selected, directory.resolve("parallel-references"))
            .execution());
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("reference-reclamation")));
    JsonFiles.write(selected, raw);
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(references), selected, directory.resolve("reclamation")).execution());
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
    var result = RunEngine.run(input(program), profile(21), output);
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertEquals(RunReport.Outcome.INVALID_WORKLOAD, RunEngine.verify(output).execution());
  }

  @Test
  void probesReferenceCaptureWhenTheProfileRequiresIt() throws IOException {
    Path selected = profile(21);
    var raw = JsonFiles.read(selected);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(List.of("reference-histories", "reference-queues")));
    JsonFiles.write(selected, raw);
    Path output = directory.resolve("reference-probe");
    assertPass(RunEngine.probe(selected, output));
    assertTrue(Files.exists(output.resolve("traces/references.ghistory")));
  }

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
    assertPass(RunEngine.run(input(program), profile(feature), output));
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    var trace =
        ExecutionTrace.read(
            output.resolve("traces/execution.gtrace"), manifest.get("run").getAsLong());
    assertEquals(java.util.Set.of(0, 1, 2), trace.byWorker().keySet());
    assertPass(RunEngine.verify(output));
    Path missing = copy(output, "missing-synchronization");
    Files.delete(missing.resolve("traces/workers.gevidence"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
    Path barrier = copy(output, "missing-barrier");
    var events = new ArrayList<>(trace.events());
    int index =
        java.util.stream.IntStream.range(0, events.size())
            .filter(i -> events.get(i).kind() == ExecutionTrace.Kind.BARRIER)
            .findFirst()
            .orElseThrow();
    events.remove(index);
    new ExecutionTrace(trace.run(), true, events).write(barrier.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(barrier).verdict());
    Path invalid = copy(output, "contradictory-joins");
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
    Path profile = profile(21), output = directory.resolve("campaign");
    var messages = new java.io.ByteArrayOutputStream();
    int status =
        FuzzCampaign.run(
            profile,
            output,
            "concurrent",
            2,
            java.time.Duration.ofSeconds(3),
            42,
            new java.io.PrintStream(messages));
    assertTrue(java.util.Set.of(0, 3, 5).contains(status), messages.toString());
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
            input(CASE), profile, limited, java.time.Duration.ZERO, java.util.Map.of("seed", 7L));
    assertEquals(RunReport.Outcome.CAMPAIGN_LIMIT, result.execution());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertEquals(5, result.exitStatus());
  }

  private Path input(String text) throws IOException {
    Path path = directory.resolve("case.gir");
    Files.writeString(path, text);
    return path;
  }

  private Path copy(Path source, String name) throws IOException {
    Path target = directory.resolve(name);
    Artifacts.copyTree(source, target);
    return target;
  }

  private static void assertPass(RunReport result) {
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict(), result.toString());
    assertEquals(0, result.exitStatus());
  }

  private static void rewriteSnapshot(Path directory, boolean remove) throws IOException {
    Path file = directory.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(file));
    int index =
        java.util.stream.IntStream.range(0, snapshot.records().size())
            .filter(
                i -> {
                  var record = snapshot.records().get(i);
                  return record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD && record.d() == 7;
                })
            .findFirst()
            .orElseThrow();
    var records = new ArrayList<>(snapshot.records());
    var record = records.get(index);
    if (remove) records.remove(index);
    else
      records.set(
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
    Files.write(
        file,
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
