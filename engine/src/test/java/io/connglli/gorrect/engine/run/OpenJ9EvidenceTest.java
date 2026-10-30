package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real VM frames must override synthetic installation claims, including overloaded methods. */
@Tag("openj9")
class OpenJ9EvidenceTest {
  @TempDir Path directory;

  @Test
  void executingFramesAreRequiredDespiteInstallationClaims() throws Exception {
    Path support = Path.of(System.getProperty("gorrect.support"));
    Path classes = Files.createDirectory(directory.resolve("classes"));
    Path source = directory.resolve("Case.java");
    Files.writeString(source, SOURCE);
    String classpath =
        support.resolve("runtime.jar") + File.pathSeparator + support.resolve("protocol.jar");
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "17",
                "-cp",
                classpath,
                "-d",
                classes.toString(),
                source.toString()));
    var profileJson = new JsonObject();
    profileJson.addProperty("version", 1);
    profileJson.addProperty("vm", "openj9");
    profileJson.addProperty("java", "unused-java");
    profileJson.addProperty("collector", "gencon");
    profileJson.addProperty("observation", "named-roots-diagnostic");
    var libraries = new JsonObject();
    for (String name : List.of("runtime", "protocol", "observer"))
      libraries.addProperty(name, "unused");
    profileJson.add("support", libraries);
    profileJson.add("jit", new JsonObject());
    var profile = TargetProfile.parse(profileJson, directory);
    for (String mode : List.of("compiled", "interpreted", "other", "descriptor")) {
      Path run = Files.createDirectory(directory.resolve(mode));
      Files.createDirectory(run.resolve("traces"));
      var command =
          new ArrayList<>(
              List.of(
                  System.getProperty("gorrect.openj9.java"),
                  "-Xmx128m",
                  "-Xshareclasses:none",
                  "-Xnoaot",
                  "-Xjit:count=0,limit={Case*.worker*}"));
      if (mode.equals("interpreted")) command.add("-Xint");
      command.addAll(
          List.of(
              "-agentpath:" + support.resolve("libgorrect_observer.so"),
              "-cp",
              classes + File.pathSeparator + classpath,
              "Case",
              run.toString(),
              mode,
              support.resolve("libgorrect_observer.so").toString()));
      var builder =
          new ProcessBuilder(command)
              .redirectOutput(run.resolve("stdout.log").toFile())
              .redirectError(run.resolve("stderr.log").toFile());
      ProcessSupport.cleanEnvironment(builder, Map.of());
      var process = builder.start();
      try {
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "OpenJ9 stack fixture deadline exceeded");
        assertEquals(0, process.exitValue(), Files.readString(run.resolve("stderr.log")));
      } finally {
        ProcessSupport.terminate(process);
      }
      var target = new Properties();
      try (var in = Files.newInputStream(run.resolve("target.properties"))) {
        target.load(in);
      }
      var frames = OpenJ9Driver.compiledFrames(run.resolve("traces/workers.gstacks"), 42, target);
      boolean expected = mode.equals("compiled");
      assertFalse(frames.isEmpty());
      assertEquals(expected, frames.stream().anyMatch(ExecutionEvidence.Frame::compiled), mode);
      long thread = frames.iterator().next().thread();
      var groups = new ArrayList<ExecutionEvidence.Group>();
      var windows = new ArrayList<ExecutionEvidence.Window>();
      for (int round = 1; round <= 2; round++) {
        // Deliberately claim installed code even for interpreted and wrong-method fixtures.
        groups.add(
            new ExecutionEvidence.Group(
                2,
                round,
                List.of(1),
                true,
                0,
                0,
                round == 2
                    ? List.of(new ExecutionEvidence.Compilation(1, "worker1", 1, 1, ""))
                    : List.of()));
        windows.add(new ExecutionEvidence.Window(1, 2, round, 0, thread, 100, 200, -1, -1, 0));
      }
      new ExecutionEvidence(42, true, groups, windows)
          .write(run.resolve("traces/workers.gevidence"));
      var program =
          GirParser.parse(
              "gir 1; require compiled-execution; program { repeat 2 { parallel { worker only {"
                  + " collect; } } } }");
      var result = StressVerifier.verify(run, 42, program, Oracle.interpret(program), profile);
      assertEquals(expected, result.unmet().isEmpty(), result.toString());
      if (expected) {
        target.setProperty("vm.start", "1");
        try (var out = Files.newOutputStream(run.resolve("target.properties"))) {
          target.store(out, "Wrong VM");
        }
        assertFalse(
            StressVerifier.verify(run, 42, program, Oracle.interpret(program), profile)
                .unmet()
                .isEmpty());
      }
    }
  }

  private static final String SOURCE =
"""
      import io.connglli.gorrect.runtime.*;
      import io.connglli.gorrect.runtime.observe.NativeObserver;
      import io.connglli.gorrect.protocol.StackEvidence;
      import java.nio.file.*;
      import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
      public class Case {
        static List<StackEvidence.Witness> witnesses = new ArrayList<>();
        static int round, point;
        static void emit() {
          var raw = Objects.requireNonNull(NativeObserver.openJ9Stack());
          var frames = new ArrayList<StackEvidence.Frame>();
          for (int index = 0; index < raw.length; index += 4)
            frames.add(new StackEvidence.Frame(raw[index], raw[index+1], raw[index+2], Integer.parseInt(raw[index+3])));
          witnesses.add(new StackEvidence.Witness(1, 2, round, 0, point, point < 2 ? 0 : 1, Thread.currentThread().getId(), frames));
        }
        static void worker1(TargetSession session, WorkerContext worker) { emit(); }
        static void worker1(long wrongDescriptor) { emit(); }
        static class Other { static void worker1(TargetSession session, WorkerContext worker) { emit(); } }
        public static void main(String[] args) throws Exception {
          System.load(args[2]);
          for (round = 1; round <= 2; round++) for (point = 0; point < 4; point++) {
            if (args[1].equals("other")) Other.worker1(null, null);
            else if (args[1].equals("descriptor")) worker1(0L);
            else worker1(null, null);
          }
          Path directory = Path.of(args[0]); var facts = VmFacts.read();
          try (var out = Files.newOutputStream(directory.resolve("target.properties"))) { facts.store(out, "Fixture VM"); }
          new StackEvidence(42, ProcessHandle.current().pid(), Long.parseLong(facts.getProperty("vm.start")), witnesses)
              .write(directory.resolve("traces/workers.gstacks"));
        }
      }
""";
}
