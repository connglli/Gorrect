package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("hotspot")
class ScalarBranchRunTest {
  @TempDir Path directory;

  private static final String REFERENCES =
      """
      gir 1; shape N object 1 1; object 1 N [weak(null)] [0]; root main = 1;
      program {
        scope { read-root owner N main;
          repeat 3 { read-data value owner 0;
            if-nonzero value {
              collect;
              taken: checkpoint;
              write-data owner 0 0;
            } else {
              allocate held N;
              write-data held 0 -7;
              collect;
              other: checkpoint;
              write-data owner 0 -1;
            }
          }
        }
        outside: checkpoint;
      }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void capturesActualThenAndElseScopesInAReferenceHistory(int feature) throws IOException {
    Path run = directory.resolve("reference-branches");
    assertPass(RunEngine.run(input(directory, REFERENCES), controlled(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    long token = token(run);
    var program = GirParser.parse(REFERENCES);
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token);
    var visits = CheckpointPlan.recorded(program, trace);
    assertEquals(
        List.of("other", "taken", "other", "outside"),
        visits.stream()
            .filter(visit -> visit.operation() != 0)
            .map(visit -> program.labels().get(visit.operation()))
            .toList());
    assertEquals(2, visits.get(2).occurrence());
    for (int sequence = 1; sequence <= 3; sequence++)
      assertEquals(
          sequence == 2 ? 1 : 2,
          BindingEvidence.read(run.resolve("snapshots/" + sequence + ".gbind"), token, sequence)
              .bindings()
              .size());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void emitsDirectJavaAndReservesAnUnselectedLongerArm(int feature) throws IOException {
    String text =
        """
        gir 1; shape N object 0 1; object 1 N [] [0]; root main = 1;
        program { read-root source N main; read-data value source 0; release source;
          if-nonzero value {
            read-root local N main;
            write-data local 0 11; write-data local 0 12; write-data local 0 13;
            release local;
          } else {
            read-root local N main; write-data local 0 22; release local;
          }
          checkpoint;
        }
        """;
    Path run = directory.resolve("exact-branches");
    assertPass(RunEngine.run(input(directory, text), profile(directory, feature), run));
    assertPass(RunEngine.verify(run));
    var bounds = new Properties();
    try (var in = Files.newInputStream(run.resolve("generated/recorders.properties"))) {
      bounds.load(in);
    }
    // Three prefix events, the branch, five events in the longer arm, and the checkpoint.
    assertTrue(Long.parseLong(bounds.getProperty("0")) >= 10);
    assertEquals(
        8, ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run)).events().size());
    var output = new StringWriter();
    var errors = new StringWriter();
    assertEquals(
        0,
        ToolProvider.findFirst("javap")
            .orElseThrow()
            .run(
                new PrintWriter(output),
                new PrintWriter(errors),
                "-p",
                "-c",
                run.resolve("generated/classes/Case.class").toString()),
        errors.toString());
    String code = output.toString();
    int start = code.indexOf("private static void execute(");
    assertTrue(start >= 0);
    int end = code.indexOf("\n\n", start);
    String execute = code.substring(start, end < 0 ? code.length() : end);
    assertTrue(execute.contains("lcmp"), execute);
    assertTrue(execute.contains("ifeq") || execute.contains("ifne"), execute);
  }

  @Test
  void checksContendedCasChoicesAndKeepsRefutationsBesideLaterCaptureLoss() throws IOException {
    String text =
        """
        gir 1; shape N object 0 1; object 1 N [] [0]; object 2 N [] [0];
        root left = 1; root right = 2; root winner = null;
        program { parallel {
          worker a { read-volatile-root n N left; compare-set-root won winner null n;
            if-nonzero won { write-data n 0 11; } else { write-data n 0 22; } }
          worker b { read-volatile-root n N right; compare-set-root won winner null n;
            if-nonzero won { write-data n 0 11; } else { write-data n 0 22; } }
        }
        scope { read-root n N left; compare-set-data won n 0 11 33;
          if-nonzero won { taken: checkpoint; } else { other: checkpoint; }
        } }
        """;
    Path run = directory.resolve("cas-branches");
    Path selected = controlled(21);
    var settings = JsonFiles.read(selected);
    settings.getAsJsonObject("limits").addProperty("historyTransitions", 10_000_000);
    JsonFiles.write(selected, settings);
    assertPass(RunEngine.run(input(directory, text), selected, run));
    assertPass(RunEngine.verify(run));
    Path damaged = copy(directory, run, "damaged");
    long token = token(damaged);
    Path path = damaged.resolve("traces/execution.gtrace");
    var original = ExecutionTrace.read(path, token);
    int branch =
        GirParser.parse(text)
            .instructions()
            .filter(instruction -> instruction instanceof Instruction.IfNonzero)
            .findFirst()
            .orElseThrow()
            .id();
    var changed =
        original.events().stream()
            .map(
                event ->
                    event.operation() == branch
                        ? new ExecutionTrace.Event(
                            event.operation(),
                            event.kind(),
                            1 - event.value(),
                            event.worker(),
                            event.group(),
                            event.round(),
                            event.phase())
                        : event)
            .toList();
    new ExecutionTrace(token, true, changed).write(path);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(damaged).verdict());
    Files.delete(damaged.resolve("snapshots/2.gsnap"));
    var result = RunEngine.verify(damaged);
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
  }

  @Test
  void composesNumericBranchesWithCoordinatedWorkerCuts() throws IOException {
    String text =
        """
        gir 1; shape N object 0 1;
        program { scope { allocate caller N;
          parallel { worker only { allocate held N;
            if-nonzero -7 { write-data held 0 11; } else { write-data held 0 22; }
            checkpoint; release held; checkpoint;
          } }
        } }
        """;
    Path run = directory.resolve("worker-cuts");
    assertPass(RunEngine.run(input(directory, text), controlled(21), run));
    assertPass(RunEngine.verify(run));
  }

  private Path controlled(int feature) throws IOException {
    Path path = profile(directory, feature);
    var settings = JsonFiles.read(path);
    settings.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(path, settings);
    return path;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }
}
