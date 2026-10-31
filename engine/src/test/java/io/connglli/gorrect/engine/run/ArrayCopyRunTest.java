package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real bulk calls with overlap, stable physical identities and independent semantic expectations.
 */
class ArrayCopyRunTest {
  @TempDir Path directory;

  static final String PROGRAM =
      """
      gir 1;
      shape N object 0 1; shape A refs 5; shape B refs 6; shape D data 6; shape E data 0; shape Z refs 0;
      object 1 N [] [7]; object 2 N [] [9];
      object 3 A [1,2,1,null,2] []; object 4 B [null,null,null,null,null,null] [];
      object 5 D [] [1,2,3,4,5,6]; object 6 D [] [9,9,9,9,9,9]; object 7 E [] []; object 8 Z [] [];
      root source=3; root destination=4; root data=5; root other=6; root empty=7; root emptyReferences=8;
      program {
        scope {
          read-root a A source; read-root b B destination;
          read-root d D data; read-root e D other; read-root z E empty;
          read-root zz Z emptyReferences;
          copy-array a 0 b 1 5;
          copy-array d 0 d 1 5;
          copy-array d 1 e 0 5;
          copy-array z 0 z 0 0;
          copy-array zz 0 zz 0 0;
          same-object distinct a b;
          read-ref originalTarget N a 0; read-ref copiedTarget N b 1;
          same-object shared originalTarget copiedTarget;
          write-data copiedTarget 0 11; write-ref b 1 null;
          read-data outside e 5;
        }
        checkpoint;
        collect;
        checkpoint;
      }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void copiesActualArraysAndRetainsBytecodeOnReplay(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, feature), run));
    assertActualArrays(run);
    assertArraycopyBytecode(run, 5);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void composesWithCloningWithoutLosingEitherIdentityOrOverlap(int feature) throws IOException {
    String program =
        """
        gir 1; shape A data 4;
        object 1 A [] [1,2,3,4]; object 2 A [] [0,0,0,0];
        root source=1; root destination=2; root cloned=null;
        program {
          scope {
            read-root a A source; read-root b A destination;
            clone staged a;
            copy-array staged 0 b 0 4;
            copy-array b 0 b 1 3;
            write-data staged 0 9;
            write-root cloned staged;
            same-object different a staged;
          }
          checkpoint;
        }
        """;
    Path run = directory.resolve("combined");
    assertPass(RunEngine.run(input(directory, program), profile(directory, feature), run));
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var graph = SnapshotProjector.project(raw, layout).graph().orElseThrow();
    var original = graph.roots().get("source").target();
    var cloned = graph.roots().get("cloned").target();
    assertNotEquals(original, cloned);
    assertEquals(List.of(1L, 2L, 3L, 4L), graph.objects().get(original).data());
    assertEquals(List.of(9L, 2L, 3L, 4L), graph.objects().get(cloned).data());
    assertEquals(
        List.of(1L, 1L, 2L, 3L),
        graph.objects().get(graph.roots().get("destination").target()).data());
    assertArraycopyBytecode(run, 2);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("hotspot")
  @Test
  void wrongCopiedValuesRefuteAndMissingValuesStayIncomplete() throws IOException {
    Path original = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, 21), original));
    Path wrong = copy(directory, original, "wrong");
    rewriteDestination(wrong, false);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(wrong).verdict());
    Path mixed = copy(directory, wrong, "mixed");
    Files.delete(mixed.resolve("snapshots/2.gsnap"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(mixed).verdict());
    Path missing = copy(directory, original, "missing");
    rewriteDestination(missing, true);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
  }

  @Tag("hotspot")
  @Test
  void scalarBranchPlanningDoesNotInventBindingsOrAllocations() throws IOException {
    String program =
        "gir 1; shape D data 2; object 1 D [] [7,9]; object 2 D [] [0,0]; root source=1; root"
            + " destination=2; program { read-root a D source; read-root b D destination;"
            + " if-nonzero 1 { copy-array a 0 b 0 2; } else { read-root unused D source; copy-array"
            + " unused 0 b 1 1; checkpoint; } }";
    Path run = directory.resolve("branch");
    var settings = profile(directory, 21);
    var json = JsonFiles.read(settings);
    json.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(settings, json);
    assertPass(RunEngine.run(input(directory, program), settings, run));
    assertPass(RunEngine.verify(run));
    assertArraycopyBytecode(run, 2);
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void observesLiveOperandsWithBothMetadataProviders(String metadata) throws IOException {
    Path settings = profile(directory, 21);
    var json = JsonFiles.read(settings);
    json.addProperty("observation", "roots-diagnostic");
    json.addProperty("bindingMetadata", metadata);
    json.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings")));
    JsonFiles.write(settings, json);
    // Keep the operand scope open across a capture; every live declaration needs physical evidence.
    String program =
        PROGRAM.replace("  read-data outside e 5;", "  read-data outside e 5; checkpoint;");
    assertTrue(program.contains("outside e 5; checkpoint;"));
    Path run = directory.resolve("bindings");
    assertPass(RunEngine.run(input(directory, program), settings, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("openj9")
  @Test
  void selectedOpenJ9CopiesAndReplaysWithTheSameArrayIdentities() throws IOException {
    var json = TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    Path settings = directory.resolve("profile.json");
    JsonFiles.write(settings, json);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), settings, run));
    assertActualArrays(run);
    assertArraycopyBytecode(run, 5);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  private static void assertActualArrays(Path run) throws IOException {
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var graph = SnapshotProjector.project(raw, layout).graph().orElseThrow();
    assertEquals(Value.Reference.strong(ObjectId.initial(3)), graph.roots().get("source"));
    assertEquals(Value.Reference.strong(ObjectId.initial(4)), graph.roots().get("destination"));
    assertEquals(Value.Reference.strong(ObjectId.initial(5)), graph.roots().get("data"));
    assertEquals(Value.Reference.strong(ObjectId.initial(6)), graph.roots().get("other"));
    assertEquals(List.of(1L, 1L, 2L, 3L, 4L, 5L), graph.objects().get(ObjectId.initial(5)).data());
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 9L), graph.objects().get(ObjectId.initial(6)).data());
    assertEquals(List.of(11L), graph.objects().get(ObjectId.initial(1)).data());
    assertEquals(
        List.of(
            Value.Reference.NULL,
            Value.Reference.NULL,
            Value.Reference.strong(ObjectId.initial(2)),
            Value.Reference.strong(ObjectId.initial(1)),
            Value.Reference.NULL,
            Value.Reference.strong(ObjectId.initial(2))),
        graph.objects().get(ObjectId.initial(4)).references());
    assertEquals(6, raw.arrays().size());
    assertEquals(6L, raw.arrays().stream().map(HeapSnapshot.ArrayIdentity::tag).distinct().count());
  }

  private static void assertArraycopyBytecode(Path run, int expected) {
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
        expected,
        output
            .toString()
            .lines()
            .filter(
                line ->
                    line.contains("invokestatic") && line.contains("java/lang/System.arraycopy:"))
            .count(),
        output.toString());
  }

  private static void rewriteDestination(Path run, boolean remove) throws IOException {
    Path file = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(file));
    long tag =
        snapshot.arrays().stream()
            .filter(array -> array.worker() == 0 && array.site() == 0 && array.occurrence() == 6)
            .findFirst()
            .orElseThrow()
            .tag();
    var records = new ArrayList<>(snapshot.records());
    int found = -1;
    for (int index = 0; index < records.size(); index++) {
      var record = records.get(index);
      if (record.kind() == HeapSnapshot.Kind.ARRAY_ELEMENT
          && record.a() == tag
          && record.b() == 0) {
        found = index;
        break;
      }
    }
    assertTrue(found >= 0);
    var record = records.get(found);
    if (remove) records.remove(found);
    else
      records.set(
          found,
          new HeapSnapshot.Record(
              record.kind(),
              record.a(),
              record.b(),
              record.c(),
              99,
              record.e(),
              record.f(),
              record.g()));
    Files.write(
        file,
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                snapshot.checkpoint(),
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
                records)));
  }
}
