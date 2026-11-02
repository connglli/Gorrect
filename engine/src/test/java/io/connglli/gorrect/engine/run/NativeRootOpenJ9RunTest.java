package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("openj9")
class NativeRootOpenJ9RunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void verifiesAndReplaysGenconWithBothMetadataProviders(boolean retained) throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.addProperty("observation", "roots-diagnostic");
    if (retained) settings.addProperty("bindingMetadata", "retained-class");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m")));
    Path profile = directory.resolve("profile.json");
    JsonFiles.write(profile, settings);
    String source =
        NativeRootRunTest.SOURCE.replace("collect; held:", "before: checkpoint; collect; held:");
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, source), profile, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));

    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var before = NativeRootEvidence.read(run.resolve("snapshots/1.gnative"), token, 1);
    var held = NativeRootEvidence.read(run.resolve("snapshots/2.gnative"), token, 2);
    assertTrue(before.complete() && held.complete());
    assertTrue(held.before().getFirst().handle() != 0);
    assertEquals(before.before(), held.before());
    // OpenJ9 has no completed-cycle oracle; these are actual paired observer counters only.
    assertTrue(held.startsBefore() >= before.startsAfter());
    assertTrue(held.finishesBefore() >= before.finishesAfter());
    var cleanup = NativeRootEvidence.read(run.resolve("traces/native-cleanup.gnative"), token, 5);
    assertTrue(cleanup.cleanup() && cleanup.complete());
    assertEquals(0, cleanup.before().getFirst().handle());
  }
}
