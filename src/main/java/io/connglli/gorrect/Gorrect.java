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

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.HprofParser;
import io.connglli.gorrect.java.JavaInterpreter;
import io.connglli.gorrect.java.oracle.HotSpotVM;
import io.connglli.gorrect.java.oracle.JavaVM;
import io.connglli.gorrect.java.oracle.NoopJVM;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.NoopVM;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.smith.GraphSmith;
import io.connglli.gorrect.smith.IdSmith;
import io.connglli.gorrect.smith.ProgramSmith;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.MemorySize;
import io.connglli.gorrect.utility.Rand;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import lombok.NonNull;
import picocli.CommandLine;

@CommandLine.Command(
    name = "gorrect",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    subcommands = {
      CommandLine.HelpCommand.class,
      GraphSmithCommand.class,
      ProgramSmithCommand.class
    },
    description =
        "Validate the correctness of a garbage collector based on object graph semantics.",
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

  protected GraphSmith buildGraphSmith(LangVM vm, IdSmith objectIdSmith, IdSmith referenceIdSmith) {
    return new GraphSmith(
        GraphSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
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
            .build());
  }
}

@CommandLine.Command(
    name = "gsmith",
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
    NoopVM vm = new NoopVM(); // Do nothing
    GraphSmith smith = buildGraphSmith(vm, objectIdSmith, referenceIdSmith);
    GGraph graph = smith.craft();
    System.out.println(graph.formatToText());
    return 0;
  }
}

abstract class ProgramSmithOptionsMixin extends GraphSmithOptionsMixin {

  @CommandLine.Option(
      names = {"--min-instructions"},
      paramLabel = "number",
      description = "Minimum number of instructions to smith",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMinInstructions = 1;

  @CommandLine.Option(
      names = {"--max-instructions"},
      paramLabel = "number",
      description = "Maximum number of instructions to smith",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optMaxInstructions = 32;

  protected ProgramSmith buildProgramSmith(
      LangVM vm, IdSmith objectIdSmith, IdSmith referenceIdSmith, GGraph initState) {
    ProgramSmith.Config programConfig =
        ProgramSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .initState(initState)
            .minInstructions(optMinInstructions)
            .maxInstructions(optMaxInstructions)
            .minObjSizeBytes(MemorySize.parse(optMinObjectSize))
            .maxObjSizeBytes(MemorySize.parse(optMaxObjectSize))
            .build();
    return new ProgramSmith(programConfig);
  }
}

@CommandLine.Command(
    name = "psmith",
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true,
    description =
        "Generates a random Gorrect IR program (and the corresponding Java program if requested).",
    descriptionHeading = "%n",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nArguments:%n")
class ProgramSmithCommand extends ProgramSmithOptionsMixin implements GorrectSubCommand {

  @CommandLine.ParentCommand protected Gorrect parent;

  enum SupportedLVMs {
    HOTSPOT,
    NOOPJVM,
  }

  enum SupportedGCs {
    G1(SupportedLVMs.HOTSPOT, "-XX:+UseG1GC"), // HotSpot's G1 Garbage Collector
    ZGC(SupportedLVMs.HOTSPOT, "-XX:+UseZGC"), // HotSpot's Z Garbage Collector
    SHENANDOAH(
        SupportedLVMs.HOTSPOT, "-XX:+UseShenandoahGC"), // HotSpot's Shenandoah Garbage Collector
    DEFAULT(SupportedLVMs.NOOPJVM, ""); // Use the default garbage collector

    // Which VM this GC is supported on
    SupportedLVMs vm;
    // The command line option to enable the GC
    String enableOptions;

    SupportedGCs(SupportedLVMs vm, String enable) {
      this.vm = vm;
      this.enableOptions = enable;
    }
  }

  // TODO: Replace SupportedLVMs with a path and determine its type at runtime
  @CommandLine.Option(
      names = {"--lang-vm"},
      paramLabel = "name",
      description = "The language VM under validation (choices: ${COMPLETION-CANDIDATES})",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected SupportedLVMs optUseVM = SupportedLVMs.NOOPJVM;

  @CommandLine.Option(
      names = {"--lang-vm-version"},
      paramLabel = "number",
      description = "The version of the language VM under validation",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected int optUseVMVersion = -1;

  @CommandLine.Option(
      names = {"--garbage-collector"},
      paramLabel = "name",
      description = "The garbage collector under validation (choices: ${COMPLETION-CANDIDATES})",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected SupportedGCs optUseGC = SupportedGCs.DEFAULT;

  @CommandLine.Option(
      names = {"--heap-capacity"},
      paramLabel = "size",
      description = "The heap capacity of the VM under validation",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected String optHeapCapacity = "1GB";

  @CommandLine.Option(
      names = {"--output-dir"},
      paramLabel = "dir",
      description = "The output directory for the generated files (if any)",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected File optOutputDir = null;

  @CommandLine.Option(
      names = {"--validate"},
      negatable = true,
      description = "Validate the generated program against the oracle interpreter",
      showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
  protected boolean optValidate = false;

  @Override
  public void checkOptions() throws CommandLine.ParameterException {
    if (optUseVM != SupportedLVMs.NOOPJVM && optUseGC != SupportedGCs.DEFAULT) {
      if (optUseGC.vm != optUseVM) {
        throw new CommandLine.ParameterException(
            parent.getCommandLine(),
            "The garbage collector must be compatible with the language VM: "
                + optUseGC
                + " is not compatible with "
                + optUseVM);
      }
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
    JavaVM jvm =
        switch (optUseVM) {
          // TODO: Add path to the VM executable
          case HOTSPOT -> new HotSpotVM(MemorySize.parse(optHeapCapacity), optUseVMVersion);
          case NOOPJVM -> new NoopJVM(MemorySize.parse(optHeapCapacity));
        };
    IdSmith objectIdSmith = new IdSmith("o");
    IdSmith referenceIdSmith = new IdSmith("l");

    GraphSmith graphSmith = buildGraphSmith(jvm, objectIdSmith, referenceIdSmith);
    GGraph initState = graphSmith.craft();
    System.out.println("Initial State");
    System.out.println("-------------");
    System.out.println(initState.formatToText());

    ProgramSmith programSmith = buildProgramSmith(jvm, objectIdSmith, referenceIdSmith, initState);

    GProgram program = programSmith.craft();
    System.out.println("Gorrect IR Program");
    System.out.println("------------------");
    System.out.println(program.toString());

    ProgramSmith.Stats progamGenStats = programSmith.getStats();
    System.out.println("Smith Stats");
    System.out.println("-----------");
    System.out.println(progamGenStats.formatToText());

    // Reset the JVM to ensure it starts with a clean state
    jvm.reset();

    OracleInterpreter oracle = new OracleInterpreter(jvm, program);
    oracle.interpret();
    GGraph oracleState = oracle.getState();
    System.out.println("Oracle Interpreter State");
    System.out.println("------------------------");
    System.out.println(oracleState.formatToText());

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
      return 1;
    }

    if (optOutputDir != null) {
      String javaFilePath = java.exportToJava(optOutputDir.toString());
      String javaFileFullPath = optOutputDir + File.separator + javaFilePath;
      System.out.println("Java Program");
      System.out.println("------------");
      System.out.println("Program generated at: " + javaFileFullPath);
      System.out.println(Files.readString(Path.of(javaFileFullPath)));

      if (optValidate) {
        System.out.println("Java Execution State");
        System.out.println("--------------------");
        try {
          String hprofFilePath =
              java.executeGeneratedJavaFile(
                  javaFilePath, jvm, optOutputDir.toString(), optUseGC.enableOptions);
          String hprofFileFullPath = optOutputDir + File.separator + hprofFilePath;
          System.out.println("HPROF generated at: " + hprofFileFullPath);
          GGraph execState = HprofParser.parse(hprofFileFullPath);
          System.out.println(execState.formatToText());

          // if (Oracle.equals(oracleState, execState)) {
          //   System.out.println("Java execution state matches the oracle interpreter state.");
          //   return 0;
          // } else {
          //   System.out.println("Java execution state does not match the oracle interpreter
          // state!");
          //   return 1;
          // }
        } catch (Exception e) {
          System.out.println("FAILED to execute Java code: " + e.getMessage());
          return 1;
        }
      }
    }

    return 0;
  }
}
