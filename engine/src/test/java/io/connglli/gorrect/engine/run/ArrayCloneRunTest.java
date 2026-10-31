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
import io.connglli.gorrect.engine.gir.GirParser;
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
 * Exercises actual clone calls, physical identities, shallow contents and retained verification.
 */
class ArrayCloneRunTest {
  @TempDir Path directory;

  static final String PROGRAM =
      """
      gir 1;
      shape N object 0 1; shape R refs 3; shape D data 2; shape E data 0;
      object 1 N [] [7]; object 2 R [1, 1, null] [];
      object 3 D [] [-9, 9223372036854775807]; object 4 E [] [];
      root references = 2; root data = 3; root empty = 4;
      root copiedReferences = null; root copiedData = null; root copiedEmpty = null;
      program {
        scope {
          read-root originalReferences R references;
          copy_refs: clone referenceCopy originalReferences;
          read-root originalData D data;
          copy_data: clone dataCopy originalData;
          read-root originalEmpty E empty;
          copy_empty: clone emptyCopy originalEmpty;
          write-root copiedReferences referenceCopy;
          write-root copiedData dataCopy; write-root copiedEmpty emptyCopy;
          same-object distinct originalReferences referenceCopy;
          same-object distinctEmpty originalEmpty emptyCopy;
          write-ref originalReferences 0 null;
          write-data dataCopy 0 99;
          read-ref originalTarget N originalReferences 1;
          read-ref copiedTarget N referenceCopy 0;
          same-object shared originalTarget copiedTarget;
          write-data copiedTarget 0 11;
          read-data unchanged originalData 0;
        }
        checkpoint;
        collect;
        checkpoint;
      }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void clonesRealArraysAndPreservesBytecodeThroughReplay(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, feature), run));
    checkPhysicalArrays(run);
    assertCloneBytecode(run, 3);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
  }

  @Tag("hotspot")
  @Test
  void corruptedCloneContentsRefuteAndMissingElementsRemainIncomplete() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, 21), run));
    Path corrupt = copy(directory, run, "corrupt");
    rewriteCloneElement(corrupt, false);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(corrupt).verdict());
    Path mixed = copy(directory, corrupt, "mixed");
    Files.delete(mixed.resolve("snapshots/2.gsnap"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(mixed).verdict());
    Path missing = copy(directory, run, "missing");
    rewriteCloneElement(missing, true);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
  }

  @Tag("hotspot")
  @Test
  void conditionalStorageReservesUnexecutedCloneBindings() throws IOException {
    String program =
        "gir 1; shape D data 1; object 1 D [] [7]; root source = 1; root saved = null; program {"
            + " read-root original D source; if-nonzero 1 { clone a original; write-root saved a; }"
            + " else { clone b original; write-root saved b; checkpoint; } }";
    Path run = directory.resolve("conditional");
    assertPass(RunEngine.run(input(directory, program), profile(directory, 21), run));
    assertPass(RunEngine.verify(run));
    assertCloneBytecode(run, 2);
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void capturesLiveCloneBindingsWithEachMetadataProvider(String metadata) throws IOException {
    Path selected = profile(directory, 21);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings")));
    JsonFiles.write(selected, settings);
    String program =
        "gir 1; shape D data 1; object 1 D [] [7]; root source = 1; root saved = null; program {"
            + " read-root original D source; clone copy original; write-root saved copy;"
            + " checkpoint; }";
    Path run = directory.resolve("bindings");
    assertPass(RunEngine.run(input(directory, program), selected, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("openj9")
  @Test
  void selectedOpenJ9ClonesAndReplaysWithoutIdentityAliases() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    checkPhysicalArrays(run);
    assertCloneBytecode(run, 3);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
  }

  private static void checkPhysicalArrays(Path run) throws IOException {
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var graph = SnapshotProjector.project(raw, layout).graph().orElseThrow();
    for (String name : List.of("References", "Data", "Empty")) {
      String source = name.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + name.substring(1);
      assertNotEquals(graph.roots().get(source), graph.roots().get("copied" + name));
    }
    var data = graph.objects().get(graph.roots().get("data").target());
    var copy = graph.objects().get(graph.roots().get("copiedData").target());
    assertEquals(List.of(-9L, Long.MAX_VALUE), data.data());
    assertEquals(List.of(99L, Long.MAX_VALUE), copy.data());
    assertTrue(graph.objects().get(graph.roots().get("copiedEmpty").target()).data().isEmpty());
  }

  private static void assertCloneBytecode(Path run, int count) {
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
        count,
        output
            .toString()
            .lines()
            .filter(line -> line.contains("invokevirtual") && line.contains(".clone:"))
            .count(),
        output.toString());
  }

  private static void rewriteCloneElement(Path run, boolean remove) throws IOException {
    var program = GirParser.parse(PROGRAM);
    int site =
        program.labels().entrySet().stream()
            .filter(entry -> entry.getValue().equals("copy_data"))
            .mapToInt(java.util.Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    Path path = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(path));
    long tag =
        snapshot.arrays().stream()
            .filter(array -> array.site() == site)
            .findFirst()
            .orElseThrow()
            .tag();
    var records = new ArrayList<>(snapshot.records());
    int index =
        java.util.stream.IntStream.range(0, records.size())
            .filter(
                i -> {
                  var record = records.get(i);
                  return record.kind() == HeapSnapshot.Kind.ARRAY_ELEMENT
                      && record.a() == tag
                      && record.b() == 0;
                })
            .findFirst()
            .orElseThrow();
    var record = records.get(index);
    if (remove) records.remove(index);
    else
      records.set(
          index,
          new HeapSnapshot.Record(
              record.kind(),
              record.a(),
              record.b(),
              record.c(),
              record.d() + 1,
              record.e(),
              record.f(),
              record.g()));
    Files.write(
        path,
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
