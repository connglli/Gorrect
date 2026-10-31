package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedLaunchEvidenceTest {
  @TempDir Path directory;

  @Test
  void acceptsOwnedArgumentsOfflineWithoutTheInstallation() throws IOException {
    var profile = profile();
    var facts = facts(profile);
    ControlledLaunch.evidence(profile, manifest(profile, facts), facts);
    assertFalse(Files.exists(Path.of(profile.java())));
  }

  @Test
  void rejectsEffectiveAgentsLoadersIndirectOptionsAndCountDamage() throws IOException {
    var profile = profile();
    var baseline = facts(profile);
    var manifest = manifest(profile, baseline);
    int count = Integer.parseInt(baseline.getProperty("vm.arguments.count"));
    for (String argument :
        List.of(
            "-agentlib:other",
            "-javaagent:other.jar",
            "-Djava.system.class.loader=Other",
            "-Xoptionsfile=other",
            "--patch-module=java.base=other")) {
      var changed = new Properties();
      changed.putAll(baseline);
      changed.setProperty("vm.argument." + count, argument);
      changed.setProperty("vm.arguments.count", Integer.toString(count + 1));
      assertThrows(IOException.class, () -> ControlledLaunch.evidence(profile, manifest, changed));
    }
    for (String countValue : List.of("-1", "513", "unknown")) {
      var changed = new Properties();
      changed.putAll(baseline);
      changed.setProperty("vm.arguments.count", countValue);
      assertThrows(IOException.class, () -> ControlledLaunch.evidence(profile, manifest, changed));
    }
    baseline.remove("vm.argument.0");
    assertThrows(IOException.class, () -> ControlledLaunch.evidence(profile, manifest, baseline));
  }

  @Test
  void rejectsResolvedEnvironmentContradictionAndMissingEvidence() throws IOException {
    var profile = profile();
    var facts = facts(profile);
    for (String name : List.of("LD_PRELOAD", "LD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS", "CLASSPATH")) {
      var manifest = manifest(profile, facts);
      manifest.getAsJsonObject("environment").addProperty(name, "unreviewed");
      assertThrows(IOException.class, () -> ControlledLaunch.evidence(profile, manifest, facts));
    }
    var missing = manifest(profile, facts);
    missing.remove("environment");
    assertThrows(IOException.class, () -> ControlledLaunch.evidence(profile, missing, facts));
  }

  @Test
  void targetFactsRejectDuplicateArgumentProperties() throws IOException {
    Files.writeString(
        directory.resolve("target.properties"), "vm.arguments.count=1\nvm.arguments.count=2\n");
    assertThrows(IOException.class, () -> ControlledLaunch.targetFacts(directory));
  }

  private TargetProfile profile() {
    var json =
        JsonFiles.GSON.fromJson(
            """
            {"version":1,"vm":"hotspot","java":"absent-jdk/bin/java","collector":"serial",
             "observation":"roots-diagnostic","bindingMetadata":"retained-class",
             "support":{"runtime":"runtime.jar","protocol":"protocol.jar","observer":"observer.so"}}
            """,
            JsonObject.class);
    return TargetProfile.parse(json, directory);
  }

  private Properties facts(TargetProfile profile) {
    var facts = new Properties();
    var options = new ArrayList<>(VmDriver.launchOptions(profile, directory, facts));
    options.add("-Dgorrect.observation=roots-diagnostic");
    facts.setProperty("vm.arguments.count", Integer.toString(options.size()));
    for (int index = 0; index < options.size(); index++)
      facts.setProperty("vm.argument." + index, options.get(index));
    return facts;
  }

  private JsonObject manifest(TargetProfile profile, Properties facts) {
    var command = new ArrayList<String>();
    command.add(profile.java());
    command.addAll(VmDriver.launchOptions(profile, directory, facts));
    command.add("-Dgorrect.observation=roots-diagnostic");
    command.addAll(
        List.of(
            "-cp",
            String.join(
                File.pathSeparator,
                directory.resolve("generated/classes").toString(),
                directory.resolve("support/runtime.jar").toString(),
                directory.resolve("support/protocol.jar").toString()),
            "Case",
            "socket",
            "17",
            directory.toString(),
            directory.resolve("support/" + Artifacts.OBSERVER).toString(),
            Integer.toString(profile.limits().records()),
            Integer.toString(profile.limits().traceEvents())));
    var result = new JsonObject();
    result.addProperty("run", 17);
    result.addProperty("controlSocket", "socket");
    result.add("environment", new JsonObject());
    result.add("command", JsonFiles.GSON.toJsonTree(command));
    return result;
  }
}
