/*
 * MIT License
 *
 * Copyright (c) 2025-2025 Cong Li (cong.li@inf.ethz.ch)
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

import io.connglli.gorrect.fuzz.GraphSmith;
import io.connglli.gorrect.fuzz.IdSmith;
import io.connglli.gorrect.fuzz.JavaFuzzer;
import io.connglli.gorrect.fuzz.ProgramSmith;
import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.HprofParser;
import io.connglli.gorrect.java.JavaExecutor;
import io.connglli.gorrect.java.JavaTranslator;
import io.connglli.gorrect.java.oracle.*;
import io.connglli.gorrect.oracle.NoOpVM;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.MemorySize;
import io.connglli.gorrect.utility.Rand;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Collectors;
import lombok.NonNull;
import picocli.CommandLine;

@CommandLine.Command(
    name = "gorrect",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    subcommands = {
      CommandLine.HelpCommand.class,
      GraphSmithCommand.class,
      ProgramSmithCommand.class,
      FuzzCommand.class,
      StateCompareCommand.class,
    },
    description = "Validate the correctness of garbage collectors based on object graph semantics.",
    version = "Gorrect 0.1.0",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
public class Gorrect {

  @CommandLine.Option(
      names = {"--seed"},
      paramLabel = "seed",
      description = "Random seed for the generator (if not specified, the current time is used)",
      showDefaultValue = CommandLine.Help.Visibility.NEVER)
  protected int optSeed = (int) System.currentTimeMillis();

  @CommandLine.Option(
      names = {"--verbose"},
      negatable = true,
      description = "Enable verbose output during generation",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optVerbose = false;

  private CommandLine commandLine = null;

  protected @NonNull CommandLine getCommandLine() {
    Assert.set(
        commandLine != null, "Command line must be initialized after Gorrect has been created.");
    return commandLine;
  }

  private int strategy(CommandLine.ParseResult args) {
    globallyInitialize();
    return new CommandLine.RunLast().execute(args);
  }

  private void globallyInitialize() {
    Logger.setLevel(optVerbose ? Level.FINE : Level.INFO);
    Rand.getInstance().setSeed(optSeed);
  }

  public static void main(String[] args) {
    Gorrect gorrect = new Gorrect();
    CommandLine commandLine = new CommandLine(gorrect);
    gorrect.commandLine = commandLine;
    System.exit(commandLine.setExecutionStrategy(gorrect::strategy).execute(args));
  }
}

interface GorrectSubCommand extends Callable<Integer> {

  enum SupportedGCs {
    G1(HotSpotVM.BRAND_NAME, "-XX:+UseG1GC"), // HotSpot's G1 Garbage Collector
    ZGC(HotSpotVM.BRAND_NAME, "-XX:+UseZGC"), // HotSpot's Z Garbage Collector
    SHENANDOAH(
        HotSpotVM.BRAND_NAME, "-XX:+UseShenandoahGC"), // HotSpot's Shenandoah Garbage Collector
    DEFAULT("", ""); // Use the default garbage collector

    // Which VM this GC is supported on
    String vmBrand;
    // The command line option to enable the GC
    String enableOptions;

    SupportedGCs(String vmBrand, String enable) {
      this.vmBrand = vmBrand;
      this.enableOptions = enable;
    }
  }

  default void checkOptions() throws CommandLine.ParameterException {}

  Integer runCommand() throws Exception;

  @Override
  public default Integer call() throws Exception {
    checkOptions();
    return runCommand();
  }
}

abstract class GorrectSubCommandOptionsMixin {}

abstract class GraphSmithOptionsMixin extends GorrectSubCommandOptionsMixin {

  @CommandLine.Option(
      names = {"--min-roots"},
      paramLabel = "number",
      description = "Minimum number of root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinRoots = 1;

  @CommandLine.Option(
      names = {"--max-roots"},
      paramLabel = "number",
      description = "Maximum number of root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxRoots = 3;

  @CommandLine.Option(
      names = {"--min-non-roots"},
      paramLabel = "number",
      description = "Minimum number of non-root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinNonRoots = 2;

  @CommandLine.Option(
      names = {"--max-non-roots"},
      paramLabel = "number",
      description = "Maximum number of non-root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxNonRoots = 5;

  @CommandLine.Option(
      names = {"--min-object-size"},
      paramLabel = "size",
      description = "Minimum size of an object",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optMinObjectSize = "1B";

  @CommandLine.Option(
      names = {"--max-object-size"},
      paramLabel = "size",
      description = "Maximum size of an object",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optMaxObjectSize = "128KB";

  @CommandLine.Option(
      names = {"--min-references"},
      paramLabel = "number",
      description = "Minimum number of references",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinReferences = 15;

  @CommandLine.Option(
      names = {"--max-references"},
      paramLabel = "number",
      description = "Maximum number of references",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxReferences = 25;

  @CommandLine.Option(
      names = {"--strong-reference-weight"},
      paramLabel = "weight",
      description = "Weight for generating a strong reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optStrongRefWeight = 50;

  @CommandLine.Option(
      names = {"--soft-reference-weight"},
      paramLabel = "weight",
      description = "Weight for generating a soft reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optSoftRefWeight = 20;

  @CommandLine.Option(
      names = {"--weak-reference-weight"},
      paramLabel = "weight",
      description = "Weight for generating a weak reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optWeakRefWeight = 30;

  protected GraphSmith.Config.ConfigBuilder getGraphSmithConfigBuilder() {
    return GraphSmith.Config.builder()
        .minRoots(optMinRoots)
        .maxRoots(optMaxRoots)
        .minNonRoots(optMinNonRoots)
        .maxNonRoots(optMaxNonRoots)
        .minRefs(optMinReferences)
        .maxRefs(optMaxReferences)
        .minObjSizeBytes(MemorySize.parse(optMinObjectSize))
        .maxObjSizeBytes(MemorySize.parse(optMaxObjectSize))
        .strongRefWeight(optStrongRefWeight)
        .softRefWeight(optSoftRefWeight)
        .weakRefWeight(optWeakRefWeight);
  }

  protected GraphSmith buildGraphSmith(IdSmith objectIdSmith, IdSmith referenceIdSmith) {
    return new GraphSmith(getGraphSmithConfigBuilder().build(), objectIdSmith, referenceIdSmith);
  }
}

@CommandLine.Command(
    name = "gsmi",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    description = "Generates a random object graph.",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class GraphSmithCommand extends GraphSmithOptionsMixin implements GorrectSubCommand {

  @Override
  public Integer runCommand() throws Exception {
    IdSmith objectIdSmith = new IdSmith("o");
    IdSmith referenceIdSmith = new IdSmith("l");
    NoOpVM vm = new NoOpVM(); // Do nothing
    GraphSmith smith = buildGraphSmith(objectIdSmith, referenceIdSmith);
    GGraph graph = smith.craft(vm);
    // TODO: Export the graph in other formats (e.g., JSON) that can be parsed.
    System.out.println(graph.formatToText());
    return 0;
  }
}

abstract class ProgramSmithOptionsMixin extends GraphSmithOptionsMixin {

  @CommandLine.Option(
      names = {"--min-instructions"},
      paramLabel = "number",
      description = "Minimum number of instructions to fuzz",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinInstructions = 1;

  @CommandLine.Option(
      names = {"--max-instructions"},
      paramLabel = "number",
      description = "Maximum number of instructions to fuzz",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxInstructions = 32;

  protected ProgramSmith.Config.ConfigBuilder getProgramSmithConfig() {
    return ProgramSmith.Config.builder()
        .minInstructions(optMinInstructions)
        .maxInstructions(optMaxInstructions)
        .minObjSizeBytes(MemorySize.parse(optMinObjectSize))
        .maxObjSizeBytes(MemorySize.parse(optMaxObjectSize));
  }

  protected ProgramSmith buildProgramSmith(IdSmith objectIdSmith, IdSmith referenceIdSmith) {
    return new ProgramSmith(getProgramSmithConfig().build(), objectIdSmith, referenceIdSmith);
  }
}

@CommandLine.Command(
    name = "psmi",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    description =
        "Generates (and validates if requested) a random Gorrect IR program (and the corresponding Java program if requested).",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class ProgramSmithCommand extends ProgramSmithOptionsMixin implements GorrectSubCommand {

  @CommandLine.ParentCommand protected Gorrect parent;

  @CommandLine.Option(
      names = {"-c", "--collector"},
      paramLabel = "name",
      description = "The garbage collector under validation (choices: ${COMPLETION-CANDIDATES})",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected SupportedGCs optUseGC = SupportedGCs.DEFAULT;

  @CommandLine.Option(
      names = {"-H", "--heap-capacity"},
      paramLabel = "size",
      description = "The heap capacity of the Java virtual machine under validation",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optHeapCapacity = "1GB";

  @CommandLine.Option(
      names = {"-o", "--output-dir"},
      paramLabel = "dir",
      description = "The output directory for the generated files (if any)",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected File optOutputDir = null;

  @CommandLine.Option(
      names = {"--validate"},
      negatable = true,
      description =
          "Validate the generated Java program against the host JVM and the oracle interpreter",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optValidate = false;

  @Override
  public void checkOptions() throws CommandLine.ParameterException {
    if (optUseGC != SupportedGCs.DEFAULT) {
      if (!optUseGC.vmBrand.equals(HostJVM.getBrandName())) {
        throw new CommandLine.ParameterException(
            parent.getCommandLine(),
            "The garbage collector must be compatible with the language VM: "
                + optUseGC
                + " is not compatible with "
                + HostJVM.getBrandName());
      }
    }

    try {
      MemorySize.parse(optHeapCapacity);
    } catch (Exception e) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(),
          "The heap capacity must be a valid memory size (e.g., 1GB, 512MB): " + optHeapCapacity,
          e);
    }

    if (optOutputDir != null) {
      if (optOutputDir.exists()) {
        throw new CommandLine.ParameterException(
            parent.getCommandLine(), "The output directory already exist: " + optOutputDir);
      }
      if (!optOutputDir.mkdir()) {
        throw new CommandLine.ParameterException(
            parent.getCommandLine(), "Failed to create the output directory: " + optOutputDir);
      }
    }

    if (optValidate && optOutputDir == null) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(),
          "Validation requires an output directory to store the generated Java program.");
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    // The JVM exists here to ensure our generated programs are not ouf of memory.
    JavaVM jvm = HostJVM.getInstance(MemorySize.parse(optHeapCapacity));
    IdSmith objectIdSmith = new IdSmith("o");
    IdSmith referenceIdSmith = new IdSmith("l");

    GraphSmith graphSmith = buildGraphSmith(objectIdSmith, referenceIdSmith);
    GGraph initState = graphSmith.craft(jvm);
    System.out.println("Initial State");
    System.out.println("-------------");
    System.out.println(initState.formatToText());

    ProgramSmith programSmith = buildProgramSmith(objectIdSmith, referenceIdSmith);

    GProgram program = programSmith.craft(jvm, initState);
    System.out.println("Gorrect IR Program");
    System.out.println("------------------");
    System.out.println(program.toString());

    ProgramSmith.Stats progamGenStats = programSmith.getStats();
    System.out.println("Smith Stats");
    System.out.println("-----------");
    System.out.println(progamGenStats.formatToText());

    // We run the oracle interpreter with an unlimited heap size to obtain the oracle state.
    JavaVM unlimitedJvm = new EpsilonVM(Long.MAX_VALUE);
    OracleInterpreter interpreter = new OracleInterpreter(unlimitedJvm, program);
    interpreter.interpret();
    GGraph oracleState = interpreter.getState();
    System.out.println("Oracle Interpreter State");
    System.out.println("------------------------");
    System.out.println(oracleState.formatToText());

    // Translate the Gorrect IR program to Java code and obtain the translator state.
    JavaTranslator translator = new JavaTranslator(program);
    translator.translate();
    GGraph transState = translator.getState();
    System.out.println("Java Translator State");
    System.out.println("----------------------");
    System.out.println(transState.formatToText());

    System.out.println("State Comparison");
    System.out.println("----------------");
    if (oracleState.equals(transState)) {
      System.out.println("Equals\n");
    } else {
      System.out.println("Not equals; there must be a bug in the Java translator!\n");
      return 1;
    }

    if (optOutputDir != null) {
      // Export the Java program to a file and execute it
      System.out.println("Java Program");
      System.out.println("------------");
      String javaFilePath =
          translator.exportToJava(optOutputDir.toString(), oracleState, /* heapDump= */ true);
      String javaFileFullPath = optOutputDir + File.separator + javaFilePath;
      System.out.println(Files.readString(Path.of(javaFileFullPath)));

      if (optValidate) {
        // Validate if the Java program after execution matches the oracle state.
        System.out.println("Java Execution State");
        System.out.println("--------------------");
        JavaExecutor executor = new JavaExecutor(jvm);
        try {
          String hprofFilePath = javaFilePath + ".hprof";
          JavaExecutor.Result execResult =
              executor.execute(
                  javaFilePath, hprofFilePath, optOutputDir.toString(), optUseGC.enableOptions);
          if (execResult.exitCode() == JavaExecutor.ExitCode.EXECUTION_ASSERTION) {
            System.out.println("FOUND A GC BUG");
            System.out.println(execResult.errorMessage());
            return 0;
          } else if (execResult.exitCode() != JavaExecutor.ExitCode.SUCCESS) {
            System.out.println("Java program execution failed: " + execResult.errorMessage());
            return 1;
          }

          String hprofFileFullPath = optOutputDir + File.separator + hprofFilePath;
          GGraph execState = HprofParser.parse(hprofFileFullPath);
          System.out.println(execState.formatToText());

          System.out.println("Oracle Check");
          System.out.println("------------");
          // Validate the Java execution state against the oracle interpreter state.
          JavaOracle validator = new JavaOracle(oracleState);
          JavaOracle.ValidationResult valiResult = validator.validate(execState);
          if (valiResult.isValid()) {
            System.out.println(
                "SUCCESS: The Java execution state matches the oracle interpreter state.");
          } else {
            System.out.println("FAILURE: " + valiResult.getFailureMessage());
            System.out.println(
                "Object Violations: "
                    + valiResult.getObjectViolations().entrySet().stream()
                        .map(s -> " - %s: %s".formatted(s.getKey(), s.getValue()))
                        .collect(Collectors.joining("\n")));
            System.out.println(
                "Reference Violations: "
                    + valiResult.getReferenceViolations().entrySet().stream()
                        .map(
                            s ->
                                " - (%s -> %s): %s"
                                    .formatted(s.getKey().a(), s.getKey().b(), s.getValue()))
                        .collect(Collectors.joining("\n")));
            return 0;
          }
        } catch (Exception e) {
          System.out.println("FAILED to execute Java code: " + e.getMessage());
          return 1;
        }
      }
    }

    return 0;
  }
}

@CommandLine.Command(
    name = "fuzz",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    description = "Fuzz and validate a target garbage collector of the host Java virtual machine.",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class FuzzCommand extends ProgramSmithOptionsMixin implements GorrectSubCommand {
  @CommandLine.ParentCommand protected Gorrect parent;

  @CommandLine.Option(
      names = {"-j", "--max-workers"},
      paramLabel = "number",
      description = "The maximum number of workers to use for fuzzing",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxWorkers =
      Math.max((int) ((double) Runtime.getRuntime().availableProcessors() * .8), 1);

  @CommandLine.Option(
      names = {"-L", "--max-iterations"},
      paramLabel = "number",
      description = "The maximum number of iterations to run the fuzzer (0 for infinite)",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxIterations = 0;

  @CommandLine.Option(
      names = {"-S", "--strict-validation"},
      negatable = true,
      description =
          "Validation by dumping the runtime heap and parsing and analyzing the HPROF "
              + "instead of directly checking the runtime state loosely",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optStrictValidation = false;

  @CommandLine.Option(
      names = {"-H", "--heap-capacity"},
      paramLabel = "size",
      required = true,
      description = "The heap capacity of the Java virtual machine under validation e.g., 1GB")
  protected String optHeapCapacity;

  @CommandLine.Option(
      names = {"-o", "--output-dir"},
      paramLabel = "dir",
      required = true,
      description = "The output directory for the generated files (if any)")
  protected File optOutputDir;

  @CommandLine.Parameters(
      paramLabel = "collector",
      description = "The garbage collector under validation (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGc;

  @Override
  public void checkOptions() throws CommandLine.ParameterException {
    if (optMaxWorkers <= 0) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "The maximum number of workers must be greater than 0.");
    }

    if (optMaxIterations < 0) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "The maximum number of iterations must be non-negative.");
    }

    try {
      MemorySize.parse(optHeapCapacity);
    } catch (Exception e) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(),
          "The heap capacity must be a valid memory size (e.g., 1GB, 512MB): " + optHeapCapacity,
          e);
    }

    if (argUseGc != SupportedGCs.DEFAULT) {
      if (!argUseGc.vmBrand.equals(HostJVM.getBrandName())) {
        throw new CommandLine.ParameterException(
            parent.getCommandLine(),
            "The garbage collector must be compatible with the language VM: "
                + argUseGc
                + " is not compatible with "
                + HostJVM.getBrandName());
      }
    }

    if (optOutputDir.exists()) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "The output directory already exist: " + optOutputDir);
    }
    if (!optOutputDir.mkdir()) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "Failed to create the output directory: " + optOutputDir);
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    JavaVM jvm = HostJVM.getInstance(MemorySize.parse(optHeapCapacity));
    new JavaFuzzer(
            JavaFuzzer.Config.builder()
                .strictValidation(optStrictValidation)
                .maxWorkers(optMaxWorkers)
                .outputDir(optOutputDir.toString())
                .maxIterations(optMaxIterations)
                .graphSmithConfig(getGraphSmithConfigBuilder().build())
                .programSmithConfig(getProgramSmithConfig().build())
                .javaVM(jvm)
                .useGcOptions(argUseGc.enableOptions)
                .build())
        .start();
    return 0;
  }
}

@CommandLine.Command(
    name = "scmp",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    description =
        "Compare the final runtime state of two different garbage collectors using the host JVM. "
            + "This command is usually helpful to identify if a bug reported by our fuzzer is "
            + "a bug of the fuzzer or a real bug of the target garbage collector.",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class StateCompareCommand implements GorrectSubCommand {
  @CommandLine.ParentCommand protected Gorrect parent;

  @CommandLine.Option(
      names = {"-H", "--heap-capacity"},
      paramLabel = "size",
      required = true,
      description = "The heap capacity of the Java virtual machine under validation")
  protected String argHeapCapacity;

  @CommandLine.Option(
      names = {"-a", "--gc-a"},
      paramLabel = "collector",
      required = true,
      description =
          "Options to use the collector A under comparison (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGcA;

  @CommandLine.Option(
      names = {"-b", "--gc-b"},
      paramLabel = "collector",
      required = true,
      description =
          "Options to use the collector B under comparison (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGcB;

  @CommandLine.Parameters(
      paramLabel = "program",
      description = "The Java program (generated by us) to run")
  protected File argJavaProg;

  @Override
  public void checkOptions() throws CommandLine.ParameterException {
    if (!argJavaProg.exists()) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "The Java program does not exist: " + argJavaProg);
    }
    if (!argJavaProg.isFile()) {
      throw new CommandLine.ParameterException(
          parent.getCommandLine(), "The Java program is not a file: " + argJavaProg);
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    Logger.setLevel(Level.SEVERE);

    JavaVM jvm = HostJVM.getInstance(MemorySize.parse(argHeapCapacity));
    JavaExecutor executor = new JavaExecutor(jvm);

    String progName = argJavaProg.getName();

    // Copy the Java program to the working directory
    String workdir = Files.createTempDirectory("gorrect-scmp-").toString();
    Files.copy(argJavaProg.toPath(), Path.of(workdir, progName));

    // Execute the Java program with both garbage collectors
    GGraph stateA =
        execute(
            executor,
            progName,
            argJavaProg.getName() + ".a.hprof",
            workdir,
            argUseGcA.enableOptions);
    Files.writeString(Path.of(workdir, "state-a.txt"), stateA.formatToText());
    GGraph stateB =
        execute(
            executor,
            progName,
            argJavaProg.getName() + ".b.hprof",
            workdir,
            argUseGcB.enableOptions);
    Files.writeString(Path.of(workdir, "state-b.txt"), stateB.formatToText());

    JavaOracle oracle = new JavaOracle(stateA);
    JavaOracle.ValidationResult result = oracle.validate(stateB);
    if (!result.isValid()) {
      String summary = result.getSummary().strip();
      Files.writeString(Path.of(workdir, "state-compare.txt"), summary);
      System.out.println("**GOTCHA**");
      System.out.println("Garbage collector A and B behave differently!");
      System.out.println("Below are the comparison summary using the collector A as the oracle:");
      System.out.println("------");
      System.out.println(summary);
      System.out.println("------");
      System.out.println("Heap dumps are stored in: " + workdir);
      return 1;
    }

    io.connglli.gorrect.utility.Files.deleteFileTreeIfExists(Path.of(workdir));

    return 0;
  }

  private GGraph execute(
      JavaExecutor executor, String javaFile, String dumpFile, String workdir, String useGc) {
    JavaExecutor.Result res = executor.execute(javaFile, dumpFile, workdir, useGc);
    if (res.exitCode() != JavaExecutor.ExitCode.SUCCESS) {
      System.out.println("Error: Unable to executed the given Java program: " + res.errorMessage());
      System.exit(1);
    }
    try {
      return HprofParser.parse(Path.of(workdir, dumpFile).toString());
    } catch (Exception e) {
      System.out.println("Error: Unable to parse the heap dump: " + e.getMessage());
      System.exit(1);
      return null; // Unreachable but required by the compiler
    }
  }
}
