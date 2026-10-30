package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HotSpotCollectorTest {
  @Test
  void unavailableWhiteBoxControlDoesNotFallBackToSystemGc() throws IOException {
    Path selected = profile(21, "serial", "default"), empty = directory.resolve("client.jar");
    try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(empty))) {
      jar.flush();
    }
    var raw = JsonFiles.read(selected);
    raw.getAsJsonObject("support").addProperty("whitebox", empty.toString());
    var gc = new JsonObject();
    gc.addProperty("control", "whitebox-full");
    raw.add("gc", gc);
    JsonFiles.write(selected, raw);
    var report = RunEngine.run(input(STRONG), selected, directory.resolve("unavailable-control"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, report.execution(), report.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict());
  }

  @Test
  void invalidBaseOptionsRemainStartupFailures() throws IOException {
    Path selected = profile(21, "g1", "default");
    var raw = JsonFiles.read(selected);
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-XX:GorrectUnknownOption=true")));
    JsonFiles.write(selected, raw);
    assertEquals(
        RunReport.Outcome.STARTUP_FAILURE,
        RunEngine.run(input(STRONG), selected, directory.resolve("invalid-base")).execution());
  }

  @Test
  void combinedCapabilityProbeStillExecutesACollection() throws IOException {
    Path selected = profile(21, "g1", "default");
    var raw = JsonFiles.read(selected);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of("tagging", "named-roots", "contended-histories", "gc-completion")));
    JsonFiles.write(selected, raw);
    pass(RunEngine.probe(selected, directory.resolve("combined-probe")));
  }

  @TempDir Path directory;
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
  private static final String HISTORY =
      """
      gir 1; shape N object 2 1;
      object 1 N [soft(2), weak(2)] [0]; object 2 N [null, null] [7]; object 3 N [null, null] [0];
      root owner = 1; root guard = 2; root pending = 3; root left = null; root right = null;
      program {
        scope { read-root owner N owner; acquire held N owner 0 {
          write-root guard null;
          parallel {
            worker a { exchange-root n N pending null; if-present n { write-data n 0 11; write-volatile-root left n; } }
            worker b { exchange-root n N pending null; if-present n { write-data n 0 22; write-volatile-root right n; } }
          }
        } }
        collect; checkpoint;
      }
      """;

  static Stream<Arguments> profiles() {
    return Stream.of(17, 21)
        .flatMap(
            feature ->
                Stream.of(
                    Arguments.of(feature, "serial", "default"),
                    Arguments.of(feature, "parallel", "default"),
                    Arguments.of(feature, "g1", "default"),
                    Arguments.of(feature, "zgc", "non-generational"),
                    Arguments.of(feature, "shenandoah", "satb"),
                    Arguments.of(feature, "shenandoah", "iu"),
                    Arguments.of(feature, "shenandoah", "passive")));
  }

  @ParameterizedTest
  @MethodSource("profiles")
  void verifiesGraphsCyclesAndSavedCollectorModes(int feature, String collector, String mode)
      throws IOException {
    execute(feature, collector, mode);
  }

  @Test
  void verifiesGenerationalZgcOnJdk21() throws IOException {
    execute(21, "zgc", "generational");
  }

  private void execute(int feature, String collector, String mode) throws IOException {
    Path profile = profile(feature, collector, mode), strong = directory.resolve("strong");
    var result = RunEngine.run(input(STRONG), profile, strong);
    // Some JDK distributions omit a collector; retain its unsupported bundle and report a skip.
    boolean unavailable =
        result.execution() == RunReport.Outcome.UNSUPPORTED
            && result.problems().stream()
                .anyMatch(
                    problem ->
                        problem.startsWith("Collector probe failed")
                            || problem.startsWith("Collector mode unavailable")
                            || problem.startsWith("ZGC mode unavailable"));
    Assumptions.assumeFalse(unavailable, result.toString());
    pass(result);
    pass(RunEngine.verify(strong));
    assertEquals(
        mode,
        JsonFiles.read(strong.resolve("manifest.json"))
            .getAsJsonObject("profile")
            .get("collectorMode")
            .getAsString());
    pass(RunEngine.run(input(HISTORY), profile, directory.resolve("history")));
    if (collector.equals("g1")) {
      pass(RunEngine.replay(strong, directory.resolve("replay")));
      var manifest = JsonFiles.read(strong.resolve("manifest.json"));
      var target = new java.util.Properties();
      try (var in = Files.newInputStream(strong.resolve("target.properties"))) {
        target.load(in);
      }
      assertThrows(
          IOException.class,
          () ->
              HotSpotCollections.read(
                  strong.resolve("traces/workers.jfr"),
                  manifest.get("run").getAsLong(),
                  Long.MAX_VALUE,
                  Long.parseLong(target.getProperty("vm.start"))));
      Files.delete(strong.resolve("traces/workers.jfr"));
      var missing = RunEngine.verify(strong);
      assertEquals(ExactVerifier.Verdict.SATISFIED, missing.verdict());
      assertTrue(missing.unmetRequirements().contains("gc-completion"));
    }
  }

  @Test
  void unsupportedModesAndIgnoredRequestsDoNotBecomeCompletionProof() throws IOException {
    Path selected = profile(17, "zgc", "generational");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(STRONG), selected, directory.resolve("unsupported-mode")).execution());
    selected = profile(21, "serial", "default");
    var raw = JsonFiles.read(selected);
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-XX:+DisableExplicitGC")));
    JsonFiles.write(selected, raw);
    var ignored = RunEngine.run(input(STRONG), selected, directory.resolve("ignored"));
    assertEquals(RunReport.Outcome.COMPLETED, ignored.execution());
    assertEquals(ExactVerifier.Verdict.SATISFIED, ignored.verdict());
    assertTrue(ignored.unmetRequirements().contains("gc-completion"));
  }

  private Path profile(int feature, String collector, String mode) throws IOException {
    Path support = Path.of(System.getProperty("gorrect.support"));
    var raw = new JsonObject();
    raw.addProperty("version", 1);
    raw.addProperty("vm", "hotspot");
    raw.addProperty("java", System.getProperty("gorrect.java" + feature));
    raw.addProperty("collector", collector);
    raw.addProperty("collectorMode", mode);
    raw.addProperty("observation", "named-roots-diagnostic");
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    var artifacts = new JsonObject();
    artifacts.addProperty("runtime", support.resolve("runtime.jar").toString());
    artifacts.addProperty("protocol", support.resolve("protocol.jar").toString());
    artifacts.addProperty(
        "observer", support.resolve(System.mapLibraryName("gorrect_observer")).toString());
    raw.add("support", artifacts);
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(List.of("tagging", "named-roots", "gc-completion")));
    var limits = new JsonObject();
    limits.addProperty("historyTransitions", 10_000_000);
    limits.addProperty("records", 20000);
    raw.add("limits", limits);
    Path path = directory.resolve("profile.json");
    JsonFiles.write(path, raw);
    return path;
  }

  private Path input(String text) throws IOException {
    Path file = directory.resolve("input.gir");
    Files.writeString(file, text);
    return file;
  }

  private static void pass(RunReport report) {
    assertEquals(0, report.exitStatus(), report.toString());
  }
}
