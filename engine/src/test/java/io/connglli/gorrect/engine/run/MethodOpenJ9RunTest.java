package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;

import io.connglli.gorrect.engine.config.JsonFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("openj9")
class MethodOpenJ9RunTest {
  @TempDir Path directory;

  @Test
  void selectedGenconRunsVerifiesAndReplaysDirectCalls() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, MethodRunTest.SOURCE), selected, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }
}
