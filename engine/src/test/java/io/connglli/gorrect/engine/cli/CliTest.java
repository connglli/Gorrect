package io.connglli.gorrect.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"probe", "run", "verify", "replay", "fuzz"})
  void commandHelpNeedsNoInputsAndCreatesNoArtifacts(String command) {
    Path output = directory.resolve("unused-output");
    for (String help : new String[] {"--help", "-h"}) {
      var minimal = invoke(command, help);
      assertEquals(0, minimal.status());
      assertTrue(minimal.output().contains("Usage: gorrect " + command));
      assertEquals("", minimal.error());
      var withArguments =
          switch (command) {
            case "probe" ->
                invoke(command, "--profile", "missing.json", "--out", output.toString(), help);
            case "fuzz" ->
                invoke(
                    command,
                    "--profile",
                    "missing.json",
                    "--duration",
                    "1m",
                    "--out",
                    output.toString(),
                    help);
            case "run" ->
                invoke(
                    command,
                    "missing.gir",
                    "--profile",
                    "missing.json",
                    "--out",
                    output.toString(),
                    help);
            case "verify" -> invoke(command, "missing-run", help);
            default ->
                invoke(command, "missing-run", "--repeat", "3", "--out", output.toString(), help);
          };
      assertEquals(0, withArguments.status());
      assertEquals("", withArguments.error());
      assertFalse(Files.exists(output));
    }
  }

  @Test
  void errorsPointToRelevantHelp() {
    var missing = invoke("run", "missing.gir");
    assertEquals(2, missing.status());
    assertTrue(missing.error().contains("gorrect run --help"));
    var unknown = invoke("unknown", "--help");
    assertEquals(2, unknown.status());
    assertTrue(unknown.error().contains("gorrect --help"));
    assertEquals(0, invoke("-h").status());
  }

  private record Invocation(int status, String output, String error) {}

  private static Invocation invoke(String... arguments) {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    int status = Main.execute(arguments, new PrintStream(output), new PrintStream(error));
    return new Invocation(
        status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsProfileOverridesAndDuplicateOptions() {
    var bytes = new ByteArrayOutputStream();
    var stream = new PrintStream(bytes);
    for (var args :
        new String[][] {
          {"run", "case.gir", "--java", "jdk/bin/java"},
          {"fuzz", "--profile", "missing.json", "--java", "jdk/bin/java"},
          {"fuzz", "--profile", "missing.json", "--workers", "0"},
          {"fuzz", "--profile", "missing.json", "--duration", "999999999999999999h"},
          {"probe", "--profile", "one.json", "--profile", "two.json"},
          {"verify", "run-dir", "--profile", "target.json"},
          {"replay", "run-dir", "--repeat", "0"},
          {"run", "case.gir"},
          {"probe", "case.gir"},
          {"unknown"}
        }) assertEquals(2, Main.execute(args, stream, stream));
    assertTrue(bytes.toString().contains("--java"));
    assertEquals(0, Main.execute(new String[] {"--help"}, stream, stream));
  }
}
