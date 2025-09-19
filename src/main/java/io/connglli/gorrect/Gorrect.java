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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import lombok.NonNull;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

@CommandLine.Command(
    name = "gorrect",
    usageHelpWidth = 100,
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

  // Override this method to check the validity of the options and throw
  // IllegalArgumentException if the options are invalid.
  default void checkOptions() throws IllegalArgumentException {}

  // Override this method to implement the command logic.
  Integer runCommand() throws Exception;

  @Override
  default Integer call() throws Exception {
    try {
      checkOptions();
    } catch (IllegalArgumentException e) {
      System.err.println("Error: " + e.getMessage());
      return 1;
    }
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
      names = {"--min-nroots"},
      paramLabel = "number",
      description = "Minimum number of non-root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinNonRoots = 2;

  @CommandLine.Option(
      names = {"--max-nroots"},
      paramLabel = "number",
      description = "Maximum number of non-root objects",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxNonRoots = 5;

  @CommandLine.Option(
      names = {"--min-obj-size"},
      paramLabel = "size",
      description = "Minimum size of an object for example 1KB, 512MB",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optMinObjectSize = "1KB";

  @CommandLine.Option(
      names = {"--max-obj-size"},
      paramLabel = "size",
      description = "Maximum size of an object for example 1KB, 512MB",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optMaxObjectSize = "1MB";

  @CommandLine.Option(
      names = {"--min-refs"},
      paramLabel = "number",
      description = "Minimum number of references",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinReferences = 15;

  @CommandLine.Option(
      names = {"--max-refs"},
      paramLabel = "number",
      description = "Maximum number of references",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxReferences = 25;

  @CommandLine.Option(
      names = {"--strong-ref-wt"},
      paramLabel = "weight",
      description = "Weight for generating a strong reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optStrongRefWeight = 50;

  @CommandLine.Option(
      names = {"--soft-ref-wt"},
      paramLabel = "weight",
      description = "Weight for generating a soft reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optSoftRefWeight = 20;

  @CommandLine.Option(
      names = {"--weak-ref-wt"},
      paramLabel = "weight",
      description = "Weight for generating a weak reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optWeakRefWeight = 30;

  @CommandLine.Option(
      names = {"--vol-obj-prob"},
      paramLabel = "prob",
      description = "Probability to generate a volatile reference",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected double optVolatileRefProb = 0.30;

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
        .weakRefWeight(optWeakRefWeight)
        .volatileRefProb(optVolatileRefProb);
  }
}

@CommandLine.Command(
    name = "gsmi",
    usageHelpWidth = 100,
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
    GraphSmith smith =
        new GraphSmith(
            getGraphSmithConfigBuilder().buildUnchecked().check(), objectIdSmith, referenceIdSmith);
    GGraph graph = smith.craft(vm);
    // TODO: Export the graph in other formats (e.g., JSON) that can be parsed.
    System.out.println(graph.formatToText());
    return 0;
  }
}

abstract class ProgramSmithOptionsMixin extends GraphSmithOptionsMixin {

  @CommandLine.Option(
      names = {"--humon-obj-prob"},
      paramLabel = "prob",
      description = "Probability to generate a humongous object for region-based GCs",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected double optHumongousObjProb = 0.30;

  @CommandLine.Option(
      names = {"--min-insts"},
      paramLabel = "number",
      description = "Minimum number of instructions to generate",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinInstructions = 1;

  @CommandLine.Option(
      names = {"--max-insts"},
      paramLabel = "number",
      description = "Maximum number of instructions to generate",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxInstructions = 32;

  protected ProgramSmith.Config.ConfigBuilder getProgramSmithConfigBuilder() {
    return ProgramSmith.Config.builder()
        .minInstructions(optMinInstructions)
        .maxInstructions(optMaxInstructions)
        .minObjSizeBytes(MemorySize.parse(optMinObjectSize))
        .maxObjSizeBytes(MemorySize.parse(optMaxObjectSize));
  }
}

@CommandLine.Command(
    name = "psmi",
    usageHelpWidth = 100,
    mixinStandardHelpOptions = true,
    description =
        "Generates (and validates if requested) a random Gorrect IR program (and the corresponding Java program if requested).",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class ProgramSmithCommand extends ProgramSmithOptionsMixin implements GorrectSubCommand {

  @CommandLine.ParentCommand protected Gorrect parent;

  @CommandLine.Option(
      names = {"-H", "--heap-cap"},
      paramLabel = "size",
      description = "Heap capacity of the Java virtual machine under validation",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optHeapCapacity = "1GB";

  @CommandLine.Option(
      names = {"-o", "--out-dir"},
      paramLabel = "dir",
      required = true,
      description = "Output directory for saving the generated files")
  protected File optOutputDir = null;

  @CommandLine.Parameters(
      paramLabel = "collector",
      description = "Garbage collector under validation (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGC;

  @Override
  public void checkOptions() throws IllegalArgumentException {
    try {
      MemorySize.parse(optHeapCapacity);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "The heap capacity must be a valid memory size (e.g., 1GB, 512MB): " + optHeapCapacity,
          e);
    }
    if (optOutputDir == null) {
      throw new IllegalArgumentException("The output directory is not given");
    }
    if (optOutputDir.exists()) {
      throw new IllegalArgumentException("The output directory already exist: " + optOutputDir);
    }
    if (!argUseGC.vmBrand.equals(HostJVM.getBrandName())) {
      throw new IllegalArgumentException(
          "The specified garbage collector "
              + argUseGC
              + " is not supported by the host JVM "
              + HostJVM.getBrandName());
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    // The JVM exists here to ensure our generated programs are not ouf of memory.
    long heapCapacity = MemorySize.parse(optHeapCapacity);
    JavaVM jvm = HostJVM.getInstance(heapCapacity);
    IdSmith objectIdSmith = new IdSmith("o");
    IdSmith referenceIdSmith = new IdSmith("l");

    GraphSmith.Config.ConfigBuilder graphSmithConfigBuilder = getGraphSmithConfigBuilder();
    if (argUseGC.isRegionBased) {
      graphSmithConfigBuilder
          .humongousObjProb(optHumongousObjProb)
          .humongousObjSizeBytes(argUseGC.computeHumongousObjectMinSizes(heapCapacity));
    }

    GraphSmith graphSmith =
        new GraphSmith(
            graphSmithConfigBuilder.buildUnchecked().check(), objectIdSmith, referenceIdSmith);
    GGraph initState = graphSmith.craft(jvm);

    ProgramSmith.Config.ConfigBuilder programSmithConfigBuilder = getProgramSmithConfigBuilder();
    if (argUseGC.isRegionBased) {
      programSmithConfigBuilder
          .humongousObjProb(optHumongousObjProb)
          .humongousObjSizeBytes(argUseGC.computeHumongousObjectMinSizes(heapCapacity));
    }

    ProgramSmith programSmith =
        new ProgramSmith(
            programSmithConfigBuilder.buildUnchecked().check(), objectIdSmith, referenceIdSmith);
    GProgram program = programSmith.craft(jvm, initState);

    // We run the oracle interpreter with an unlimited heap size to obtain the oracle state.
    JavaVM unlimitedJvm = new EpsilonVM(Long.MAX_VALUE);
    OracleInterpreter interpreter = new OracleInterpreter(unlimitedJvm);
    interpreter.interpret(program);
    GGraph oracleState = interpreter.getState();

    // Translate the Gorrect IR program to Java code and obtain the translator state.
    JavaTranslator translator = new JavaTranslator();
    translator.translate(program);
    GGraph transState = translator.getState();

    Assert.set(
        oracleState.equals(transState),
        "The oracle state and the translator state is not equal. There should be bugs in the Java translator!");

    Assert.set(optOutputDir.mkdir(), "Failed to create the output directory: " + optOutputDir);

    String javaFilePath =
        translator.exportToJava(optOutputDir.toString(), oracleState, /* heapDump= */ true);
    String javaFileFullPath = optOutputDir + File.separator + javaFilePath;

    System.out.println(Files.readString(Path.of(javaFileFullPath)));

    return 0;
  }
}

@CommandLine.Command(
    name = "fuzz",
    usageHelpWidth = 100,
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
      description = "Maximum number of workers to use for fuzzing",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxWorkers =
      Math.max((int) ((double) Runtime.getRuntime().availableProcessors() * .8), 1);

  @CommandLine.Option(
      names = {"-L", "--max-iters"},
      paramLabel = "number",
      description = "Maximum number of iterations to run the fuzzer (0 for infinite)",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxIterations = 0;

  @CommandLine.Option(
      names = {"-S", "--strict"},
      negatable = true,
      description =
          "Validation by dumping the runtime heap and parsing and analyzing the HPROF "
              + "instead of directly checking the runtime state loosely",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optStrictValidation = false;

  @CommandLine.Option(
      names = {"--extra-opts"},
      paramLabel = "opts",
      description =
          "Extra JVM options to pass to the Java virtual machine under validation "
              + "(e.g., --extra-opts -Xcomp --extra-opts -XX:+UseStringDeduplication)",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String[] optExtraOpts = null;

  @CommandLine.Option(
      names = {"-H", "--heap-cap"},
      paramLabel = "size",
      description = "Heap capacity of the Java virtual machine under validation for example 1GB",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optHeapCapacity = "1GB";

  @CommandLine.Option(
      names = {"-o", "--out-dir"},
      paramLabel = "dir",
      required = true,
      description = "Output directory for the generated files (if any)")
  protected File optOutputDir;

  @CommandLine.Parameters(
      paramLabel = "collector",
      description = "Garbage collector under validation (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGc;

  @Override
  public void checkOptions() throws IllegalArgumentException {
    if (optMaxWorkers <= 0) {
      throw new IllegalArgumentException("The maximum number of workers must be greater than 0.");
    }

    if (optMaxIterations < 0) {
      throw new IllegalArgumentException("The maximum number of iterations must be non-negative.");
    }

    try {
      MemorySize.parse(optHeapCapacity);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "The heap capacity must be a valid memory size (e.g., 1GB, 512MB): " + optHeapCapacity,
          e);
    }

    if (optExtraOpts != null) {
      List<String> parsedOpts = new ArrayList<>();
      for (String opt : optExtraOpts) {
        if (opt != null && !opt.isBlank()) {
          for (String o : opt.split("\\s+")) {
            parsedOpts.add(o.trim());
          }
        }
      }
      optExtraOpts = parsedOpts.toArray(new String[0]);
    } else {
      optExtraOpts = new String[0];
    }

    if (optOutputDir.exists()) {
      throw new IllegalArgumentException("The output directory already exist: " + optOutputDir);
    }

    if (!argUseGc.vmBrand.equals(HostJVM.getBrandName())) {
      throw new IllegalArgumentException(
          "The specified garbage collector "
              + argUseGc
              + " is not supported by the host JVM "
              + HostJVM.getBrandName());
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    JavaVM jvm = HostJVM.getInstance(MemorySize.parse(optHeapCapacity));

    // Print the host JVM information
    Logger log = Logger.getLogger(Gorrect.class);
    Logger.setOutput(System.out);
    Logger.disableHeadless();

    log.info("Detected JVM: " + HostJVM.getBrandName());
    HostJVM.printInfo(System.out);
    System.out.println("JVM Implementation Details:");
    jvm.printInfo(System.out);

    log.info("Testing if the JVM can be launched ...");
    try {
      String testLog = testJVM(jvm);
      log.info("Success\n" + testLog);
    } catch (Exception e) {
      log.error(
          "Error: The specified JVM configuration is invalid. See below message:\n"
              + e.getMessage());
    }

    if (!optOutputDir.mkdir()) {
      log.error("Failed to create the output directory: " + optOutputDir);
    }

    // Start the fuzzer
    new JavaFuzzer(
            JavaFuzzer.Config.builder()
                .strictValidation(optStrictValidation)
                .maxWorkers(optMaxWorkers)
                .outputDir(optOutputDir.toString())
                .maxIterations(optMaxIterations)
                .humongousObjProb(optHumongousObjProb)
                .graphSmithConfig(getGraphSmithConfigBuilder().buildUnchecked())
                .programSmithConfig(getProgramSmithConfigBuilder().buildUnchecked())
                .javaVM(jvm)
                .useGC(argUseGc)
                .extraOptions(optExtraOpts)
                .buildUnchecked()
                .check())
        .start();

    return 0;
  }

  private String testJVM(JavaVM jvm) {
    ByteArrayOutputStream oss = new ByteArrayOutputStream();
    List<String> command = new ArrayList<>();
    command.add(jvm.getJavaExecutablePath());
    command.addAll(Arrays.stream(argUseGc.enableOptions).toList());
    command.addAll(Arrays.stream(optExtraOpts).toList());
    command.add("-Xmx" + MemorySize.toString(jvm.getHeapCapacity(), 0, true));
    command.add("-Xms" + MemorySize.toString(jvm.getHeapCapacity(), 0, true));
    command.addAll(List.of(argUseGc.getLoggingOptions("stderr")));
    command.add("--version");
    try {
      new ProcessExecutor()
          .command(command)
          .redirectOutput(oss)
          .redirectErrorStream(true)
          .exitValue(0)
          .execute();
      String output = oss.toString();
      // Some errors due to invalid options may still return exit code 0
      if (output.contains("Error") || output.contains("Exception")) {
        throw new IllegalArgumentException(output);
      }
      return output;
    } catch (Exception e) {
      throw new IllegalArgumentException(oss.toString(), e);
    }
  }
}

@CommandLine.Command(
    name = "scmp",
    usageHelpWidth = 100,
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
      names = {"-K", "--keep-wdir"},
      negatable = true,
      description = "Keep the temporarily created working directory for debugging purpose",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optKeepWorkdir = false;

  @CommandLine.Option(
      names = {"-H", "--heap-cap"},
      paramLabel = "size",
      required = true,
      description = "Heap capacity of the Java virtual machine under validation")
  protected String argHeapCapacity;

  @CommandLine.Option(
      names = {"-a", "--gc-a"},
      paramLabel = "collector",
      required = true,
      description = "Garbage collector A under comparison (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGcA;

  @CommandLine.Option(
      names = {"-b", "--gc-b"},
      paramLabel = "collector",
      required = true,
      description = "Garbage collector B under comparison (choices: ${COMPLETION-CANDIDATES})")
  protected SupportedGCs argUseGcB;

  @CommandLine.Parameters(
      paramLabel = "program",
      description = "The Java program (generated by us) to run")
  protected File argJavaProg;

  @Override
  public void checkOptions() throws IllegalArgumentException {
    if (!argJavaProg.exists()) {
      throw new IllegalArgumentException("The Java program does not exist: " + argJavaProg);
    }
    if (!argJavaProg.isFile()) {
      throw new IllegalArgumentException("The Java program is not a file: " + argJavaProg);
    }
    if (!argUseGcA.vmBrand.equals(HostJVM.getBrandName())) {
      throw new IllegalArgumentException(
          "The specified garbage collector A "
              + argUseGcA
              + " is not supported by the host JVM "
              + HostJVM.getBrandName());
    }
    if (!argUseGcB.vmBrand.equals(HostJVM.getBrandName())) {
      throw new IllegalArgumentException(
          "The specified garbage collector B "
              + argUseGcB
              + " is not supported by the host JVM "
              + HostJVM.getBrandName());
    }
  }

  @Override
  public Integer runCommand() throws Exception {
    Logger.setLevel(Level.SEVERE);

    JavaVM jvm = HostJVM.getInstance(MemorySize.parse(argHeapCapacity));
    JavaExecutor executor = new JavaExecutor(jvm);

    // TODO: In case no heap-dump option is specified in the Java program
    String progName = argJavaProg.getName();

    // Copy the Java program to the working directory
    String workdir = Files.createTempDirectory("gorrect-scmp-").toString();
    Files.copy(argJavaProg.toPath(), Path.of(workdir, progName));

    // Execute the Java program with both garbage collectors
    GGraph stateA =
        execute(executor, progName, argJavaProg.getName() + ".a.hprof", workdir, argUseGcA);
    Files.writeString(Path.of(workdir, "state-a.txt"), stateA.formatToText());
    GGraph stateB =
        execute(executor, progName, argJavaProg.getName() + ".b.hprof", workdir, argUseGcB);
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

    if (!optKeepWorkdir) {
      io.connglli.gorrect.utility.Files.deleteFileTreeIfExists(Path.of(workdir));
    } else {
      System.out.println("The working directory is kept at: " + workdir);
    }

    return 0;
  }

  private GGraph execute(
      JavaExecutor executor, String javaFile, String dumpFile, String workdir, SupportedGCs useGc) {
    List<String> options = new ArrayList<>(List.of(useGc.enableOptions));
    options.add("-Xmx" + MemorySize.toString(executor.getJvm().getHeapCapacity(), 0, true));
    options.add("-Xms" + MemorySize.toString(executor.getJvm().getHeapCapacity(), 0, true));
    if (parent.optVerbose) {
      options.addAll(
          Arrays.stream(useGc.getLoggingOptions(workdir + File.separator + dumpFile + ".log"))
              .toList());
    }
    JavaExecutor.Result res =
        executor.execute(javaFile, dumpFile, workdir, options.toArray(new String[0]));
    if (res.exitCode() != JavaExecutor.ExitCode.SUCCESS) {
      System.err.println("Error: Unable to executed the given Java program: " + res.errorMessage());
      System.exit(1);
    }
    try {
      return HprofParser.parse(Path.of(workdir, dumpFile).toString());
    } catch (Exception e) {
      System.err.println("Error: Unable to parse the heap dump: " + e.getMessage());
      System.exit(1);
      return null; // Unreachable but required by the compiler
    }
  }
}
