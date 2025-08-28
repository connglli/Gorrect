/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect;

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.HprofParser;
import io.connglli.gorrect.java.JavaInterpreter;
import io.connglli.gorrect.java.oracle.EpsilonVM;
import io.connglli.gorrect.java.oracle.JavaOracle;
import io.connglli.gorrect.java.oracle.JavaVM;
import io.connglli.gorrect.oracle.Oracle;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.smith.GraphSmith;
import io.connglli.gorrect.smith.IdSmith;
import io.connglli.gorrect.smith.ProgramSmith;
import io.connglli.gorrect.utility.Formats;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.Rand;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.*;

public class JavaFuzzer {
  private static final Logger log = Logger.getLogger(JavaFuzzer.class);

  @Builder
  public static class Config {
    /// The maximum number of worker threads to use for fuzzing.
    public int maxWorkers;

    /// The directory where the output files will be stored.
    public String outputDir;

    /// The maximum number of iterations to perform in the fuzzing process.
    /// If set to 0, it will run indefinitely until manually stopped.
    public final int maxIterations;

    /// The IdSmith instance for generating object IDs.
    public final IdSmith objectIdSmith;

    /// The IdSmith instance for generating reference IDs.
    public final IdSmith referenceIdSmith;

    /// The config builder for GraphSmith, which is used to generate init states.
    public final GraphSmith.Config.ConfigBuilder graphSmithConfigBuilder;

    /// The config builder for ProgramSmith, which is used to generate programs.
    public final ProgramSmith.Config.ConfigBuilder programSmithConfigBuilder;

    /// The Java virtual machine (JVM) under testing
    public final JavaVM javaVM;

    /// The options to enable a specific garbage collector (GC) for the Java VM.
    public String useGcOptions;
  }

  // Statistics for tracking the fuzzer's performance and results.
  public static class Stats {
    /// The start time of the fuzzing process in milliseconds.
    @Getter @Setter private long startTimeMills = System.currentTimeMillis();

    // The total number of bugs found during fuzzing.
    private final AtomicInteger totalBugs = new AtomicInteger(0);

    // The number of failed iterations, e.g., when the Java interpreter does not match the oracle.
    private final AtomicInteger failedIterations = new AtomicInteger(0);
    // The total number of iterations attempted during fuzzing.
    private final AtomicInteger totalIterations = new AtomicInteger(0);

    public int getTotalBugs() {
      return totalBugs.get();
    }

    public int incAndGetTotalBugs() {
      return totalBugs.incrementAndGet();
    }

    public void incTotalBugs() {
      incAndGetTotalBugs();
    }

    public int getFailedIterations() {
      return failedIterations.get();
    }

    public int incAndGetFailedGenerations() {
      return failedIterations.incrementAndGet();
    }

    public void incFailedGenerations() {
      incAndGetFailedGenerations();
    }

    public int getTotalGenerations() {
      return totalIterations.get();
    }

    public int incAndGetTotalGenerations() {
      return totalIterations.incrementAndGet();
    }

    public void incTotalGenerations() {
      incAndGetTotalGenerations();
    }

    public String formatAsText() {
      int totalBugs = getTotalBugs();
      int totalIter = getTotalGenerations();
      int validIter = totalIter - getFailedIterations();
      double validRate = totalIter == 0 ? 0.0 : (double) validIter * 100.0 / totalIter;
      long elapsedSeconds = (System.currentTimeMillis() - startTimeMills) / 1000;
      double throughput = elapsedSeconds == 0 ? 0.0 : (double) totalIter / elapsedSeconds;

      return """
          Total Bugs Found   : %d
          Generated Programs : %d
          Valid     Programs : %d (%.2f%%)
          Fuzzing Time       : %s
          Fuzzing Throughput : %.2f it/s, %.2f it/m, %.2f it/h
          """
          .formatted(
              totalBugs,
              totalIter,
              validIter,
              validRate,
              Formats.toHoursMinutesSecondsStr(elapsedSeconds),
              throughput,
              throughput * 60,
              throughput * 3600);
    }
  }

  @NonNull private final Config config; // Configuration for the fuzzer
  @NonNull private final Stats stats; // Statistics for tracking fuzzer performance

  public JavaFuzzer(@NonNull Config config) {
    this.config = config;
    this.stats = new Stats();
  }

  public void start() {
    log.info(
        "Fuzzer started: numWorkers=%d, outputDir=%s, maxIterations=%s, gcOptions='%s'",
        config.maxWorkers,
        config.outputDir,
        config.maxIterations == 0 ? "infinite" : Integer.toString(config.maxIterations),
        config.useGcOptions);
    recoverLogging();
    ExecutorService workers = Executors.newFixedThreadPool(config.maxWorkers);
    try {
      stats.setStartTimeMills(System.currentTimeMillis());
      for (int i = 0; config.maxWorkers == 0 || i < config.maxWorkers; i++) {
        log.info("Requesting the %d-th worker ...", i);
        workers.submit(
            new FuzzWorker(i, config.outputDir + File.separator + "fuzz_proc_%05d".formatted(i)));
      }
      log.info("Waiting for all workers to finish ...");
      workers.close();
      log.info("Shutting down the fuzzer ...");
    } catch (Exception e) {
      log.info(Logger.AnsiColor.RED, "The fuzzer has encountered an unexpected error; see below");
      try {
        log.error(e);
      } catch (AssertionError ex) {
        // pass
      }
      log.info("Shutting down the fuzzer ...");
      workers.close();
    }

    log.info("Fuzzer has been shut down successfully");
  }

  private void recoverLogging() {
    Logger.disableHeadless();
    Logger.setOutput(System.out);
  }

  @RequiredArgsConstructor
  private class FuzzWorker implements Runnable {

    private final int workerId;
    @NonNull private final String workdir;
    private String bugsdir = "";

    @Override
    public void run() {
      recoverLogging();

      log.info("Worker %d started, working on directory %s", workerId, workdir);

      // Create the working directory and the bugs subdirectory if they do not exist.
      bugsdir = workdir + File.separator + "found_bugs";
      try {
        Files.createDirectories(Path.of(bugsdir));
      } catch (IOException e) {
        log.error("Failed to create the working and bugs directory; see below");
        log.error(e);
        return;
      }

      // Start the fuzzing loop
      try {
        runFuzzLoop();
        recoverLogging();
        log.info("Worker %d has finished its work", workerId);
      } catch (Throwable e) {
        recoverLogging();
        log.warning("Worker %d has encountered an error: %s", workerId, e.getMessage());
        try {
          log.error(e);
        } catch (AssertionError ex) {
          // pass
        }
        log.warning("Worker %d is shutting down due to the error", workerId);
      }

      log.info("Worker %d has been shut down", workerId);
    }

    public void runFuzzLoop() {
      // We create a separate directory for each generated test.
      String testNameFormat = createTestNameFormat();
      for (int i = 0; config.maxIterations == 0 || i < config.maxIterations; i++) {
        recoverLogging();
        log.info("Worker %d starting iteration %d ...", workerId, i);
        stats.incTotalGenerations();
        // Maintain a separate directory for each tests
        String testdir = workdir + File.separator + testNameFormat.formatted(i);
        Oracle.ValidationResult result;
        try {
          Files.createDirectories(Path.of(testdir));
          // Each test also has its own logging
          Logger.enableHeadless();
          Logger.setOutput(
              new PrintStream(new FileOutputStream(testdir + File.separator + "work.log")));
          result = fuzzOnce(workdir + File.separator + testNameFormat.formatted(i));
        } catch (Throwable e) {
          stats.incFailedGenerations();
          log.warning("Error occurred during fuzzing: " + e.getMessage());
          try {
            log.error(e);
          } catch (AssertionError ex) {
            // pass
          }
          continue;
        }
        recoverLogging();
        handleResult(result, testdir);
      }
    }

    private Oracle.ValidationResult fuzzOnce(String testdir) throws Throwable {
      JavaVM jvm = config.javaVM.clone();

      log.info("Generating the initial state ...");
      GraphSmith graphSmith = new GraphSmith(config.graphSmithConfigBuilder.build());
      GGraph initState = graphSmith.craft(jvm);
      log.info("Succeeded, the initial state is:");
      log.info(initState.formatToText());

      log.info("Generating the GIR program ...");
      ProgramSmith programSmith =
          new ProgramSmith(config.programSmithConfigBuilder.initState(initState).build());
      GProgram program = programSmith.craft(jvm);
      String progFullPath = testdir + File.separator + "program.gir";
      try (PrintStream ps = new PrintStream(new FileOutputStream(progFullPath))) {
        String progStr = program.toString();
        log.info("Succeeded, exported to %s:", progFullPath);
        log.info(progStr);
        ps.println(progStr);
      }

      log.info("Generation statistics for the GIR program:");
      log.info(programSmith.getStats().formatToText());

      // We run the oracle interpreter with an unlimited heap size to obtain the oracle state.
      log.info("Interpret the GIR using the Oracle Interpreter ...");
      JavaVM unlimitedJvm = new EpsilonVM(Long.MAX_VALUE);
      OracleInterpreter oracle = new OracleInterpreter(unlimitedJvm, program);
      oracle.interpret();

      GGraph oracleState = oracle.getState();
      log.info("Succeeded. The interpreter stopped with the following final state:");
      log.info(oracleState.formatToText());

      // Translate the Gorrect IR program to Java code and obtain the interpreter state.
      log.info("Generating the Java program by interpreting the GIR program ...");
      JavaInterpreter java = new JavaInterpreter(program);
      java.interpret();

      GGraph interpState = java.getState();
      log.info("Succeeded. The interpreter stopped with the following final state:");
      log.info(interpState.formatToText());

      if (oracleState.equals(interpState)) {
        log.info("Good: The state of the Java interpreter matches the Oracle interpreter state.");
      } else {
        log.error("BAD: There must be a bug in the Java interpreter due to state mismatch!");
      }

      // Export the Java program to a file and execute it
      String javaFilePath = java.exportToJava(testdir);
      String javaFileFullPath = testdir + File.separator + javaFilePath;
      log.info("The Java program has been exported to %s", javaFileFullPath);

      // Validate if the Java program after execution matches the oracle state.
      log.info("Extracting the final state of the Java program after execution");
      String hprofFilePath =
          java.executeGeneratedJavaFile(javaFilePath, jvm, testdir, config.useGcOptions);
      String hprofFileFullPath = testdir + File.separator + hprofFilePath;
      GGraph execState = HprofParser.parse(hprofFileFullPath);

      log.info("The extracted state:");
      log.info(execState.formatToText());

      log.info("Validating the final state ...");
      // Validate the Java execution state against the oracle interpreter state.
      JavaOracle validator = new JavaOracle(oracleState);
      JavaOracle.ValidationResult result = validator.validate(execState);
      if (result.isValid() && Rand.getInstance().nextFloat() < 0.5) {
        log.info("Passed: The Java execution state matches the oracle interpreter state.");
      } else {
        log.info(
            Logger.AnsiColor.GREEN,
            "GC BUG FOUND: The Java execution state does NOT match the oracle!");
      }
      return result;
    }

    private void handleResult(Oracle.ValidationResult result, String testdir) {
      Path testPath = Path.of(testdir);

      // Good result, remove the work directory to save space.
      if (result.isValid()) {
        try {
          io.connglli.gorrect.utility.Files.deleteFileTreeIfExists(testPath);
        } catch (Exception e) {
          log.warning("Worker %d failed to remove work directory: %s", workerId, e.getMessage());
        }
        return;
      }

      // We found a bug, save the test directory
      log.info(Logger.AnsiColor.GREEN, "GC BUG FOUND at: %s", testdir);
      stats.incTotalBugs();

      String newTestDir = bugsdir + File.separator + testPath.getFileName();
      try {
        Files.move(testPath, Path.of(newTestDir));
      } catch (IOException e) {
        log.warning(
            "Worker %d failed to move the bug's directory from %s to %s", testdir, newTestDir);
        return;
      }

      // Generate a bug report file in the new directory.
      try (PrintStream ps =
          new PrintStream(new FileOutputStream(newTestDir + File.separator + "bug-report.txt"))) {
        ps.println("FAILURE: " + result.getFailureMessage());
        ps.print("Object Violations:");
        if (result.getObjectViolations().isEmpty()) {
          ps.println(" Not Found");
        } else {
          ps.println();
          ps.println(
              result.getObjectViolations().entrySet().stream()
                  .map(s -> "- %s: %s".formatted(s.getKey(), s.getValue()))
                  .collect(Collectors.joining("\n")));
        }
        ps.println("Reference Violations:");
        if (result.getReferenceViolations().isEmpty()) {
          ps.println(" Not Found");
        } else {
          ps.println();
          ps.println(
              result.getReferenceViolations().entrySet().stream()
                  .map(
                      s ->
                          "- (%s -> %s): %s"
                              .formatted(s.getKey().a(), s.getKey().b(), s.getValue()))
                  .collect(Collectors.joining("\n")));
        }
        ps.println();
        ps.println();
        ps.println("--- Oracle " + "-".repeat(50));
        ps.println(result.getOracle().formatToText());
        ps.println();
        ps.println();
        ps.println("--- Actual " + "-".repeat(50));
        ps.println(result.getObtained().formatToText());
      } catch (FileNotFoundException e) {
        log.warning("Worker %d failed to create bug report file: %s", workerId, e.getMessage());
      }
      log.info(
          Logger.AnsiColor.BLUE,
          "\nFuzzing Statistics\n" + "------------------\n" + stats.formatAsText());
    }

    private String createTestNameFormat() {
      String date =
          LocalDateTime.now()
              .format(new DateTimeFormatterBuilder().appendPattern("yyMMddHHmm").toFormatter());
      int maxIterationLength =
          config.maxIterations == 0 ? 8 : String.valueOf(config.maxIterations).length();
      // Format for work directory names, e.g., "G-202501011234-00000001"
      return "gctest_" + date + "_%0" + maxIterationLength + "d";
    }
  }
}
