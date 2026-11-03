package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("openj9")
class NativeRootOpenJ9RunTest {
  @TempDir Path directory;

  static Stream<Arguments> policies() {
    return Stream.of(
        Arguments.of("gencon", false),
        Arguments.of("gencon", true),
        Arguments.of("balanced", false),
        Arguments.of("optavgpause", false),
        Arguments.of("optthruput", false),
        Arguments.of("metronome", false));
  }

  @ParameterizedTest
  @MethodSource("policies")
  void verifiesAliasedGlobalGenerationsAndCleanupAcrossPolicies(String policy, boolean retained)
      throws IOException {
    var settings = TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), policy);
    settings.addProperty("observation", "roots-diagnostic");
    if (retained) settings.addProperty("bindingMetadata", "retained-class");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m")));
    Path profile = directory.resolve("profile.json");
    JsonFiles.write(profile, settings);
    String source =
        """
        gir 1; shape N object 0 1;
        object 1 N [] [42]; object 2 N [] [7];
        root main = 1; root witness = 2;
        root saved jni-global = null; root alias jni-global = null;
        program {
          scope { read-root n N main; write-root saved n; write-root alias n; }
          write-root main null; before: checkpoint; collect; held: checkpoint;
          scope {
            read-root recovered N saved; read-data value recovered 0;
            write-root saved null; read-data surviving recovered 0;
            write-root saved recovered; write-root alias null;
          }
          replaced: checkpoint; write-root saved null; deleted: checkpoint;
        }
        """;
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, source), profile, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));

    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var before = NativeRootEvidence.read(run.resolve("snapshots/1.gnative"), token, 1);
    var held = NativeRootEvidence.read(run.resolve("snapshots/2.gnative"), token, 2);
    assertTrue(before.complete() && held.complete());
    var left = held.before().getFirst();
    var right = held.before().get(1);
    assertTrue(left.handle() != 0 && right.handle() != 0);
    assertEquals(2, left.kind());
    assertEquals(2, right.kind());
    assertEquals(left.tag(), right.tag());
    assertNotEquals(left.generation(), right.generation());
    assertEquals(before.before(), held.before());
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/2.gsnap")));
    assertEquals(
        2,
        raw.records().stream()
            .filter(
                row ->
                    row.kind() == HeapSnapshot.Kind.ROOT && row.b() == 21 && row.a() == left.tag())
            .count());
    var replaced = NativeRootEvidence.read(run.resolve("snapshots/3.gnative"), token, 3);
    assertTrue(replaced.before().getFirst().generation() > right.generation());
    assertEquals(left.tag(), replaced.before().getFirst().tag());
    assertEquals(0, replaced.before().get(1).handle());
    // OpenJ9 has no completed-cycle oracle; these are actual paired observer counters only.
    assertTrue(held.startsBefore() >= before.startsAfter());
    assertTrue(held.finishesBefore() >= before.finishesAfter());
    var cleanup = NativeRootEvidence.read(run.resolve("traces/native-cleanup.gnative"), token, 6);
    assertTrue(cleanup.cleanup() && cleanup.complete());
    assertTrue(cleanup.before().stream().allMatch(slot -> slot.handle() == 0));
    assertEquals(
        replaced.before().getFirst().generation(), cleanup.before().getFirst().generation());
    if (policy.equals("gencon") && !retained) {
      for (String damage :
          List.of("version", "feature", "platform", "qualification", "injection")) {
        Path changed = copy(directory, run, damage);
        var facts = Artifacts.target(changed);
        switch (damage) {
          case "version" -> facts.setProperty("java.vm.version", "unrecognized-version");
          case "feature" -> facts.setProperty("java.feature", "22");
          case "platform" -> facts.setProperty("os.arch", "aarch64");
          case "qualification" -> facts.remove("observer.nativeRoots.qualified");
          case "injection" -> facts.setProperty("vm.argument.0", "-javaagent:unreviewed.jar");
          default -> throw new AssertionError(damage);
        }
        try (var output = Files.newOutputStream(changed.resolve("target.properties"))) {
          facts.store(output, "Damaged native root admission evidence");
        }
        if (damage.equals("version")) assertPass(RunEngine.verify(changed));
        else
          assertNotEquals(
              ExactVerifier.Verdict.SATISFIED, RunEngine.verify(changed).verdict(), damage);
      }
    }
  }
}
