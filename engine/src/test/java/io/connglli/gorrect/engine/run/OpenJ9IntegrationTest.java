package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.fuzz.WorkloadGenerator;
import io.connglli.gorrect.engine.gir.GirFormatter;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises supported semantics and independent coverage on an explicitly selected OpenJ9. */
class OpenJ9IntegrationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"gencon", "balanced", "optavgpause", "optthruput", "metronome"})
  void verifiesStrongConcurrentAndConditionalGraphs(String collector) throws IOException {
    Path selected = profile(collector, false);
    var strong = directory.resolve("strong");
    pass(RunEngine.run(input(STRONG), selected, strong));
    pass(RunEngine.verify(strong));
    for (String resource : List.of("references", "contended"))
      pass(RunEngine.run(resource(resource), selected, directory.resolve(resource)));
    if (collector.equals("gencon")) {
      pass(RunEngine.replay(strong, directory.resolve("replay")));
      var manifest = JsonFiles.read(strong.resolve("manifest.json"));
      assertTrue(
          manifest.getAsJsonObject("installationArtifacts").keySet().stream()
              .anyMatch(key -> key.contains("j9gc")));
      assertTrue(
          manifest.getAsJsonObject("installationArtifacts").keySet().stream()
              .anyMatch(key -> key.contains("j9jit")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gencon", "balanced", "optavgpause", "optthruput", "metronome"})
  void verifiesCompiledWorkerCalls(String collector) throws IOException {
    Path selected = profile(collector, true);
    Path run = directory.resolve("jit");
    var generated = WorkloadGenerator.generate(42, "concurrent-jit", 2, 2);
    // Isolate VM compilation evidence; campaign runs assess scheduling-dependent joint stress.
    var program =
        new io.connglli.gorrect.engine.gir.GirProgram(
            generated.version(),
            generated.shapes(),
            generated.objects(),
            generated.roots(),
            generated.body(),
            generated.labels(),
            java.util.Set.of("compiled-execution"));
    pass(RunEngine.run(input(GirFormatter.format(program)), selected, run));
    pass(RunEngine.verify(run));
    if (collector.equals("gencon")) {
      pass(RunEngine.replay(run, directory.resolve("replay")));
      Files.delete(run.resolve("traces/workers.gstacks"));
      var missing = RunEngine.verify(run);
      assertEquals(ExactVerifier.Verdict.SATISFIED, missing.verdict());
      assertTrue(missing.unmetRequirements().contains("compiled-execution"));
    }
  }

  @Test
  void interpretedExecutionKeepsGraphVerdictButCannotMeetCompilation() throws IOException {
    Path selected = profile("gencon", true);
    var raw = JsonFiles.read(selected);
    raw.getAsJsonArray("vmArgs").add("-Xint");
    raw.getAsJsonObject("jit").addProperty("deadlineMillis", 50);
    JsonFiles.write(selected, raw);
    var result = RunEngine.probe(selected, directory.resolve("interpreted"));
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict());
    assertTrue(result.unmetRequirements().contains("compiled-execution"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"gc-completion", "local-bindings", "reference-reclamation"})
  void unsupportedRequirementsAreReportedByProbe(String requirement) throws IOException {
    Path selected = profile("gencon", false);
    var raw = JsonFiles.read(selected);
    raw.getAsJsonArray("requirements").add(requirement);
    JsonFiles.write(selected, raw);
    var result = RunEngine.probe(selected, directory.resolve("unsupported"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, result.execution(), result.toString());
    assertTrue(result.unmetRequirements().contains(requirement));
  }

  @Test
  void invalidBaseOptionsRemainStartupFailures() throws IOException {
    Path selected = profile("gencon", false);
    var raw = JsonFiles.read(selected);
    raw.getAsJsonArray("vmArgs").add("-XgorrectUnknownOption");
    JsonFiles.write(selected, raw);
    assertEquals(
        RunReport.Outcome.STARTUP_FAILURE,
        RunEngine.probe(selected, directory.resolve("invalid-base")).execution());
  }

  private Path profile(String collector, boolean jit) throws IOException {
    Path support = Path.of(System.getProperty("gorrect.support"));
    var raw = new JsonObject();
    raw.addProperty("version", 1);
    raw.addProperty("vm", "openj9");
    raw.addProperty("java", System.getProperty("gorrect.openj9.java"));
    raw.addProperty("collector", collector);
    raw.addProperty("observation", "named-roots-diagnostic");
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xshareclasses:none")));
    var libraries = new JsonObject();
    libraries.addProperty("runtime", support.resolve("runtime.jar").toString());
    libraries.addProperty("protocol", support.resolve("protocol.jar").toString());
    libraries.addProperty("observer", support.resolve("libgorrect_observer.so").toString());
    raw.add("support", libraries);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of(
                "tagging",
                "named-roots",
                "gc-events",
                "compilation-events",
                "reference-histories",
                "reference-queues",
                "contended-histories")));
    var limits = new JsonObject();
    limits.addProperty("startupSeconds", 30);
    limits.addProperty("runSeconds", 90);
    raw.add("limits", limits);
    if (jit) {
      raw.add("jit", new JsonObject());
      raw.getAsJsonArray("requirements").add("compiled-execution");
    }
    Path path = directory.resolve("profile.json");
    JsonFiles.write(path, raw);
    return path;
  }

  private Path input(String source) throws IOException {
    Path path = directory.resolve("case.gir");
    Files.writeString(path, source);
    return path;
  }

  private Path resource(String name) throws IOException {
    try (var in = getClass().getResourceAsStream("/cases/" + name + ".gir")) {
      return input(
          new String(
              java.util.Objects.requireNonNull(in).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private static void pass(RunReport report) {
    assertEquals(RunReport.Outcome.COMPLETED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, report.verdict(), report.toString());
    assertTrue(report.unmetRequirements().isEmpty(), report.toString());
  }

  private static final String STRONG =
      """
      gir 1; shape N object 2 1; shape R refs 2; shape D data 2;
      object 1 N [2, 3] [7]; object 2 R [1, null] []; object 3 D [] [0, 9];
      object 4 N [null, null] [0]; object 5 N [null, null] [0];
      root main = 1; root left = 4; root right = 5;
      program {
        parallel {
          worker a { read-root n N left; allocate child N; write-data child 0 11; write-ref n 0 child; }
          worker b { read-root n N right; allocate child N; write-data child 0 22; write-ref n 0 child; }
        }
        collect; checkpoint;
        scope { read-root n N main; read-ref links R n 0; write-ref links 1 n; }
        collect; checkpoint;
      }
      """;
}
