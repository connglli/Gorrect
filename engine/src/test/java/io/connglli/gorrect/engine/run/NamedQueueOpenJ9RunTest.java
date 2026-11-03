package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("openj9")
class NamedQueueOpenJ9RunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualArrayOwnershipWorksWithBothBindingMetadataProviders(boolean retained)
      throws IOException {
    Path run = directory.resolve("run");
    assertPass(
        RunEngine.run(input(directory, NamedQueueRunTest.PROGRAM), selected(retained, false), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    if (!retained) {
      for (String damage :
          List.of(
              "version",
              "feature",
              "platform",
              "qualification",
              "Reference",
              "ReferenceQueue",
              "ReferenceQueue$Null")) {
        Path changed = copy(directory, run, damage);
        var facts = Artifacts.target(changed);
        switch (damage) {
          case "version" -> facts.setProperty("java.vm.version", "unrecognized-version");
          case "feature" -> facts.setProperty("java.feature", "22");
          case "platform" -> facts.setProperty("os.arch", "aarch64");
          case "qualification" -> facts.remove("observer.queues.ready");
          default -> facts.setProperty("queue.library." + damage, "unknown");
        }
        try (var output = Files.newOutputStream(changed.resolve("target.properties"))) {
          facts.store(output, "Damaged queue admission evidence");
        }
        if (damage.equals("version")) assertPass(RunEngine.verify(changed));
        else
          assertNotEquals(
              ExactVerifier.Verdict.SATISFIED, RunEngine.verify(changed).verdict(), damage);
      }
    }
  }

  @Test
  void softQueueUsesActualArrayOwnership() throws IOException {
    Path run = directory.resolve("soft");
    assertPass(
        RunEngine.run(
            input(directory, NamedQueueRunTest.PROGRAM.replace("shape W weak", "shape W soft")),
            selected(false, false),
            run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("soft-replay")));
  }

  @Test
  void emptyWrappedFullAndGrownRingsRetainCompleteMemberSets() throws IOException {
    String program =
        """
        gir 1; shape W weak; queue ready W;
        program {
          checkpoint;
          repeat 220 { allocate-ref r W null ready; enqueue-ref added r; }
          checkpoint;
          repeat 80 { poll-queue removed ready; }
          repeat 80 { allocate-ref r W null ready; enqueue-ref added r; }
          checkpoint;
          repeat 3 { allocate-ref r W null ready; enqueue-ref added r; }
          checkpoint;
          allocate-ref last W null ready; enqueue-ref added last; release last;
          checkpoint;
        }
        """;
    Path profile = selected(false, false);
    var settings = JsonFiles.read(profile);
    var limits = new JsonObject();
    limits.addProperty("records", 50000);
    limits.addProperty("historyTransitions", 5000000);
    settings.add("limits", limits);
    JsonFiles.write(profile, settings);
    Path run = directory.resolve("rings");
    assertPass(RunEngine.run(input(directory, program), profile, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("rings-replay")));
    long token = NamedQueueRunTest.token(run);
    var expected =
        new long[][] {
          {0, 0, 1, 0}, {0, 220, 0, 223}, {80, 77, 0, 223}, {80, 80, 0, 223}, {0, 224, 0, 245}
        };
    for (int i = 0; i < expected.length; i++) {
      var raw =
          QueueCapture.read(run.resolve("snapshots/" + (i + 1) + ".gqueue"), token, i + 1)
              .snapshot();
      var roots =
          raw.classes().stream()
              .filter(type -> type.signature().equals("LCase$QueueRoots;"))
              .findFirst()
              .orElseThrow();
      int field =
          roots.fields().stream()
              .filter(value -> value.name().equals("q1"))
              .findFirst()
              .orElseThrow()
              .index();
      long owner =
          raw.records().stream()
              .filter(
                  value ->
                      value.kind() == HeapSnapshot.Kind.REFERENCE
                          && value.a() == -roots.id()
                          && value.d() == field)
              .findFirst()
              .orElseThrow()
              .b();
      var type =
          raw.classes().stream()
              .filter(value -> value.signature().equals("LCase$Queue;"))
              .findFirst()
              .orElseThrow();
      assertEquals(expected[i][0], primitive(raw, owner, type, "head"));
      assertEquals(expected[i][1], primitive(raw, owner, type, "tail"));
      assertEquals(expected[i][2], primitive(raw, owner, type, "empty"));
      int arrayField =
          type.fields().stream()
              .filter(value -> value.name().equals("references"))
              .findFirst()
              .orElseThrow()
              .index();
      var edge =
          raw.records().stream()
              .filter(
                  value ->
                      value.kind() == HeapSnapshot.Kind.REFERENCE
                          && value.a() == owner
                          && value.d() == arrayField)
              .findFirst();
      long length =
          edge.isEmpty()
              ? 0
              : raw.records().stream()
                  .filter(
                      value ->
                          value.kind() == HeapSnapshot.Kind.OBJECT
                              && value.a() == edge.orElseThrow().b())
                  .findFirst()
                  .orElseThrow()
                  .d();
      assertEquals(expected[i][3], length);
    }
  }

  @Test
  void incompatibleClearBeforeEnqueueSettingIsUnsupportedBeforeConstruction() throws IOException {
    Path run = directory.resolve("unsupported");
    var result =
        RunEngine.run(input(directory, NamedQueueRunTest.PROGRAM), selected(false, true), run);
    assertEquals(RunReport.Outcome.UNSUPPORTED, result.execution(), result.toString());
  }

  private static long primitive(
      HeapSnapshot raw, long owner, HeapSnapshot.ClassInfo type, String name) {
    int index =
        type.fields().stream()
            .filter(field -> field.name().equals(name))
            .findFirst()
            .orElseThrow()
            .index();
    return raw.records().stream()
        .filter(
            value ->
                value.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                    && value.a() == owner
                    && value.b() == index)
        .findFirst()
        .orElseThrow()
        .d();
  }

  private Path selected(boolean retained, boolean incompatible) throws IOException {
    var profile =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    profile.addProperty("observation", "roots-diagnostic");
    if (retained) profile.addProperty("bindingMetadata", "retained-class");
    profile.add(
        "vmArgs",
        JsonFiles.GSON.toJsonTree(
            incompatible
                ? List.of("-Xmx128m", "-Xint", "-Djdk.lang.ref.disableClearBeforeEnqueue=true")
                : List.of("-Xmx128m", "-Xint")));
    Path path = directory.resolve("profile.json");
    JsonFiles.write(path, profile);
    return path;
  }
}
