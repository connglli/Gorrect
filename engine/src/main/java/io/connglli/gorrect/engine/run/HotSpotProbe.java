package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Resolves a mode on the selected target without assuming the engine's JDK or flag set. */
final class HotSpotProbe {
  record Result(TargetProfile profile, RunReport failure) {}

  private record Attempt(Properties facts, int exit) {}

  private HotSpotProbe() {}

  static Result resolve(
      TargetProfile profile,
      Path directory,
      Map<String, String> savedEnvironment,
      Long campaignDeadline)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.limits().startupSeconds());
    if (campaignDeadline != null) deadline = Math.min(deadline, campaignDeadline);
    try {
      var family =
          attempt(
              profile,
              directory,
              savedEnvironment,
              List.of("-XX:+" + HotSpotDriver.flag(profile.collector())),
              "collector-family",
              deadline);
      if (family.exit() != 0) {
        // The same base settings must work before attributing failure to our collector selector.
        var base =
            attempt(profile, directory, savedEnvironment, List.of(), "collector-base", deadline);
        Files.copy(
            directory.resolve("logs/collector-family.stderr"),
            directory.resolve("logs/stderr.log"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new Result(
            profile,
            RunReport.failure(
                base.exit() == 0
                    ? RunReport.Outcome.UNSUPPORTED
                    : RunReport.Outcome.STARTUP_FAILURE,
                "Collector probe failed; inspect logs/collector-family.stderr"));
      }
      var actual = family.facts();
      if (!isHotSpot(actual) || Integer.parseInt(actual.getProperty("java.feature")) < 17)
        return new Result(
            profile, RunReport.failure(RunReport.Outcome.UNSUPPORTED, "Expected HotSpot JDK 17+"));
      String mode =
          profile.collectorMode().equals("default")
              ? HotSpotDriver.mode(profile.collector(), actual)
              : profile.collectorMode();
      var resolved = profile.withCollectorMode(mode);
      var flags = HotSpotDriver.collectorArguments(resolved, actual);
      if (!flags.equals(List.of("-XX:+" + HotSpotDriver.flag(profile.collector())))) {
        var selected =
            attempt(resolved, directory, savedEnvironment, flags, "collector-mode", deadline);
        if (selected.exit() != 0)
          return new Result(
              profile,
              RunReport.failure(
                  RunReport.Outcome.UNSUPPORTED,
                  "Collector mode unavailable; inspect logs/collector-mode.stderr"));
        actual = selected.facts();
      }
      if (!HotSpotDriver.selected(profile.collector(), actual)
          || !mode.equals(HotSpotDriver.mode(profile.collector(), actual)))
        return new Result(
            profile,
            RunReport.failure(RunReport.Outcome.UNSUPPORTED, "Collector mode was not confirmed"));
      try (var out = Files.newOutputStream(directory.resolve("collector.properties"))) {
        actual.store(out, "Resolved target collector");
      }
      return new Result(resolved, null);
    } catch (java.net.SocketTimeoutException timeout) {
      return new Result(
          profile,
          RunReport.failure(
              campaignDeadline != null && System.nanoTime() >= campaignDeadline
                  ? RunReport.Outcome.CAMPAIGN_LIMIT
                  : RunReport.Outcome.TIMEOUT,
              "Collector probe deadline exceeded"));
    } catch (IllegalArgumentException unavailable) {
      return new Result(
          profile, RunReport.failure(RunReport.Outcome.UNSUPPORTED, unavailable.getMessage()));
    }
  }

  private static boolean isHotSpot(Properties properties) {
    String name = properties.getProperty("java.vm.name", "");
    return (name.contains("HotSpot") || name.contains("OpenJDK")) && !name.contains("OpenJ9");
  }

  private static Attempt attempt(
      TargetProfile profile,
      Path directory,
      Map<String, String> savedEnvironment,
      List<String> flags,
      String label,
      long deadline)
      throws IOException, InterruptedException {
    var command = new ArrayList<String>();
    command.add(profile.java());
    if (profile.collector().equals("shenandoah")
        && !profile.collectorMode().equals("default")
        && !profile.collectorMode().equals("satb")) command.add("-XX:+UnlockExperimentalVMOptions");
    if (profile.collectorMode().equals("passive")) command.add("-XX:+UnlockDiagnosticVMOptions");
    command.addAll(profile.vmArgs());
    command.addAll(flags);
    command.addAll(
        List.of(
            "-cp",
            directory.resolve("support/runtime.jar")
                + java.io.File.pathSeparator
                + directory.resolve("support/protocol.jar"),
            "io.connglli.gorrect.runtime.HotSpotFacts"));
    JsonFiles.write(directory.resolve("logs/" + label + "-command.json"), command);
    Path output = directory.resolve("logs/" + label + ".properties");
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectOutput(output.toFile())
            .redirectError(directory.resolve("logs/" + label + ".stderr").toFile());
    if (savedEnvironment == null) RunEngine.cleanEnvironment(builder, profile.environment());
    else {
      builder.environment().clear();
      builder.environment().putAll(savedEnvironment);
    }
    RunEngine.milliseconds(deadline);
    var process = builder.start();
    try {
      if (!process.waitFor(RunEngine.milliseconds(deadline), TimeUnit.MILLISECONDS))
        throw new java.net.SocketTimeoutException();
      var properties = new Properties();
      if (process.exitValue() == 0)
        try (var in = Files.newInputStream(output)) {
          properties.load(in);
        }
      return new Attempt(properties, process.exitValue());
    } finally {
      RunEngine.terminate(process);
    }
  }
}
