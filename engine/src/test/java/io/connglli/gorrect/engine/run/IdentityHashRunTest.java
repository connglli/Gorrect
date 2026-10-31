package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual API bytecode, retained primitive evidence, offline verification and replay. */
class IdentityHashRunTest {
  @TempDir Path directory;

  private static final String PROGRAM =
      """
      gir 1; shape N object 0 1; shape A data 1;
      object 1 N [] [7]; object 2 A [] [9]; root main = 1; root array = 2;
      program {
        read-root n N main; read-root alias N main;
        first: identity-hash n; identity-hash null; checkpoint;
        write-data alias 0 11; collect; identity-hash alias;
        read-data value n 0;
        if-nonzero value { identity-hash n; } else { identity-hash null; }
        read-root a A array;
        repeat 2 { clone c a; identity-hash c; identity-hash c; }
        checkpoint;
        identity-hash n;
      }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void recordsRealHashCallsAndReplaysOnEachTargetRelease(int feature) throws IOException {
    Path selected = bindingProfile(feature);
    if (feature == 21 && System.getenv("GORRECT_IDENTITY_HOTSPOT_JAVA") != null) {
      var settings = JsonFiles.read(selected);
      settings.addProperty("java", System.getenv("GORRECT_IDENTITY_HOTSPOT_JAVA"));
      JsonFiles.write(selected, settings);
    }
    checkRun(selected);
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void arrayCopyPreservesEachAllocationsHashToken(int feature) throws IOException {
    String source =
        """
        gir 1; shape A data 2;
        object 1 A [] [7, 9]; root main = 1; root saved = null;
        program {
          read-root original A main;
          clone duplicate original;
          write-root saved duplicate;
          identity-hash original; identity-hash duplicate;
          copy-array original 0 duplicate 0 2;
          write-data original 0 11;
          collect;
          identity-hash original; identity-hash duplicate;
        }
        """;
    Path run = directory.resolve("copy-run");
    assertPass(RunEngine.run(input(directory, source), bindingProfile(feature), run));
    assertPass(RunEngine.verify(run));
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void preservesLocalBindingCaptureWithEachMetadataProvider(String metadata) throws IOException {
    Path selected = profile(directory, 21);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings")));
    JsonFiles.write(selected, settings);
    checkRun(selected);
  }

  @Tag("openj9")
  @Test
  void runsAndReplaysOnSelectedOpenJ9() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    checkRun(selected);
  }

  @Tag("hotspot")
  @Test
  void missingTraceAndControlEvidenceCannotEraseIndependentRefutations() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), bindingProfile(21), run));
    var trace = read(run);
    int first = hashIndices(trace).getFirst();
    int secondForSameObject = hashIndices(trace).get(2);
    Path wrong = copy(directory, run, "wrong");
    var corrupt = new ArrayList<>(trace.events());
    var event = corrupt.get(secondForSameObject);
    corrupt.set(
        secondForSameObject,
        new ExecutionTrace.Event(
            event.operation(), event.kind(), trace.events().get(first).value() == 0 ? 1 : 0));
    new ExecutionTrace(trace.run(), false, corrupt.subList(0, secondForSameObject + 1))
        .write(wrong.resolve("traces/execution.gtrace"));
    Files.delete(wrong.resolve("traces/control.json"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(wrong).verdict());

    Path missing = copy(directory, run, "missing");
    Files.delete(missing.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
    Path graph = copy(directory, run, "graph");
    RunTestSupport.rewriteSnapshot(graph, false);
    Files.delete(graph.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(graph).verdict());

    Path scalar = copy(directory, run, "scalar");
    var ordinary = new ArrayList<>(trace.events());
    int readOperation =
        GirParser.parse(PROGRAM)
            .instructions()
            .filter(Instruction.ReadData.class::isInstance)
            .findFirst()
            .orElseThrow()
            .id();
    int index =
        java.util.stream.IntStream.range(0, ordinary.size())
            .filter(i -> ordinary.get(i).operation() == readOperation)
            .findFirst()
            .orElseThrow();
    ordinary.set(
        index, new ExecutionTrace.Event(readOperation, ExecutionTrace.Kind.SCALAR_READ, 99));
    new ExecutionTrace(trace.run(), false, ordinary.subList(0, index + 1))
        .write(scalar.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(scalar).verdict());
  }

  @Tag("hotspot")
  @Test
  void captureIdentityGapsDoNotAssignGraphsToTheWrongCheckpoint() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), bindingProfile(21), run));
    Path first = copy(directory, run, "first");
    moveSnapshot(first, 2, 1);
    damageControl(first, 1);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(first).verdict());

    Path suffix = copy(directory, run, "suffix");
    moveSnapshot(suffix, 1, 2);
    damageControl(suffix, 2);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(suffix).verdict());

    Path prefix = copy(directory, run, "prefix");
    RunTestSupport.rewriteSnapshot(prefix, false);
    damageControl(prefix, 2);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(prefix).verdict());
  }

  private static void damageControl(Path run, int index) throws IOException {
    Path path = run.resolve("traces/control.json");
    var transcript = JsonFiles.read(path);
    var message = transcript.getAsJsonArray("messages").get(index).getAsJsonObject();
    message.addProperty("operation", 999);
    JsonFiles.write(path, transcript);
  }

  /** Moves an actual different program state while preserving the destination envelope index. */
  private static void moveSnapshot(Path run, int source, int destination) throws IOException {
    var snapshot =
        SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/" + source + ".gsnap")));
    Files.write(
        run.resolve("snapshots/" + destination + ".gsnap"),
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                destination,
                snapshot.status(),
                snapshot.jvmtiError(),
                snapshot.capabilities(),
                snapshot.gcStartsBefore(),
                snapshot.gcFinishesBefore(),
                snapshot.gcStartsAfter(),
                snapshot.gcFinishesAfter(),
                snapshot.classes(),
                snapshot.arrays(),
                snapshot.methods(),
                snapshot.records())));
  }

  @Tag("hotspot")
  @Test
  void namedRootCaptureNeedsNoAdditionalHashRoots() throws IOException {
    String program =
        "gir 1; shape N object 0 0; object 1 N [] []; root main = 1;"
            + " program { scope { read-root n N main; identity-hash n; collect; identity-hash n; }"
            + " checkpoint; }";
    Path run = directory.resolve("named");
    assertPass(RunEngine.run(input(directory, program), profile(directory, 21), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("named-replay")));
  }

  private Path bindingProfile(int feature) throws IOException {
    Path selected = profile(directory, feature);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(selected, settings);
    return selected;
  }

  private void checkRun(Path selected) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    assertHashBytecode(run);
    var trace = read(run);
    var hashes = hashIndices(trace);
    assertEquals(9, hashes.size());
    assertEquals(0, trace.events().get(hashes.get(1)).value());
    for (int index : List.of(2, 3, 8))
      assertEquals(
          trace.events().get(hashes.getFirst()).value(),
          trace.events().get(hashes.get(index)).value());
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
  }

  private static ExecutionTrace read(Path run) throws IOException {
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    return ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token);
  }

  private static List<Integer> hashIndices(ExecutionTrace trace) {
    Set<Integer> sites =
        GirParser.parse(PROGRAM)
            .instructions()
            .filter(Instruction.IdentityHash.class::isInstance)
            .map(Instruction::id)
            .collect(Collectors.toSet());
    return java.util.stream.IntStream.range(0, trace.events().size())
        .filter(index -> sites.contains(trace.events().get(index).operation()))
        .boxed()
        .toList();
  }

  private static void assertHashBytecode(Path run) {
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
    assertEquals(
        8,
        output
            .toString()
            .lines()
            .filter(
                line -> line.contains("invokestatic") && line.contains("System.identityHashCode:"))
            .count(),
        output.toString());
    assertTrue(!output.toString().contains(".hashCode:"), output.toString());
  }
}
