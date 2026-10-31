package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("hotspot")
class YoungGcRunTest {
  @TempDir Path directory;
  private static final String PROGRAM =
      """
      gir 1; shape N object 0 1; object 1 N [] [7]; root main = 1;
      program {
        scope { read-root owner N main; if-present owner { collect; } }
        repeat 2 { collect; }
        parallel { worker a { collect; barrier; } worker b { barrier; collect; } }
        checkpoint;
      }
      """;

  static Stream<Arguments> collectors() {
    return Stream.of(17, 21)
        .flatMap(
            feature ->
                Stream.of("serial", "parallel", "g1")
                    .map(collector -> Arguments.of(feature, collector)));
  }

  @ParameterizedTest
  @MethodSource("collectors")
  void certifiesMainRepeatedConditionalAndWorkerYoungRequests(int feature, String collector)
      throws IOException {
    Path selected = profile(feature, collector, client("native"));
    Path run = directory.resolve("run");
    // A prior in-flight cycle may satisfy a coalesced native call without lying inside its span.
    // Separate worker phases make the per-request certificate premise deterministic.
    var report = RunEngine.run(input(directory, PROGRAM), selected, run);
    assertEquals(0, report.exitStatus(), report.toString());
    assertPass(RunEngine.verify(run));
    assertEquals("whitebox-young", Artifacts.target(run).getProperty("gc.control"));
    if (feature == 21 && collector.equals("g1"))
      assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Test
  void youngControlAndCertificatesWorkWithJitSettings() throws IOException {
    Path selected = profile(21, "g1", client("native"));
    var raw = JsonFiles.read(selected);
    var jit = new JsonObject();
    jit.addProperty("level", 1);
    jit.addProperty("warmupRounds", 0);
    raw.add("jit", jit);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of(
                "tagging", "named-roots", "young-gc", "compiled-execution", "gc-during-workers")));
    JsonFiles.write(selected, raw);
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("jit")));
  }

  @Test
  void combinedProbeActuallyExercisesTheRequestedYoungPolicy() throws IOException {
    Path selected = profile(21, "serial", client("native"));
    var raw = JsonFiles.read(selected);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of("tagging", "named-roots", "contended-histories", "young-gc")));
    JsonFiles.write(selected, raw);
    assertPass(RunEngine.probe(selected, directory.resolve("probe")));
  }

  @Test
  void startupExerciseCannotSatisfyAWorkloadWithoutCollection() throws IOException {
    Path selected = profile(21, "serial", client("native"));
    var report =
        RunEngine.run(
            input(directory, "gir 1; program { checkpoint; }"),
            selected,
            directory.resolve("no-request"));
    assertEquals(RunReport.Outcome.COMPLETED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, report.verdict());
    assertTrue(report.unmetRequirements().contains("young-gc"));
    assertTrue(
        report.problems().stream().anyMatch(problem -> problem.contains("No explicit collection")),
        report.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"noop", "full"})
  void ignoredCallsAndFullOnlyFallbackCannotCertifyYoungCoverage(String mode) throws IOException {
    Path selected = profile(21, "serial", client(mode));
    var report = RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("ignored"));
    assertEquals(RunReport.Outcome.COMPLETED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, report.verdict());
    assertTrue(report.unmetRequirements().contains("young-gc"));
  }

  @Test
  void unavailableAndFailingControlsRetainTheirDistinctOutcomes() throws IOException {
    Path selected = profile(21, "serial", client("missing"));
    var unavailable =
        RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("unavailable"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, unavailable.execution(), unavailable.toString());
    assertFalse(Files.exists(directory.resolve("unavailable/traces/execution.gtrace")));

    selected = profile(21, "serial", client("fail"));
    var failed = RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("failed"));
    assertEquals(RunReport.Outcome.RUNTIME_ERROR, failed.execution(), failed.toString());
    assertTrue(
        Files.readString(directory.resolve("failed/logs/stderr.log"))
            .contains("injected collection failure"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unregistered", "startup-fail"})
  void startupMethodFailuresRetainTheirOriginWithoutExecutingTheWorkload(String mode)
      throws IOException {
    Path selected = profile(21, "serial", client(mode));
    Path run = directory.resolve("startup-cause");
    var report = RunEngine.run(input(directory, PROGRAM), selected, run);
    assertEquals(RunReport.Outcome.UNSUPPORTED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict());
    assertFalse(Files.exists(run.resolve("traces/execution.gtrace")));
    String cause = mode.equals("unregistered") ? "UnsatisfiedLinkError" : "IllegalStateException";
    String detail = mode.equals("unregistered") ? "youngGC" : "injected startup cause";
    var target = Artifacts.target(run);
    for (String property : List.of("gc.problem", "jit.problem")) {
      assertTrue(target.getProperty(property).contains(cause), target.toString());
      assertTrue(target.getProperty(property).contains(detail), target.toString());
    }
    assertTrue(
        report.unmetRequirements().stream()
            .anyMatch(gap -> gap.contains(cause) && gap.contains(detail)),
        report.toString());
  }

  @Test
  void incompatibleCollectorAndCompletionRequestsStopBeforeWorkloadExecution() throws IOException {
    Path selected = profile(21, "zgc", client("native"));
    var unsupported =
        RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("collector"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, unsupported.execution(), unsupported.toString());
    assertFalse(Files.exists(directory.resolve("collector/traces/execution.gtrace")));
    assertFalse(Files.exists(directory.resolve("collector/target.properties")));

    selected = profile(21, "serial", client("native"));
    var raw = JsonFiles.read(selected);
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion", "young-gc")));
    JsonFiles.write(selected, raw);
    unsupported =
        RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("combination"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, unsupported.execution(), unsupported.toString());
    assertFalse(Files.exists(directory.resolve("combination/traces/execution.gtrace")));
    assertFalse(Files.exists(directory.resolve("combination/target.properties")));
  }

  static Stream<Arguments> evidenceGaps() {
    return Stream.of(false, true)
        .flatMap(
            history -> Stream.of(false, true).map(workerGap -> Arguments.of(history, workerGap)));
  }

  @ParameterizedTest
  @MethodSource("evidenceGaps")
  void missingRecordingCannotHideAnExactOrHistoryGraphRefutation(boolean history, boolean workerGap)
      throws IOException {
    String program =
        history
            ? "gir 1; shape N object 1 1; object 1 N [weak(1)] [7]; root main = 1; program { scope"
                + " { read-root owner N main; collect; is-cleared value owner 0; } checkpoint; }"
            : PROGRAM;
    Path selected = profile(21, "serial", client("native"));
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, program), selected, run));
    Path changed = copy(directory, run, "changed");
    RunTestSupport.rewriteSnapshot(changed, false);
    Files.delete(changed.resolve("traces/workers.jfr"));
    if (workerGap) {
      long runToken = JsonFiles.read(changed.resolve("manifest.json")).get("run").getAsLong();
      Path evidencePath = changed.resolve("traces/workers.gevidence");
      var evidence = ExecutionEvidence.read(evidencePath, runToken);
      new ExecutionEvidence(runToken, false, evidence.groups(), evidence.windows())
          .write(evidencePath);
    }
    var report = RunEngine.verify(changed);
    assertEquals(ExactVerifier.Verdict.VIOLATED, report.verdict(), report.toString());
    assertTrue(report.unmetRequirements().contains("young-gc"), report.toString());
    assertTrue(
        report.problems().stream()
            .anyMatch(problem -> problem.contains("Collection certificate unavailable")),
        report.toString());
  }

  @Test
  void malformedWorkerStructureCannotHideAGraphViolationOrUnverifiedCertificate()
      throws IOException {
    Path selected = profile(21, "serial", client("native"));
    Path run = directory.resolve("malformed");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    Path evidencePath = run.resolve("traces/workers.gevidence");
    var evidence = ExecutionEvidence.read(evidencePath, token);
    var windows = new java.util.ArrayList<>(evidence.windows());
    windows.add(windows.getFirst());
    new ExecutionEvidence(token, true, evidence.groups(), windows).write(evidencePath);
    RunTestSupport.rewriteSnapshot(run, false);

    var report = RunEngine.verify(run);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, report.verdict(), report.toString());
    assertTrue(report.unmetRequirements().contains("young-gc"), report.toString());
    assertTrue(
        report.problems().stream().anyMatch(problem -> problem.contains("Duplicate worker window")),
        report.toString());
  }

  private Path profile(int feature, String collector, Path client) throws IOException {
    var raw =
        TestProfiles.create("hotspot", System.getProperty("gorrect.java" + feature), collector);
    raw.getAsJsonObject("support").addProperty("whitebox", client.toString());
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xbatch")));
    var gc = new JsonObject();
    gc.addProperty("control", "whitebox-young");
    raw.add("gc", gc);
    raw.add(
        "requirements", JsonFiles.GSON.toJsonTree(List.of("tagging", "named-roots", "young-gc")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, raw);
    return selected;
  }

  /**
   * A bootstrap fixture exercises only the declared native methods; other VM warnings stay logged.
   */
  private Path client(String mode) throws IOException {
    Path base = directory.resolve("client-" + mode);
    Path source = base.resolve("source/jdk/test/whitebox/WhiteBox.java");
    Files.createDirectories(source.getParent());
    String initialization =
        mode.equals("native")
            ? "private static native void registerNatives(); static { registerNatives(); }"
            : "";
    String young =
        switch (mode) {
          case "native" -> "public native void youngGC(); public native void fullGC();";
          case "unregistered" -> "public native void youngGC();";
          case "startup-fail" ->
              "public void youngGC() { throw new IllegalStateException(\"injected startup cause\");"
                  + " }";
          case "noop" -> "public void youngGC() {}";
          case "full" -> "public void youngGC() { System.gc(); }";
          case "fail" ->
              "private int calls; public void youngGC() { if (++calls > 1) throw new"
                  + " IllegalStateException(\"injected collection failure\"); }";
          case "missing" -> "";
          default -> throw new IllegalArgumentException(mode);
        };
    Files.writeString(
        source,
        """
        package jdk.test.whitebox;
        import java.lang.reflect.Executable;
        public final class WhiteBox {
          %s
          private static final WhiteBox INSTANCE = new WhiteBox();
          public static WhiteBox getWhiteBox() { return INSTANCE; }
          %s
          private native boolean testSetDontInlineMethod0(Executable method, boolean value);
          public boolean testSetDontInlineMethod(Executable method, boolean value) { return testSetDontInlineMethod0(method, value); }
          private native boolean enqueueMethodForCompilation0(Executable method, int level, int bci);
          public boolean enqueueMethodForCompilation(Executable method, int level) { return enqueueMethodForCompilation0(method, level, -1); }
          private native int getMethodCompilationLevel0(Executable method, boolean osr);
          public int getMethodCompilationLevel(Executable method) { return getMethodCompilationLevel0(method, false); }
        }
        """
            .formatted(initialization, young));
    Path classes = base.resolve("classes");
    Files.createDirectories(classes);
    try (var compiler =
        ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
      boolean success =
          ToolProvider.getSystemJavaCompiler()
              .getTask(
                  null,
                  compiler,
                  null,
                  List.of(
                      "--release", "17", "-g", "-Xlint:all", "-Werror", "-d", classes.toString()),
                  null,
                  compiler.getJavaFileObjects(source))
              .call();
      assertTrue(success);
    }
    Path jar = base.resolve("whitebox.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("jdk/test/whitebox/WhiteBox.class"));
      Files.copy(classes.resolve("jdk/test/whitebox/WhiteBox.class"), output);
      output.closeEntry();
    }
    return jar;
  }
}
