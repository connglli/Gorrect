package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Selected OpenJ9 conformance; retained scopes and actual compiled execution are separate proofs.
 */
@Tag("openj9")
class RetainedOpenJ9RunTest {
  @TempDir Path directory;

  @Test
  void verifiesAndReplaysTheGeneratedCallerAndRepeatedWorkerCuts() throws IOException {
    Path run = directory.resolve("run");
    assertPass(
        RunEngine.run(
            input(
                directory,
                RetainedCaptureRunTest.PROGRAM.replace(
                    "read-root empty N main; checkpoint;\n",
                    "read-root empty N main; checkpoint; release caller; release empty;\n")),
            selected(false),
            run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Test
  void varHandleWorkerCompilationRequiresActualFramesWithLocalAccessAbsent() throws IOException {
    String program =
        """
        gir 1; require compiled-execution;
        shape N object 0 1; object 1 N [] [7]; root main = 1;
        program { repeat 3 { parallel {
          worker a { read-root held N main; read-volatile-data value held 0; release held; checkpoint; }
          worker b { read-root held N main; read-volatile-data value held 0; release held; checkpoint; }
        } } }
        """;
    Path run = directory.resolve("compiled");
    assertPass(RunEngine.run(input(directory, program), selected(true), run));
    assertPass(RunEngine.verify(run));
    long token = JsonFiles.number(JsonFiles.read(run.resolve("manifest.json")).get("run"));
    assertEquals(
        0, Artifacts.snapshot(run, token, 2).capabilities() & HeapSnapshot.LOCAL_VARIABLES);
    assertTrue(
        VmDriver.compiledFrames(
                io.connglli.gorrect.engine.config.TargetProfile.parse(
                    JsonFiles.read(run.resolve("manifest.json")).getAsJsonObject("profile"), run),
                run,
                token)
            .stream()
            .anyMatch(io.connglli.gorrect.protocol.ExecutionEvidence.Frame::compiled));
    assertPass(RunEngine.replay(run, directory.resolve("compiled-replay")));
  }

  @Test
  void compilationCannotRepairAbsentPhysicalBindingOrigins() throws IOException {
    String program =
        """
        gir 1; require compiled-execution;
        shape N object 0 1; object 1 N [] [7]; root main = 1;
        program { repeat 3 { parallel {
          worker a { read-root held N main; read-volatile-data value held 0; checkpoint; }
          worker b { read-root held N main; read-volatile-data value held 0; checkpoint; }
        } } }
        """;
    Path run = directory.resolve("compiled-live");
    var result = RunEngine.run(input(directory, program), selected(true), run);
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    assertTrue(result.unmetRequirements().isEmpty(), result.toString());
    assertTrue(
        result.problems().stream()
            .allMatch(problem -> problem.contains("physical root for binding")),
        result.toString());
    assertEquals(result, RunEngine.verify(run));
  }

  private Path selected(boolean jit) throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    settings.add(
        "vmArgs",
        JsonFiles.GSON.toJsonTree(jit ? List.of("-Xmx128m") : List.of("-Xmx128m", "-Xint")));
    settings.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(jit ? List.of("tagging") : List.of("tagging", "local-bindings")));
    if (jit) settings.add("jit", new JsonObject());
    var limits = new JsonObject();
    limits.addProperty("startupSeconds", 60);
    limits.addProperty("runSeconds", 120);
    limits.addProperty("historyTransitions", 1_000_000);
    settings.add("limits", limits);
    Path path = directory.resolve("profile.json");
    JsonFiles.write(path, settings);
    return path;
  }
}
