package io.connglli.gorrect.engine.cli;

import io.connglli.gorrect.engine.fuzz.FuzzCampaign;
import io.connglli.gorrect.engine.run.RunEngine;
import io.connglli.gorrect.engine.run.RunReport;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/** Command parsing only; all commands use the engine's shared run and verification operations. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(execute(args, System.out, System.err));
  }

  public static int execute(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || Help.requested(args[0])) {
      Help.overview(out);
      return 0;
    }
    String usage = "gorrect --help";
    try {
      String command = args[0];
      var allowed =
          switch (command) {
            case "probe", "run" -> Set.of("--profile", "--out");
            case "fuzz" ->
                Set.of("--profile", "--out", "--workload", "--workers", "--duration", "--seed");
            case "verify" -> Set.<String>of();
            case "replay" -> Set.of("--repeat", "--out");
            default -> throw new IllegalArgumentException("Unknown command " + command);
          };
      usage = "gorrect " + command + " --help";
      // Help must work without loading a profile, reading inputs, or creating run directories.
      for (int i = 1; i < args.length; i++) {
        if (Help.requested(args[i])) {
          Help.command(command, allowed, out);
          return 0;
        }
      }
      var options = new HashMap<String, String>();
      String input = null;
      for (int i = 1; i < args.length; i++) {
        String option = args[i];
        if (option.startsWith("--")) {
          if (!allowed.contains(option)
              || options.containsKey(option)
              || i + 1 == args.length
              || args[i + 1].startsWith("--"))
            throw new IllegalArgumentException("Invalid or duplicate option " + option);
          options.put(option, args[++i]);
        } else {
          if (input != null) throw new IllegalArgumentException("Unexpected argument " + option);
          input = option;
        }
      }
      boolean takesInput = Set.of("run", "verify", "replay").contains(command);
      if (takesInput == (input == null))
        throw new IllegalArgumentException(
            takesInput ? "Missing input path" : command + " takes no case argument");
      if (command.equals("verify"))
        return print(RunEngine.verify(Path.of(input)), Path.of(input), out);
      Path output = Path.of(options.getOrDefault("--out", "runs/" + UUID.randomUUID()));
      if (command.equals("replay")) {
        int repeats = Integer.parseInt(options.getOrDefault("--repeat", "1"));
        if (repeats < 1 || repeats > 1000)
          throw new IllegalArgumentException("Repeat count must be 1..1000");
        if (repeats > 1) {
          Files.createDirectories(output.toAbsolutePath().getParent());
          Files.createDirectory(output);
        }
        int status = 0;
        for (int i = 1; i <= repeats; i++) {
          Path directory = repeats == 1 ? output : output.resolve(Integer.toString(i));
          int next = print(RunEngine.replay(Path.of(input), directory), directory, out);
          status = RunReport.combineExitStatus(status, next);
        }
        return status;
      }
      if (!options.containsKey("--profile"))
        throw new IllegalArgumentException("Missing --profile");
      Path profile = Path.of(options.get("--profile"));
      if (command.equals("fuzz")) {
        long seed =
            options.containsKey("--seed")
                ? Long.parseLong(options.get("--seed"))
                : new java.security.SecureRandom().nextLong();
        return FuzzCampaign.run(
            profile,
            output,
            options.getOrDefault("--workload", "concurrent"),
            Integer.parseInt(options.getOrDefault("--workers", "2")),
            FuzzCampaign.duration(options.getOrDefault("--duration", "1m")),
            seed,
            out);
      }
      var result =
          command.equals("probe")
              ? RunEngine.probe(profile, output)
              : RunEngine.run(Path.of(input), profile, output);
      int status = print(result, output, out);
      if (command.equals("probe") && Files.isRegularFile(output.resolve("target.properties"))) {
        var target = new java.util.Properties();
        try (var stream = Files.newInputStream(output.resolve("target.properties"))) {
          target.load(stream);
        }
        out.println(
            target.getProperty("java.vm.name") + " " + target.getProperty("java.vm.version"));
        out.println("Collectors: " + target.getProperty("collectors"));
        out.println("Observer capability bits: " + target.getProperty("observer.capabilities"));
      }
      return status;
    } catch (IOException | IllegalArgumentException exception) {
      err.println("gorrect: " + exception.getMessage());
      err.println("Try '" + usage + "' for usage.");
      return 2;
    }
  }

  private static int print(RunReport report, Path directory, PrintStream out) {
    out.println(report.execution() + " / " + report.verdict() + " — " + directory);
    for (String requirement : report.unmetRequirements()) out.println("Unmet: " + requirement);
    report.problems().stream().limit(5).forEach(problem -> out.println("  " + problem));
    if (report.problems().size() > 5)
      out.println("Further diagnostics: " + directory.resolve("result.json"));
    return report.exitStatus();
  }
}
