package io.connglli.gorrect.engine.fuzz;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirFormatter;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.run.RunEngine;
import io.connglli.gorrect.engine.run.RunReport;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/** A bounded single-job campaign. Each retained case uses the ordinary run and replay format. */
public final class FuzzCampaign {
  public record Case(long seed, String directory, RunReport result, boolean durationLimit) {}

  public record Summary(
      int version,
      String generator,
      long seed,
      String workload,
      int workers,
      long durationMillis,
      String stopped,
      java.util.List<Case> cases) {}

  private FuzzCampaign() {}

  public static int run(
      Path profileFile,
      Path output,
      String workload,
      int workers,
      Duration duration,
      long seed,
      PrintStream out)
      throws IOException {
    if (!WorkloadGenerator.WORKLOADS.contains(workload)
        || workers < 2
        || workers > 16
        || duration.isNegative()
        || duration.isZero()
        || duration.compareTo(Duration.ofHours(24)) > 0)
      throw new IllegalArgumentException("Invalid fuzz workload, worker count, or duration");
    var raw = JsonFiles.read(profileFile);
    var profile = TargetProfile.parse(raw, profileFile.toAbsolutePath().getParent());
    int rounds = profile.jit() == null ? 2 : profile.jit().warmupRounds() + 1;
    WorkloadGenerator.validate(workload, workers, rounds);
    output = output.toAbsolutePath().normalize();
    Files.createDirectories(output.getParent());
    Files.createDirectory(output);
    Files.createDirectory(output.resolve("inputs"));
    Files.createDirectory(output.resolve("cases"));
    // Freeze resolved paths and settings so edits to the original profile cannot change the
    // campaign.
    JsonFiles.write(output.resolve("profile.json"), profile);
    JsonFiles.write(output.resolve("input-profile.json"), raw);
    var cases = new ArrayList<Case>();
    var random = new Random(seed);
    long deadline = System.nanoTime() + duration.toNanos();
    String stopped = "duration";
    int status = 0;
    JsonFiles.write(
        output.resolve("campaign.json"),
        new Summary(
            1,
            WorkloadGenerator.VERSION,
            seed,
            workload,
            workers,
            duration.toMillis(),
            "running",
            java.util.List.of()));
    while (cases.isEmpty() || System.nanoTime() < deadline) {
      if (Thread.currentThread().isInterrupted()) {
        stopped = "interrupted";
        status = 130;
        break;
      }
      long caseSeed = random.nextLong();
      int index = cases.size() + 1;
      var program = WorkloadGenerator.generate(caseSeed, workload, workers, rounds);
      Path input = output.resolve("inputs/" + index + ".gir");
      Files.writeString(input, GirFormatter.format(program));
      Path directory = output.resolve("cases/" + index);
      var provenance =
          Map.<String, Object>of(
              "generator",
              WorkloadGenerator.VERSION,
              "seed",
              caseSeed,
              "campaignSeed",
              seed,
              "index",
              index,
              "workload",
              workload,
              "workers",
              workers);
      var result =
          RunEngine.runBounded(
              input,
              output.resolve("profile.json"),
              directory,
              Duration.ofNanos(Math.max(0, deadline - System.nanoTime())),
              provenance);
      boolean durationLimit = result.execution() == RunReport.Outcome.CAMPAIGN_LIMIT;
      cases.add(new Case(caseSeed, "cases/" + index, result, durationLimit));
      out.println(
          "Case "
              + index
              + ", seed "
              + caseSeed
              + ": "
              + result.execution()
              + " / "
              + result.verdict()
              + " — "
              + directory);
      if (!result.unmetRequirements().isEmpty())
        out.println("  Unmet requirements: " + result.unmetRequirements());
      JsonFiles.write(
          output.resolve("campaign.json"),
          new Summary(
              1,
              WorkloadGenerator.VERSION,
              seed,
              workload,
              workers,
              duration.toMillis(),
              "running",
              java.util.List.copyOf(cases)));
      if (!durationLimit) status = RunReport.combineExitStatus(status, result.exitStatus());
      if (result.execution() == RunReport.Outcome.UNSUPPORTED
          || result.execution() == RunReport.Outcome.INVALID_WORKLOAD
          || result.execution() == RunReport.Outcome.CHECKING_LIMIT) {
        stopped = "unsupported-or-invalid-case";
        break;
      }
      if (result.verdict() == ExactVerifier.Verdict.VIOLATED
          || result.execution() == RunReport.Outcome.CRASH) {
        stopped = "finding";
        break;
      }
      if (durationLimit) break;
      if (result.execution() != RunReport.Outcome.COMPLETED) {
        stopped = "execution-failure";
        break;
      }
    }
    JsonFiles.write(
        output.resolve("campaign.json"),
        new Summary(
            1,
            WorkloadGenerator.VERSION,
            seed,
            workload,
            workers,
            duration.toMillis(),
            stopped,
            java.util.List.copyOf(cases)));
    out.println("Campaign " + stopped + "; " + cases.size() + " case(s); artifacts: " + output);
    if (status == 0
        && cases.stream()
            .noneMatch(value -> value.result().execution() == RunReport.Outcome.COMPLETED))
      status = 5;
    return status;
  }

  public static Duration duration(String value) {
    var match = java.util.regex.Pattern.compile("([0-9]+)(ms|s|m|h)").matcher(value);
    if (!match.matches())
      throw new IllegalArgumentException("Duration needs a unit: ms, s, m, or h");
    long amount = Long.parseLong(match.group(1));
    try {
      return switch (match.group(2)) {
        case "ms" -> Duration.ofMillis(amount);
        case "s" -> Duration.ofSeconds(amount);
        case "m" -> Duration.ofMinutes(amount);
        default -> Duration.ofHours(amount);
      };
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("Duration is outside the supported range", failure);
    }
  }
}
