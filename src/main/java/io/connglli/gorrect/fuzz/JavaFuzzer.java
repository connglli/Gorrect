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

package io.connglli.gorrect.fuzz;

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.HprofParser;
import io.connglli.gorrect.java.JavaExecutor;
import io.connglli.gorrect.java.JavaTranslator;
import io.connglli.gorrect.java.oracle.EpsilonVM;
import io.connglli.gorrect.java.oracle.JavaOracle;
import io.connglli.gorrect.java.oracle.JavaVM;
import io.connglli.gorrect.java.oracle.SupportedGCs;
import io.connglli.gorrect.oracle.Oracle;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.utility.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.*;

public class JavaFuzzer {
  private final Logger log = Logger.getLogger(JavaFuzzer.class);

  @Builder(toBuilder = true, buildMethodName = "buildUnchecked")
  public static class Config {
    /// Strict validation mode. If true, the fuzzer will perform strict validation of the
    /// Java execution state against the oracle state. This includes dumping the runtime heap
    /// into HPROF and parsing and analyzing it. If false, the fuzzer relies on some injected
    /// loosely runtime checks into the generated code.
    private final boolean strictValidation;

    /// The maximum number of worker threads to use for fuzzing.
    private final int maxWorkers;

    /// The maximum number of iterations to perform in the fuzzing process.
    /// If set to 0, it will run indefinitely until manually stopped.
    private final int maxIterations;

    /// The directory where the output files will be stored.
    private final @NonNull String outputDir;

    /// The probability of generating humongous objects when the GC is region-based.
    private final double humongousObjProba;

    /// The config for GraphSmith, which is used to generate init states.
    private final @NonNull GraphSmith.Config graphSmithConfig;

    /// The config for ProgramSmith, which is used to generate programs.
    private final @NonNull ProgramSmith.Config programSmithConfig;

    /// The Java virtual machine (JVM) under testing
    private final @NonNull JavaVM javaVM;

    /// The options to enable a specific garbage collector (GC) for the Java VM.
    private final @NonNull SupportedGCs useGC;

    public Config check() {
      if (maxWorkers < 0) {
        throw new IllegalArgumentException(
            "maxWorkers must be non-negative, got %d".formatted(maxWorkers));
      }
      if (maxIterations < 0) {
        throw new IllegalArgumentException(
            "maxIterations must be non-negative, got %d".formatted(maxIterations));
      }
      if (outputDir.isEmpty()) {
        throw new IllegalArgumentException("outputDir must be non-empty");
      }
      if (humongousObjProba < 0.0 || humongousObjProba > 1.0) {
        throw new IllegalArgumentException(
            "humongousObjProba must be in [0.0, 1.0], got %f".formatted(humongousObjProba));
      }
      graphSmithConfig.check();
      programSmithConfig.check();
      return this;
    }
  }

  // Statistics for tracking the fuzzer's performance and results.
  public static class Stats {
    /// The start time of the fuzzing process in milliseconds.
    @Getter @Setter private long startTimeMills = System.currentTimeMillis();

    // The total number of bugs found during fuzzing.
    private final AtomicInteger totalBugs = new AtomicInteger(0);

    // The number of failed iterations, e.g., when the Java translator does not match the oracle.
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
  @NonNull private final GraphSmith.Config graphSmithConfig; // Configuration for GraphSmith
  @NonNull private final ProgramSmith.Config programSmithConfig; // Configuration for ProgramSmith
  @NonNull private final Stats stats; // Statistics for tracking fuzzer performance
  @NonNull private final AtomicBoolean stopEvent; // Flag to signal stopping the fuzzer

  public JavaFuzzer(@NonNull Config config) {
    this.config = config;
    // When the GC is region-based, we parse their humongous object sizes
    GraphSmith.Config.ConfigBuilder graphSmithConfigBuilder = config.graphSmithConfig.toBuilder();
    ProgramSmith.Config.ConfigBuilder programSmithConfigBuilder =
        config.programSmithConfig.toBuilder();
    if (config.useGC.isRegionBased) {
      Long[] humongousLimits =
          config.useGC.computeHumongousObjectMinSizes(config.javaVM.getHeapCapacity());
      graphSmithConfigBuilder.humongousProba(config.humongousObjProba);
      graphSmithConfigBuilder.humongousObjSizeBytes(humongousLimits);
      programSmithConfigBuilder.humongousProba(config.humongousObjProba);
      programSmithConfigBuilder.humongousObjSizeBytes(humongousLimits);
    } else {
      graphSmithConfigBuilder.humongousProba(0);
    }
    this.graphSmithConfig = graphSmithConfigBuilder.buildUnchecked().check();
    this.programSmithConfig = programSmithConfigBuilder.buildUnchecked().check();
    this.stats = new Stats();
    this.stopEvent = new AtomicBoolean(false);
  }

  public void start() {
    FuzzThreadFactory threadFactory = new FuzzThreadFactory();
    ExecutorService workers = Executors.newFixedThreadPool(config.maxWorkers, threadFactory);

    recoverLogging();
    log.info(
        Logger.AnsiColor.GREEN,
        "Fuzzer started: numWorkers=%d, outputDir=%s, maxIterations=%s, strictValidation=%s, gcOptions='%s' (regions=%s), javaVM=%s",
        config.maxWorkers,
        config.outputDir,
        config.maxIterations == 0 ? "infinite" : Integer.toString(config.maxIterations),
        config.strictValidation ? "enabled" : "disabled",
        String.join(" ", config.useGC.enableOptions),
        config.useGC.isRegionBased ? "true" : "false",
        config.javaVM.getHomeDirectory());

    // Capture shutdown signals to stop the fuzzer gracefully
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  recoverLogging();
                  log.warning("Shutdown signal received, stopping the fuzzer ...");
                  stopEvent.set(true);
                  log.info("Waiting for all workers to finish ...");
                  workers.close(); // Stop accepting new tasks and finish existing ones
                  log.info(Logger.AnsiColor.GREEN, "Fuzzer has been stopped");
                },
                "SHUTDOWN"));

    try {
      stats.setStartTimeMills(System.currentTimeMillis());
      for (int i = 0; config.maxWorkers == 0 || i < config.maxWorkers; i++) {
        log.info("Requesting the %d-th worker ...", i);
        workers.submit(
            new FuzzWorker(
                config.outputDir + File.separator + "fuzz_proc_%05d".formatted(i),
                Rand.getInstance().nextLong()));
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

    log.info(Logger.AnsiColor.GREEN, "Fuzzer has been shut down successfully");
  }

  private void recoverLogging() {
    Logger.disableHeadless();
    Logger.setOutput(System.out);
  }

  private static class FuzzThreadFactory implements ThreadFactory {
    private final ThreadFactory fact = Executors.defaultThreadFactory();
    private final AtomicInteger wids = new AtomicInteger(0);

    public FuzzThreadFactory() {
      Thread.currentThread().setName("MANAGER");
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread t = fact.newThread(r);
      t.setName("WORKER" + wids.getAndIncrement());
      return t;
    }
  }

  @RequiredArgsConstructor
  private class FuzzWorker implements Runnable {

    @NonNull private final String workdir;
    @NonNull private final Long seed;
    private String bugsdir = "";

    @Override
    public void run() {
      recoverLogging();

      log.info(Logger.AnsiColor.GREEN, "Worker started: seed=%d, workdir=%s", seed, workdir);

      // Create the working directory and the bugs subdirectory if they do not exist.
      bugsdir = workdir + File.separator + "found_bugs";
      try {
        Files.createDirectories(Path.of(bugsdir));
      } catch (IOException e) {
        log.error("Failed to create the working and bugs directory; see below");
        log.error(e);
        return;
      }
      // Initialize the random seed for this worker
      Rand.getInstance().setSeed(seed);

      // Start the fuzzing loop
      try {
        runFuzzLoop();
        recoverLogging();
        log.info("Worker has finished its work");
      } catch (Throwable e) {
        recoverLogging();
        log.warning("Worker has encountered an error: %s", e.getMessage());
        try {
          log.error(e);
        } catch (AssertionError ex) {
          // pass
        }
        log.warning("Worker is shutting down due to the error");
      }

      log.info("Worker has been shut down");
    }

    @RequiredArgsConstructor
    private static class OracleValidationFailure extends Exception {
      @NonNull final Boolean strict;
      @NonNull final Object failure;
    }

    public void runFuzzLoop() {
      // We create a separate directory for each generated test.
      String testNameFormat = createTestNameFormat();
      for (int i = 0; config.maxIterations == 0 || i < config.maxIterations; i++) {
        if (stopEvent.get()) {
          log.warning("Worker received stop event, stopping the fuzz loop ...");
          break;
        }

        log.info("Worker started fuzz iteration %d ...", i);
        stats.incTotalGenerations();
        // Maintain a separate directory for each tests
        String testdir = workdir + File.separator + testNameFormat.formatted(i);
        Path testPath = Path.of(testdir);
        try {
          Files.createDirectories(testPath);
          // Each test also has its own logging
          Logger.enableHeadless();
          Logger.setOutput(
              new PrintStream(new FileOutputStream(testdir + File.separator + "work.log")));
          fuzzOnce(workdir + File.separator + testNameFormat.formatted(i));
        } catch (OracleValidationFailure e) {
          stats.incTotalBugs();
          recoverLogging();
          log.info(
              Logger.AnsiColor.GREEN,
              "**GOTCHA**: Worker FOUND A GC BUG (strict=%b) at iteration %d: %s",
              e.strict,
              i,
              testdir);
          handleValidationFailure(e, testdir, "bug-report.txt");
          log.info(
              Logger.AnsiColor.BLUE,
              "\nFuzzing Statistics\n" + "------------------\n" + stats.formatAsText());
        } catch (Throwable e) {
          // FIXME: We also treat execution failures and timeout as failed iterations for now.
          stats.incFailedGenerations();
          recoverLogging();
          log.warning(
              "Worker encountered errors during fuzzing at iteration %d: %s", i, e.getMessage());
          try {
            log.error(e);
          } catch (AssertionError ex) {
            // pass
          }
          continue;
        }
        try {
          recoverLogging();
          io.connglli.gorrect.utility.Files.deleteFileTreeIfExists(testPath);
        } catch (Exception e) {
          log.warning(
              "Worker failed to remove the work directory of iteration %d: %s", i, e.getMessage());
        }
      }
    }

    private void fuzzOnce(String testdir) throws OracleValidationFailure, IOException {
      JavaVM jvm = config.javaVM.clone();
      IdSmith objectIdSmith = new IdSmith("o");
      IdSmith referenceIdSmith = new IdSmith("l");

      log.info("Generating the initial state ...");
      GraphSmith graphSmith = new GraphSmith(graphSmithConfig, objectIdSmith, referenceIdSmith);
      GGraph initState = graphSmith.craft(jvm);
      log.info("Succeeded, the initial state is:");
      log.info(initState.formatToText());

      log.info("Generating the GIR program ...");
      ProgramSmith programSmith =
          new ProgramSmith(programSmithConfig, objectIdSmith, referenceIdSmith);
      GProgram program = programSmith.craft(jvm, initState);
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
      OracleInterpreter interpreter = new OracleInterpreter(unlimitedJvm, program);
      interpreter.interpret();

      GGraph oracleState = interpreter.getState();
      log.info("Succeeded. The interpreter stopped with the following final state:");
      log.info(oracleState.formatToText());

      // Translate the Gorrect IR program to Java code and obtain the interpreter state.
      log.info("Generating the Java program by translating the GIR program ...");
      JavaTranslator translator = new JavaTranslator(program);
      translator.translate();

      GGraph transState = translator.getState();
      log.info("Succeeded. The translator stopped with the following final state:");
      log.info(transState.formatToText());

      if (oracleState.equals(transState)) {
        log.info("Good: The state of the Java translator matches the Oracle interpreter state.");
      } else {
        log.error("BAD: There must be a bug in the Java translator due to state mismatch!");
      }

      // Export the Java program to a file and execute it
      String javaFilePath = translator.exportToJava(testdir, oracleState, config.strictValidation);
      String javaFileFullPath = testdir + File.separator + javaFilePath;
      log.info("The Java program has been exported to %s", javaFileFullPath);

      // Execute the generated Java program after execution
      log.info(
          "Execute the generated Java program %s...",
          config.strictValidation ? "and dump the heap state " : "");
      String dumpFilePath = config.strictValidation ? javaFilePath + ".hprof" : null;
      JavaExecutor executor = new JavaExecutor(jvm);
      JavaExecutor.Result execResult =
          executor.execute(javaFilePath, dumpFilePath, testdir, config.useGC.enableOptions);

      // The embedded oracle validation reports us a bug
      if (execResult.exitCode() == JavaExecutor.ExitCode.EXECUTION_ASSERTION) {
        throw new OracleValidationFailure(false, execResult);
      }

      // Our execution encountered unexpected errors
      if (execResult.exitCode() != JavaExecutor.ExitCode.SUCCESS) {
        StringBuilder sb = new StringBuilder();
        sb.append(execResult.exitCode().name()).append(": ").append(execResult.errorMessage());
        if (execResult.exception() != null) {
          sb.append(". Caused by Exception: ").append(execResult.exception().getMessage());
        }
        throw new RuntimeException(sb.toString());
      }

      // Our execution succeeded, but we do not need strict validation
      if (!config.strictValidation) {
        return;
      }

      // Dump and analyze the heap to validate the result for a strict validation
      String hprofFileFullPath = testdir + File.separator + dumpFilePath;
      GGraph execState = HprofParser.parse(hprofFileFullPath, translator.getUUID());

      log.info("The extracted heap state:");
      log.info(execState.formatToText());

      log.info("Validating the heap state strictly ...");
      // Validate the Java execution state against the oracle interpreter state.
      JavaOracle validator = new JavaOracle(oracleState);
      JavaOracle.ValidationResult valiResult = validator.validate(execState);
      if (!valiResult.isValid()) {
        log.info(
            Logger.AnsiColor.GREEN,
            "GC BUG FOUND: The Java execution state does NOT match the oracle!");
        throw new OracleValidationFailure(true, valiResult);
      }
    }

    private void handleValidationFailure(
        OracleValidationFailure failure, String testdir, String reportFile) {
      Path testPath = Path.of(testdir);

      String newTestDir = bugsdir + File.separator + testPath.getFileName();
      try {
        Files.move(testPath, Path.of(newTestDir));
      } catch (IOException e) {
        log.warning("Worker failed to move the bug's directory from %s to %s", testdir, newTestDir);
        return;
      }

      // Generate a bug report file in the new directory.
      try (PrintStream ps =
          new PrintStream(new FileOutputStream(newTestDir + File.separator + reportFile))) {
        if (failure.strict) {
          generateBugReport(ps, (Oracle.ValidationResult) failure.failure);
        } else {
          generateBugReport(ps, (JavaExecutor.Result) failure.failure);
        }
      } catch (FileNotFoundException e) {
        log.warning("Worker failed to create the bug report file: %s", e.getMessage());
      }
    }

    private void generateBugReport(PrintStream ps, JavaExecutor.Result result) {
      ps.println("FAILURE: " + result.exitCode().name());
      if (result.errorMessage() != null && !result.errorMessage().isEmpty()) {
        ps.println("Message: " + result.errorMessage());
      }
    }

    private void generateBugReport(PrintStream ps, Oracle.ValidationResult result) {
      ps.println("FAILURE: " + result.getFailureMessage());
      ps.println(result.getSummary());
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
