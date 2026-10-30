package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pairs synthetic installation metadata with real JVM stack recordings to test proof boundaries.
 */
@Tag("hotspot")
class JitEvidenceTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void actualFramesAreRequiredDespiteInstallationClaims(int feature) throws Exception {
    Path classes = directory.resolve("classes");
    Files.createDirectory(classes);
    Path source = directory.resolve("Case.java");
    Files.writeString(source, SOURCE);
    Path support = Path.of(System.getProperty("gorrect.support"));
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
    for (String mode : List.of("compiled", "interpreted", "other", "descriptor", "duplicate")) {
      Path run = directory.resolve(mode);
      Files.createDirectories(run.resolve("traces"));
      Path jfr = run.resolve("traces/workers.jfr");
      var command =
          new ArrayList<>(
              List.of(
                  System.getProperty("gorrect.java" + feature),
                  "-Xmx128m",
                  mode.equals("interpreted") ? "-Xint" : "-Xcomp",
                  "-Xbatch",
                  "-XX:-TieredCompilation",
                  "-XX:CompileCommand=compileonly,Case::worker1",
                  "-XX:CompileCommand=compileonly,Case$Other::worker1",
                  "-XX:CompileCommand=dontinline,Case::worker1",
                  "-XX:CompileCommand=dontinline,Case$Other::worker1",
                  "-cp",
                  classes + File.pathSeparator + classpath,
                  "Case",
                  jfr.toString(),
                  mode));
      var builder =
          new ProcessBuilder(command)
              .redirectOutput(run.resolve("stdout.log").toFile())
              .redirectError(run.resolve("stderr.log").toFile());
      builder
          .environment()
          .keySet()
          .removeIf(
              name -> TargetProfile.ENVIRONMENT_OPTIONS.contains(name.toUpperCase(Locale.ROOT)));
      var process = builder.start();
      try {
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "JFR fixture deadline exceeded");
        assertEquals(0, process.exitValue(), Files.readString(run.resolve("stderr.log")));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      if (mode.equals("duplicate")) {
        assertThrows(IOException.class, () -> HotSpotFrames.read(jfr, 42));
        continue;
      }
      var frames = HotSpotFrames.read(jfr, 42);
      assertFalse(frames.isEmpty());
      boolean expected = mode.equals("compiled");
      assertEquals(expected, frames.stream().anyMatch(ExecutionEvidence.Frame::compiled), mode);
      long thread = frames.iterator().next().thread();
      var groups = new ArrayList<ExecutionEvidence.Group>();
      var windows = new ArrayList<ExecutionEvidence.Window>();
      for (int round = 1; round <= 5; round++) {
        var installed =
            round == 5
                ? List.of(new ExecutionEvidence.Compilation(1, "worker1", 4, 4, ""))
                : List.<ExecutionEvidence.Compilation>of();
        groups.add(new ExecutionEvidence.Group(2, round, List.of(1), true, 0, 0, installed));
        windows.add(
            new ExecutionEvidence.Window(
                1, 2, round, 0, thread, round * 100L, round * 100L + 50, -1, -1, 0));
      }
      new ExecutionEvidence(42, true, groups, windows)
          .write(run.resolve("traces/workers.gevidence"));
      var program =
          GirParser.parse(
              "gir 1; require compiled-execution; program { repeat 5 { parallel { worker only {"
                  + " collect; } } } }");
      var profile = profile();
      var result = StressVerifier.verify(run, 42, program, Oracle.interpret(program), profile);
      assertEquals(expected, result.unmet().isEmpty(), result.toString());
      if (expected) {
        var changed =
            windows.stream()
                .map(
                    window ->
                        new ExecutionEvidence.Window(
                            window.worker(),
                            window.group(),
                            window.round(),
                            window.phase(),
                            window.thread() + 100,
                            window.begin(),
                            window.end(),
                            -1,
                            -1,
                            0))
                .toList();
        new ExecutionEvidence(42, true, groups, changed)
            .write(run.resolve("traces/workers.gevidence"));
        assertFalse(
            StressVerifier.verify(run, 42, program, Oracle.interpret(program), profile)
                .unmet()
                .isEmpty());
      }
    }
  }

  private TargetProfile profile() throws IOException {
    Path path = directory.resolve("profile.json");
    Files.writeString(
        path,
        """
        {"version":1,"vm":"hotspot","java":"unused-java","collector":"serial",
         "observation":"named-roots-diagnostic",
         "support":{"runtime":"runtime.jar","protocol":"protocol.jar","observer":"observer.so","whitebox":"unused.jar"},
         "jit":{"level":4,"warmupRounds":4}}
        """);
    return TargetProfile.read(path);
  }

  private static final String SOURCE =
      """
      import io.connglli.gorrect.runtime.TargetSession;
      import io.connglli.gorrect.runtime.WorkerContext;
      import java.nio.file.Path;
      import jdk.jfr.*;
      public class Case {
        @Name("gorrect.WorkerExecution") @StackTrace(true)
        static class Witness extends Event {
          public long run = 42, round, collection;
          public int version = 1, worker = 1, group = 2, phase, point;
        }
        static int currentRound, currentPoint;
        static void emit() {
          Witness event = new Witness(); event.round = currentRound; event.point = currentPoint;
          event.collection = currentPoint < 2 ? 0 : 1; event.commit();
        }
        static void worker1(TargetSession session, WorkerContext worker) { emit(); }
        static void worker1(long wrongDescriptor) { emit(); }
        static class Other { static void worker1(TargetSession session, WorkerContext worker) { emit(); } }
        static void call(String mode) {
          if (mode.equals("other")) Other.worker1(null, null);
          else if (mode.equals("descriptor")) worker1(0L);
          else worker1(null, null);
        }
        public static void main(String[] args) throws Exception {
          try (Recording recording = new Recording()) {
            recording.enable(Witness.class).withStackTrace(); recording.start();
            for (currentRound = 1; currentRound <= 5; currentRound++)
              for (currentPoint = 0; currentPoint < 4; currentPoint++) call(args[1]);
            if (args[1].equals("duplicate")) { currentRound = 5; currentPoint = 0; call(args[1]); }
            recording.stop(); recording.dump(Path.of(args[0]));
          }
        }
      }
      """;
}
