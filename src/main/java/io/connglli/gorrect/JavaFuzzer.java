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
import io.connglli.gorrect.utility.Logger;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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

  public static class Stats {
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
  }

  @NonNull private final Config config; // Configuration for the fuzzer
  @NonNull private final ExecutorService dispatcher; // Dispatcher for managing validation results
  @NonNull private final Stats stats; // Statistics for tracking fuzzer performance

  public JavaFuzzer(@NonNull Config config) {
    this.config = config;
    this.dispatcher = Executors.newSingleThreadExecutor();
    this.stats = new Stats();
  }

  public void start() {
    String workDirNameFormat = createWorkdirNameFormat();

    try (ExecutorService executor = Executors.newWorkStealingPool(config.maxWorkers)) {
      for (int i = 0; config.maxIterations == 0 || i < config.maxIterations; i++) {
        executor.submit(new FuzzWorker(workDirNameFormat.formatted(i)));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    dispatcher.shutdown();
  }

  private String createWorkdirNameFormat() {
    String date =
        LocalDateTime.now()
            .format(new DateTimeFormatterBuilder().appendPattern("yyMMddHHmm").toFormatter());
    int maxIterationLength =
        config.maxIterations == 0 ? 8 : String.valueOf(config.maxIterations).length();
    // Format for work directory names, e.g., "G-202501011234-00000001"
    return "G-" + date + "-%0" + maxIterationLength + "d";
  }

  @RequiredArgsConstructor
  private class FuzzWorker implements Runnable {

    @NonNull private final String workdirName;

    @Override
    public void run() {
      try {
        // Redirect our logs into the working directory.
        Logger.setOutput(
            new PrintStream(
                new FileOutputStream(config.outputDir + File.separator + workdirName + ".log")));
        fuzzOnce();
      } catch (Exception e) {
        stats.incFailedGenerations();
        System.err.println("Error during fuzzing: " + e.getMessage());
      }
      stats.incTotalGenerations();
    }

    public void fuzzOnce() throws Exception {
      String workdir = config.outputDir + File.separator + workdirName;

      GraphSmith graphSmith = new GraphSmith(config.graphSmithConfigBuilder.build());
      GGraph initState = graphSmith.craft();
      System.out.println("Initial State");
      System.out.println("-------------");
      System.out.println(initState.formatToText());

      ProgramSmith programSmith =
          new ProgramSmith(config.programSmithConfigBuilder.initState(initState).build());

      GProgram program = programSmith.craft();
      System.out.println("Gorrect IR Program");
      System.out.println("------------------");
      System.out.println(program.toString());

      ProgramSmith.Stats progamGenStats = programSmith.getStats();
      System.out.println("Smith Stats");
      System.out.println("-----------");
      System.out.println(progamGenStats.formatToText());

      // We run the oracle interpreter with an unlimited heap size to obtain the oracle state.
      JavaVM unlimitedJvm = new EpsilonVM(Long.MAX_VALUE);
      OracleInterpreter oracle = new OracleInterpreter(unlimitedJvm, program);
      oracle.interpret();
      GGraph oracleState = oracle.getState();
      System.out.println("Oracle Interpreter State");
      System.out.println("------------------------");
      System.out.println(oracleState.formatToText());

      // Translate the Gorrect IR program to Java code and obtain the interpreter state.
      JavaInterpreter java = new JavaInterpreter(program);
      java.interpret();
      GGraph interpState = java.getState();
      System.out.println("Java Interpreter State");
      System.out.println("----------------------");
      System.out.println(interpState.formatToText());

      System.out.println("Interpreter State Comparison");
      System.out.println("----------------------------");
      if (oracleState.equals(interpState)) {
        System.out.println("Equals\n");
      } else {
        System.out.println("Not equals; there must be a bug in the Java interpreter!\n");
        throw new IllegalStateException(
            "The Java interpreter state does not match the oracle state.");
      }

      // Export the Java program to a file and execute it
      System.out.println("Java Program");
      System.out.println("------------");
      String javaFilePath = java.exportToJava(workdir);
      String javaFileFullPath = workdir + File.separator + javaFilePath;
      System.out.println(Files.readString(Path.of(javaFileFullPath)));

      // Validate if the Java program after execution matches the oracle state.
      System.out.println("Java Execution State");
      System.out.println("--------------------");

      String hprofFilePath =
          java.executeGeneratedJavaFile(javaFilePath, config.javaVM, workdir, config.useGcOptions);
      String hprofFileFullPath = workdir + File.separator + hprofFilePath;
      GGraph execState = HprofParser.parse(hprofFileFullPath);
      System.out.println(execState.formatToText());

      System.out.println("Oracle Check");
      System.out.println("------------");
      // Validate the Java execution state against the oracle interpreter state.
      JavaOracle validator = new JavaOracle(oracleState);
      JavaOracle.ValidationResult result = validator.validate(execState);
      if (result.isValid()) {
        System.out.println(
            "SUCCESS: The Java execution state matches the oracle interpreter state.");
        dispatcher.submit(new GoodResult(result, workdirName));
      } else {
        dispatcher.submit(new BugReport(result, workdirName));
      }
    }
  }

  @RequiredArgsConstructor
  private class BugReport implements Runnable {

    @NonNull Oracle.ValidationResult bug;
    @NonNull String bugDirName;

    @Override
    public void run() {
      stats.incTotalBugs();
      String oldDirPath = config.outputDir + File.separator + bugDirName;
      String newDirPath = config.outputDir + File.separator + "bug-" + bugDirName;
      try {
        // Mark the directory by prepending a "bug-" prefix to its name.
        Files.move(Path.of(oldDirPath), Path.of(newDirPath));
      } catch (IOException e) {
        System.out.println("Failed to rename the bug's directory: " + e.getMessage());
        return;
      }
      // Generate a bug report file in the new directory.
      try (PrintStream ps =
          new PrintStream(new FileOutputStream(newDirPath + File.separator + "bug-report.txt"))) {
        ps.println("FAILURE: " + bug.getFailureMessage());
        ps.println(
            "Object Violations: "
                + bug.getObjectViolations().entrySet().stream()
                    .map(s -> " - %s: %s".formatted(s.getKey(), s.getValue()))
                    .collect(Collectors.joining("\n")));
        ps.println(
            "Reference Violations: "
                + bug.getReferenceViolations().entrySet().stream()
                    .map(
                        s ->
                            " - (%s -> %s): %s"
                                .formatted(s.getKey().a(), s.getKey().b(), s.getValue()))
                    .collect(Collectors.joining("\n")));
        ps.println("--- Oracle " + "-".repeat(50));
        ps.println(bug.getOracle().formatToText());
        ps.println("--- Actual " + "-".repeat(50));
        ps.println(bug.getObtained().formatToText());
      } catch (FileNotFoundException e) {
        System.out.println("Failed to create bug report file: " + e.getMessage());
      }
    }
  }

  @RequiredArgsConstructor
  private class GoodResult implements Runnable {

    @NonNull Oracle.ValidationResult result;
    @NonNull String resultDirName;

    @Override
    public void run() {
      // Remove the work directory since it is a good result.
      try {
        io.connglli.gorrect.utility.Files.deleteFileTreeIfExists(
            Path.of(config.outputDir, resultDirName));
      } catch (Exception e) {
        System.err.println("Failed to remove work directory: " + e.getMessage());
      }
    }
  }
}
