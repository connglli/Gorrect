package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.Artifacts.classpath;
import static io.connglli.gorrect.engine.run.ProcessSupport.cleanEnvironment;
import static io.connglli.gorrect.engine.run.ProcessSupport.terminate;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Compiles generated Java with the engine toolchain and bounds the compiler process. */
final class RunCompiler {
  private RunCompiler() {}

  /**
   * Compiles bundled source with the engine JDK, targeting Java 17. Returns null on success or a
   * report for compiler/startup/deadline failure, retaining command and output logs. A non-null
   * campaign deadline further caps the profile's compilation limit. Interruption propagates after
   * process cleanup.
   */
  static RunReport compile(Path directory, TargetProfile profile, Long campaignDeadline)
      throws IOException, InterruptedException {
    Path compiler = executable();
    var command =
        List.of(
            compiler.toString(),
            "--release",
            "17",
            "-Xlint:all",
            "-Werror",
            "-cp",
            classpath(directory, false),
            "-d",
            directory.resolve("generated/classes").toString(),
            directory.resolve("generated/Case.java").toString());
    JsonFiles.write(directory.resolve("logs/compiler-command.json"), command);
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectOutput(directory.resolve("logs/compiler.stdout").toFile())
            .redirectError(directory.resolve("logs/compiler.stderr").toFile());
    cleanEnvironment(builder, Map.of());
    long limit = profile.limits().compileSeconds() * 1000L;
    boolean campaignBound =
        campaignDeadline != null
            && TimeUnit.NANOSECONDS.toMillis(campaignDeadline - System.nanoTime()) < limit;
    if (campaignDeadline != null)
      limit = Math.min(limit, TimeUnit.NANOSECONDS.toMillis(campaignDeadline - System.nanoTime()));
    if (limit <= 0)
      return RunReport.failure(
          RunReport.Outcome.CAMPAIGN_LIMIT, "Campaign deadline reached before compilation");
    Process process;
    try {
      process = builder.start();
    } catch (IOException exception) {
      return RunReport.failure(
          RunReport.Outcome.COMPILATION_FAILURE, "Cannot start javac: " + exception);
    }
    try {
      if (!process.waitFor(limit, TimeUnit.MILLISECONDS))
        return RunReport.failure(
            campaignBound ? RunReport.Outcome.CAMPAIGN_LIMIT : RunReport.Outcome.TIMEOUT,
            "Compilation deadline exceeded");
      return process.exitValue() == 0
          ? null
          : RunReport.failure(
              RunReport.Outcome.COMPILATION_FAILURE, "javac exited with " + process.exitValue());
    } finally {
      terminate(process);
    }
  }

  /** Returns the engine JDK's compiler path without requiring a compiler in the target JVM. */
  static Path executable() {
    return Path.of(
        System.getProperty("java.home"),
        "bin",
        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac");
  }
}
