package io.connglli.gorrect.engine.cli;

import java.io.PrintStream;
import java.util.Set;

/** User-facing command guidance, kept separate from argument parsing and execution. */
final class Help {
  private Help() {}

  static boolean requested(String argument) {
    return argument.equals("--help") || argument.equals("-h");
  }

  static void overview(PrintStream out) {
    out.print(
        """
        Gorrect validates garbage collectors by verifying object graphs.

        Usage: gorrect <command> [arguments]

        Commands:
          fuzz    Generate and validate seeded concurrent stress workloads.
          probe   Check a target JVM and collector with a small graph test.
          run     Compile and execute a GIR workload, then verify its observations.
          verify  Recheck a saved run offline, without launching a target JVM.
          replay  Rerun saved Java classes using the recorded JVM settings.

        A GIR file describes the graph and its mutations. A JSON profile selects
        the target JVM, collector, support libraries, and execution limits.
        Select it with --profile; JVM settings belong in the profile.

        Example workflow (with a configured profile):
          gorrect fuzz --profile profiles/local/target.json --duration 1m --seed 42
          gorrect probe --profile profiles/local/target.json
          gorrect run examples/cycle.gir --profile profiles/local/target.json --out runs/example
          gorrect verify runs/example
          gorrect replay runs/example --repeat 3

        Execution commands save inputs, observations, logs, and results in a new
        directory under runs/. Use --out to choose that directory.

        Current support: HotSpot Serial GC, JDK 17+, named-root checkpoints.
        Compiled stress uses a WhiteBox client and JIT settings from the profile.
        Use 'gorrect <command> --help' for arguments, options, and examples.
        -h, --help  Show help.
        """);
  }

  static void command(String command, Set<String> options, PrintStream out) {
    out.println(
        switch (command) {
          case "fuzz" ->
              """
              Usage: gorrect fuzz --profile FILE [--workload NAME] [--workers N]
                                  [--duration TIME] [--seed N] [--out DIR]

              Generate seeded graph workloads and validate them on the target JVM.
              Workers mutate disjoint slots while retaining objects across collection,
              allocations, calls, and barriers. concurrent-jit also requires evidence
              of compiled worker frames and JIT controls configured in the profile.
              Runs one target JVM at a time; workers are threads inside that JVM.
              Saves each executable case under DIR/cases/ and campaign.json summarizes
              outcomes, unmet requirements, and the seed needed to reproduce each case.
              """;
          case "probe" ->
              """
              Usage: gorrect probe --profile FILE [--out DIR]

              Check that a target JVM, collector, and observer work together.
              Runs a built-in graph workload and exercises the profile's required
              concurrency or JIT controls. Verifies its observations and reports
              the actual VM, collector, and observer capabilities.
              """;
          case "run" ->
              """
              Usage: gorrect run CASE.gir --profile FILE [--out DIR]

              Execute a GIR graph workload on the selected JVM and verify its results.
              Generates and compiles Java, runs it in a separate target process, and
              checks graph snapshots and scalar reads against the independent oracle.
              Saves the inputs, Java source, classes, observations, logs, and results
              for later offline verification or replay.

              Arguments:
                CASE.gir  GIR file describing the initial graph and its mutations.
              """;
          case "verify" ->
              """
              Usage: gorrect verify RUN-DIR

              Recheck a saved run's graphs and traces against its saved GIR workload.
              Uses retained artifacts; the original profile file and target JVM
              installation are not needed. Completed runs get a recomputed result.json.

              Arguments:
                RUN-DIR   Existing run directory containing the saved artifacts.
              """;
          case "replay" ->
              """
              Usage: gorrect replay RUN-DIR [--repeat N] [--out DIR]

              Run a saved case again using its retained Java classes and support
              libraries, recorded profile settings, and saved launch environment.
              Each repetition starts a fresh target JVM, records new observations,
              and verifies them. Reuses the compiled classes and records changes to
              the target or support-library installations.

              Arguments:
                RUN-DIR   Existing run directory whose executable case will be reused.
              """;
          default -> throw new IllegalArgumentException("Unknown command " + command);
        });
    out.println("Options:");
    if (options.contains("--profile"))
      out.println(
          "  --profile FILE  Required. JSON profile selecting the target JVM, collector,\n"
              + "                  support libraries, required capabilities, and limits.");
    if (options.contains("--out"))
      out.println(
          "  --out DIR       New output directory; must not exist (default: runs/<run-id>).");
    if (options.contains("--repeat"))
      out.println(
          "  --repeat N      Number of fresh runs, 1..1000 (default: 1). For N > 1,\n"
              + "                  results go into DIR/1, DIR/2, ... under the output directory.");
    if (options.contains("--workload"))
      out.println("  --workload NAME concurrent (default) or concurrent-jit.");
    if (options.contains("--workers"))
      out.println("  --workers N     Threads per case, 2..16 (default: 2).");
    if (options.contains("--duration"))
      out.println(
          "  --duration TIME Campaign time budget, with ms, s, m, or h (default: 1m; max: 24h).");
    if (options.contains("--seed"))
      out.println("  --seed N        Signed 64-bit campaign seed (default: a fresh random seed).");
    out.println("  -h, --help      Show this help without running a case.");
    out.println();
    out.println("Example:");
    out.println(
        "  gorrect "
            + switch (command) {
              case "fuzz" ->
                  "fuzz --profile profiles/local/target.json --workload concurrent-jit --workers 4"
                      + " --duration 1m --seed 42";
              case "probe" -> "probe --profile profiles/local/target.json";
              case "run" ->
                  "run examples/cycle.gir --profile profiles/local/target.json --out runs/example";
              case "verify" -> "verify runs/example";
              case "replay" -> "replay runs/example --repeat 3 --out runs/replays";
              default -> throw new AssertionError(command);
            });
  }
}
