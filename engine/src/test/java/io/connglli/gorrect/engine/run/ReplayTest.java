package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static io.connglli.gorrect.engine.run.RunTestSupport.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import io.connglli.gorrect.engine.config.JsonFiles;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executable provenance and restored launch settings across exact-class replay. */
@Tag("hotspot")
class ReplayTest {
  @TempDir Path directory;

  @Test
  void recordsInstallationChangesAndVerifiesWithoutTheLauncher() throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path profile = profile(directory, 21), launcher = directory.resolve("java-launcher");
    String script = "#!/bin/sh\nexec " + quote(System.getProperty("gorrect.java21")) + " \"$@\"\n";
    Files.writeString(launcher, script);
    Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
    var object = JsonFiles.read(profile);
    object.addProperty("java", launcher.toString());
    JsonFiles.write(profile, object);
    Path original = directory.resolve("original");
    assertPass(RunEngine.probe(profile, original));
    Files.writeString(launcher, script + "# Changed launcher identity\n");
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(original, replay));
    assertTrue(
        JsonFiles.read(replay.resolve("manifest.json"))
            .getAsJsonArray("changedInstallationArtifacts")
            .contains(new JsonPrimitive("java")));
    Files.delete(launcher);
    Files.delete(profile);
    assertPass(RunEngine.verify(original));
  }

  @Test
  void replayRestoresTheRecordedEnvironment() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.probe(profile(directory, 21), run));
    var manifest = JsonFiles.read(run.resolve("manifest.json"));
    // Model an inherited variable from the original engine, absent from this process's environment.
    manifest.getAsJsonObject("environment").addProperty("GORRECT_SAVED_ENVIRONMENT", "retained");
    JsonFiles.write(run.resolve("manifest.json"), manifest);
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertEquals(
        "retained",
        JsonFiles.read(replay.resolve("manifest.json"))
            .getAsJsonObject("environment")
            .get("GORRECT_SAVED_ENVIRONMENT")
            .getAsString());
  }
}
