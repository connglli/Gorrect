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
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("hotspot")
class NativeRootCollectorRunTest {
  @TempDir Path directory;

  static Stream<Arguments> collectors() {
    return Stream.of(
        Arguments.of("serial", "default"),
        Arguments.of("parallel", "default"),
        Arguments.of("g1", "default"),
        Arguments.of("zgc", "non-generational"),
        Arguments.of("zgc", "generational"),
        Arguments.of("shenandoah", "satb"));
  }

  @ParameterizedTest
  @MethodSource("collectors")
  void retainsThroughCompletedCollectionAndCertifiesCleanup(String collector, String mode)
      throws IOException {
    var settings = TestProfiles.create("hotspot", System.getProperty("gorrect.java21"), collector);
    settings.addProperty("collectorMode", mode);
    settings.addProperty("observation", "roots-diagnostic");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m", "-Xshare:off")));
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("gc-completion")));
    Path profile = directory.resolve("profile.json");
    JsonFiles.write(profile, settings);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, NativeRootRunTest.SOURCE), profile, run));
    assertPass(RunEngine.verify(run));
    if (collector.equals("g1")) assertPass(RunEngine.replay(run, directory.resolve("replay")));

    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var held = NativeRootEvidence.read(run.resolve("snapshots/1.gnative"), token, 1);
    assertTrue(held.complete() && held.before().getFirst().handle() != 0);
    var cleanup = NativeRootEvidence.read(run.resolve("traces/native-cleanup.gnative"), token, 4);
    assertTrue(cleanup.cleanup() && cleanup.complete());
    assertEquals(0, cleanup.before().getFirst().handle());
  }
}
