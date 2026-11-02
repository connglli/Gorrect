package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
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

class ObjectCloneRunTest {
  @TempDir Path directory;

  static final String PROGRAM =
      """
      gir 1;
      shape N object 3 [int, long]; shape E object 0 0; shape U object 0 0; shape A refs 1;
      object 1 N [1, 2, 2] [4294967295, 9223372036854775807];
      object 2 A [1] []; object 3 E [] [];
      root original = 1; root empty = 3; root saved = null; root savedEmpty = null;
      program {
        scope {
          read-root n N original; clone c n; write-root saved c;
          read-root e E empty; repeat 3 { clone ec e; write-root savedEmpty ec; }
          same-object distinct n c; identity-hash n; identity-hash c;
          write-ref n 0 null; write-data c 0 2147483648;
          read-ref array A c 1; write-ref array 0 c;
          scope { clone arrayCopy array; same-object distinctArray array arrayCopy; }
        }
        checkpoint; collect; checkpoint;
      }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void realObjectCloneCopiesFieldsAndSurvivesExactClassReplay(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), objectProfile(feature), run));
    checkPhysicalObjects(run);
    assertBytecode(run);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    for (String name : List.of("Case", "Case$S0", "Case$S1", "Case$S2"))
      assertArrayEquals(
          Files.readAllBytes(run.resolve("generated/classes/" + name + ".class")),
          Files.readAllBytes(replay.resolve("generated/classes/" + name + ".class")));
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void physicalLocalBindingsUseEachMetadataProvider(String metadata) throws IOException {
    Path selected = objectProfile(21);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    settings.add(
        "requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings", "gc-completion")));
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("bindings");
    String bindings = PROGRAM.replace("write-ref array 0 c;", "write-ref array 0 c; checkpoint;");
    assertPass(RunEngine.run(input(directory, bindings), selected, run));
    checkPhysicalObjects(run);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("openj9")
  @Test
  void selectedOpenJ9UsesRealCloningAndCollectionEvents() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    checkPhysicalObjects(run);
    assertCollectionCounters(run);
    assertBytecode(run);
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
  }

  @Tag("openj9")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void selectedOpenJ9MapsLiveCopiesWithEachProvider(String metadata) throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("bindings");
    String bindings = PROGRAM.replace("write-ref array 0 c;", "write-ref array 0 c; checkpoint;");
    assertPass(RunEngine.run(input(directory, bindings), selected, run));
    checkPhysicalObjects(run);
    assertCollectionCounters(run);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("openj9")
  @Test
  void openJ9CompletionCertificateRequirementRemainsUnsupported() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    var report =
        RunEngine.run(input(directory, PROGRAM), selected, directory.resolve("unsupported"));
    assertEquals(RunReport.Outcome.UNSUPPORTED, report.execution());
    assertTrue(report.unmetRequirements().contains("gc-completion"));
  }

  @Tag("hotspot")
  @Test
  void corruptIdentityModifiersPayloadAndSourceSelectionCannotPass() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), objectProfile(21), run));
    for (String damage :
        List.of("worker", "site", "occurrence", "volatile", "duplicate", "payload")) {
      Path corrupt = copy(directory, run, damage);
      rewriteSnapshot(corrupt, damage);
      var verdict = RunEngine.verify(corrupt).verdict();
      assertEquals(
          damage.equals("payload")
              ? ExactVerifier.Verdict.VIOLATED
              : ExactVerifier.Verdict.INCONCLUSIVE,
          verdict,
          damage);
    }
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var unmarked = JsonFiles.read(run.resolve("generated/layout.json"));
    unmarked.getAsJsonArray("objects").get(0).getAsJsonObject().remove("cloneIdentity");
    assertTrue(
        SnapshotProjector.project(raw, JsonFiles.GSON.fromJson(unmarked, ObservationLayout.class))
            .graph()
            .isEmpty());
    for (String damage : List.of("qualification", "unknown-version", "injection")) {
      Path corruptFacts = copy(directory, run, damage);
      var facts = Artifacts.target(corruptFacts);
      if (damage.equals("qualification")) facts.remove("observer.objectClones.qualified");
      if (damage.equals("unknown-version")) facts.setProperty("java.feature", "23");
      if (damage.equals("injection"))
        facts.setProperty("vm.argument.0", "-javaagent:unreviewed.jar");
      try (var output = Files.newOutputStream(corruptFacts.resolve("target.properties"))) {
        facts.store(output, "Corrupted qualification");
      }
      assertNotEquals(
          ExactVerifier.Verdict.SATISFIED, RunEngine.verify(corruptFacts).verdict(), damage);
    }
    Path corrupt = copy(directory, run, "marker");
    var layout = JsonFiles.read(corrupt.resolve("generated/layout.json"));
    layout.getAsJsonArray("objects").get(0).getAsJsonObject().remove("cloneIdentity");
    JsonFiles.write(corrupt.resolve("generated/layout.json"), layout);
    assertNotEquals(ExactVerifier.Verdict.SATISFIED, RunEngine.verify(corrupt).verdict());
  }

  @Tag("hotspot")
  @Test
  void authenticatedUnsupportedSourceRefinesOnlyMissingOrMalformedReports() throws IOException {
    Path original = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), objectProfile(21), original));
    for (String damage : List.of("missing", "malformed", "null")) {
      for (String observation : List.of("roots-natural", "named-roots-diagnostic")) {
        Path run = copy(directory, original, damage + observation);
        var manifest = JsonFiles.read(run.resolve("manifest.json"));
        manifest.getAsJsonObject("profile").addProperty("observation", observation);
        JsonFiles.write(run.resolve("manifest.json"), manifest);
        if (damage.equals("missing")) Files.delete(run.resolve("execution.json"));
        else Files.writeString(run.resolve("execution.json"), damage.equals("null") ? "null" : "{");
        assertEquals(
            observation.equals("roots-natural")
                ? RunReport.Outcome.UNSUPPORTED
                : RunReport.Outcome.EXTRACTION_ERROR,
            RunEngine.verify(run).execution(),
            damage + observation);
      }
    }
    Path unauthenticated = copy(directory, original, "unauthenticated");
    var manifest = JsonFiles.read(unauthenticated.resolve("manifest.json"));
    manifest.getAsJsonObject("profile").addProperty("observation", "roots-natural");
    JsonFiles.write(unauthenticated.resolve("manifest.json"), manifest);
    Files.writeString(unauthenticated.resolve("case.gir"), "gir 1; program {}");
    Files.delete(unauthenticated.resolve("execution.json"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(unauthenticated).execution());

    Path failed = copy(directory, original, "failed");
    manifest = JsonFiles.read(failed.resolve("manifest.json"));
    manifest.getAsJsonObject("profile").addProperty("observation", "roots-natural");
    JsonFiles.write(failed.resolve("manifest.json"), manifest);
    JsonFiles.write(
        failed.resolve("execution.json"),
        RunReport.failure(RunReport.Outcome.CRASH, "retained crash"));
    assertEquals(RunReport.Outcome.CRASH, RunEngine.verify(failed).execution());
  }

  private Path objectProfile(int feature) throws IOException {
    Path selected = profile(directory, feature);
    var settings = JsonFiles.read(selected);
    settings.getAsJsonObject("limits").addProperty("startupSeconds", 30);
    settings.getAsJsonObject("limits").addProperty("runSeconds", 90);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion")));
    JsonFiles.write(selected, settings);
    return selected;
  }

  private static void checkPhysicalObjects(Path run) throws IOException {
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var snapshot = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var graph = SnapshotProjector.project(snapshot, layout).graph().orElseThrow();
    var original = graph.roots().get("original").target();
    var clone = graph.roots().get("saved").target();
    assertNotEquals(original, clone);
    assertNotEquals(graph.roots().get("empty"), graph.roots().get("savedEmpty"));
    assertEquals(List.of(-1L, Long.MAX_VALUE), graph.objects().get(original).data());
    assertEquals(
        List.of((long) Integer.MIN_VALUE, Long.MAX_VALUE), graph.objects().get(clone).data());
    assertEquals(original, graph.objects().get(clone).references().getFirst().target());
    assertEquals(
        graph.objects().get(clone).references().get(1),
        graph.objects().get(clone).references().get(2));
    for (var type : snapshot.classes()) {
      if (!List.of("LCase$S0;", "LCase$S1;", "LCase$S2;").contains(type.signature())) continue;
      boolean selected = !type.signature().equals("LCase$S2;");
      for (var field : type.fields()) {
        if (field.name().equals("idWorker")) assertTrue(Modifier.isFinal(field.modifiers()));
        if (List.of("idSite", "idOccurrence").contains(field.name()))
          assertEquals(!selected, Modifier.isFinal(field.modifiers()));
      }
    }
    assertFalse(
        Files.readString(run.resolve("generated/Case.java")).contains("bindArrayIdentity(c"));
    assertEquals("true", Artifacts.target(run).getProperty("observer.objectClones.qualified"));
  }

  private static void assertCollectionCounters(Path run) throws IOException {
    var first = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    Path last;
    try (var files = Files.list(run.resolve("snapshots"))) {
      last =
          files
              .filter(path -> path.toString().endsWith(".gsnap"))
              .max(
                  java.util.Comparator.comparingLong(
                      path -> Long.parseLong(path.getFileName().toString().replace(".gsnap", ""))))
              .orElseThrow();
    }
    var finalSnapshot = SnapshotCodec.read(Files.readAllBytes(last));
    assertTrue(finalSnapshot.gcFinishesBefore() > first.gcFinishesAfter());
    assertTrue(finalSnapshot.gcStartsBefore() >= finalSnapshot.gcFinishesBefore());
  }

  private static void assertBytecode(Path run) {
    for (String name : List.of("Case$S0", "Case$S1")) {
      var output = new StringWriter();
      assertEquals(
          0,
          ToolProvider.findFirst("javap")
              .orElseThrow()
              .run(
                  new PrintWriter(output),
                  new PrintWriter(output),
                  "-p",
                  "-c",
                  run.resolve("generated/classes/" + name + ".class").toString()));
      String helper = output.toString().split("copy\\(int, long\\)")[1];
      assertTrue(
          helper.contains("invokespecial") && helper.contains("java/lang/Object.clone"), helper);
      assertEquals(2, helper.lines().filter(line -> line.contains("putfield")).count(), helper);
      assertFalse(helper.contains("<init>"), helper);
      assertFalse(helper.contains("Unsafe"), helper);
    }
  }

  private static void rewriteSnapshot(Path run, String damage) throws IOException {
    Path file = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(file));
    var classes = new ArrayList<HeapSnapshot.ClassInfo>();
    for (var type : snapshot.classes()) {
      var fields = new ArrayList<HeapSnapshot.FieldInfo>();
      for (var field : type.fields()) {
        boolean change =
            type.signature().equals("LCase$S0;")
                && field
                    .name()
                    .equals(
                        switch (damage) {
                          case "worker" -> "idWorker";
                          case "site" -> "idSite";
                          case "occurrence" -> "idOccurrence";
                          case "volatile" -> "idSite";
                          default -> "none";
                        });
        fields.add(
            change
                ? new HeapSnapshot.FieldInfo(
                    field.index(),
                    damage.equals("volatile")
                        ? field.modifiers() | Modifier.VOLATILE
                        : field.modifiers() ^ Modifier.FINAL,
                    field.name(),
                    field.descriptor())
                : field);
      }
      classes.add(new HeapSnapshot.ClassInfo(type.id(), type.kind(), type.signature(), fields));
    }
    var records = new ArrayList<>(snapshot.records());
    if (damage.equals("payload") || damage.equals("duplicate")) {
      var type =
          classes.stream().filter(c -> c.signature().equals("LCase$S0;")).findFirst().orElseThrow();
      int index =
          type.fields().stream()
              .filter(f -> f.name().equals(damage.equals("payload") ? "d0" : "idSite"))
              .findFirst()
              .orElseThrow()
              .index();
      for (int position = 0; position < records.size(); position++) {
        var record = records.get(position);
        if (record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
            && record.b() == index
            && record.d() == (damage.equals("payload") ? Integer.MIN_VALUE : 3)) {
          records.set(
              position,
              new HeapSnapshot.Record(
                  record.kind(),
                  record.a(),
                  record.b(),
                  record.c(),
                  damage.equals("payload") ? record.d() + 1 : 0,
                  record.e(),
                  record.f(),
                  record.g()));
          break;
        }
      }
    }
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
                classes,
                snapshot.arrays(),
                snapshot.methods(),
                records)));
  }
}
