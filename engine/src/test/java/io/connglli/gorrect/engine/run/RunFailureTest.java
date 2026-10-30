package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.CASE;
import static io.connglli.gorrect.engine.run.RunTestSupport.assertSocketRemoved;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static io.connglli.gorrect.engine.run.RunTestSupport.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ControlMessage;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Process failure classification and retained diagnostics through the public execution path. */
@Tag("hotspot")
class RunFailureTest {
  @TempDir Path directory;

  @Test
  void retainsCompilationAndStartupDiagnostics() throws IOException {
    Path profile = profile(directory, 21);
    var object = JsonFiles.read(profile);
    var support = object.getAsJsonObject("support");
    String runtime = support.get("runtime").getAsString();
    support.addProperty("runtime", support.get("protocol").getAsString());
    JsonFiles.write(profile, object);
    Path compilation = directory.resolve("compilation");
    assertEquals(
        RunReport.Outcome.COMPILATION_FAILURE,
        RunEngine.run(input(directory, CASE), profile, compilation).execution());
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
        RunEngine.run(input(directory, CASE), profile, startup).execution());
    assertFalse(Files.readString(startup.resolve("logs/stderr.log")).isBlank());
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
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path profile = profile(directory, 21);
    Path launcher = directory.resolve("java-launcher");
    String classpath =
        Path.of(FailureTarget.class.getProtectionDomain().getCodeSource().getLocation().getPath())
            + File.pathSeparator
            + Path.of(
                ControlMessage.class.getProtectionDomain().getCodeSource().getLocation().getPath());
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
    Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
    var object = JsonFiles.read(profile);
    object.addProperty("java", launcher.toString());
    var limits = object.getAsJsonObject("limits");
    limits.addProperty("startupSeconds", 1);
    limits.addProperty("runSeconds", 2);
    JsonFiles.write(profile, object);
    var result = RunEngine.run(input(directory, CASE), profile, directory.resolve("injected"));
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
}
