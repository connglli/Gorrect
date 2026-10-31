package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.config.JsonFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("hotspot")
class PhaseReuseRunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void verifiesAndReplaysRepeatedWorkerPublicationAtDefaultHistoryBounds(int feature)
      throws IOException {
    String program =
        """
        gir 1; shape N object 0 1; root left = null; root right = null;
        program { scope { allocate caller N; repeat 2 { parallel {
          worker a { allocate held N; write-root left held; collect; checkpoint;
            write-root left null; release held; checkpoint; }
          worker b { allocate held N; write-root right held; collect; checkpoint;
            write-root right null; release held; checkpoint; }
        } } } checkpoint; }
        """;
    Path selected = profile(directory, feature);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", "roots-diagnostic");
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    JsonFiles.write(selected, raw);
    Path run = directory.resolve("run");

    assertPass(RunEngine.run(input(directory, program), selected, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    try (var snapshots = Files.list(run.resolve("snapshots"))) {
      assertEquals(4, snapshots.filter(path -> path.toString().endsWith(".gcut")).count());
    }
  }
}
