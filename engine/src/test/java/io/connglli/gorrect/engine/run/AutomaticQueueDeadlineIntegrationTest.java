package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("observer")
class AutomaticQueueDeadlineIntegrationTest {
  @TempDir Path directory;

  @Test
  void actualSecondLockWaitUsesProcessDeadlineAndNeverProducesACompletedCut() throws Exception {
    var facts = new Properties();
    try (var input =
        Files.newInputStream(
            Path.of(System.getProperty("gorrect.automaticQueue.artifacts"))
                .resolve("automatic-facts.properties"))) {
      facts.load(input);
    }
    String library = System.getProperty("gorrect.automaticQueue.library");
    var command =
        List.of(
            Path.of(facts.getProperty("java.home"), "bin/java").toString(),
            "-agentpath:" + library,
            "-Xint",
            "-Xmx128m",
            "-Xshare:off",
            "-XX:+UseSerialGC",
            "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
            "-cp",
            System.getProperty("gorrect.automaticQueue.classpath"),
            "Case",
            library,
            directory.toString(),
            "automatic-blocked");
    var builder =
        new ProcessBuilder(command)
            .redirectOutput(directory.resolve("stdout").toFile())
            .redirectError(directory.resolve("stderr").toFile());
    ProcessSupport.cleanEnvironment(builder, Map.of(), true);
    var process = builder.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Files.exists(directory.resolve("waiting.txt"))
          && process.isAlive()
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(
          Files.exists(directory.resolve("waiting.txt")),
          () -> "fixture failed before the real acquisition wait: " + process);
      assertFalse(
          process.waitFor(250, TimeUnit.MILLISECONDS), "blocked acquisition became completion");
      assertFalse(
          Files.exists(directory.resolve("1.gqlock")), "wait fabricated a consistency result");
      assertFalse(
          Files.exists(directory.resolve("1.gsnap")),
          "walk occurred before all locks were acquired");
    } finally {
      ProcessSupport.terminate(process);
    }
    assertFalse(process.isAlive());
  }
}
