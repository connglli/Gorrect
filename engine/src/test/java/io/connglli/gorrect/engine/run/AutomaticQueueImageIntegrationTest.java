package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("observer")
class AutomaticQueueImageIntegrationTest {
  @Test
  void actualStartupFactsJoinSelectedImageAndModeWithoutEnablingTheRunGate() throws Exception {
    Path fixtures = Path.of(System.getProperty("gorrect.automaticQueue.artifacts"));
    var facts = new Properties();
    try (var input = Files.newInputStream(fixtures.resolve("automatic-facts.properties"))) {
      facts.load(input);
    }
    var json = new JsonObject();
    json.addProperty("version", 1);
    json.addProperty("vm", "hotspot");
    json.addProperty("java", Path.of(facts.getProperty("java.home"), "bin/java").toString());
    json.addProperty("collector", "serial");
    json.addProperty("observation", "roots-diagnostic");
    var support = new JsonObject();
    for (String name : List.of("runtime", "protocol", "observer"))
      support.addProperty(name, "unused-" + name);
    json.add("support", support);
    json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m", "-Xshare:off")));
    var profile = TargetProfile.parse(json, fixtures);
    var manifest = new JsonObject();
    manifest.add(
        "installationArtifacts", JsonFiles.GSON.toJsonTree(Artifacts.installations(profile, true)));
    AutomaticQueues.admission(profile, facts, manifest);
    for (String key :
        List.of(
            "java.feature",
            "java.vm.version",
            "flag.UseSerialGC",
            "flag.UseCompiler",
            "queue.automatic.shared",
            "queue.automatic.configured",
            "queue.automatic.library.java.lang.Thread",
            "queue.automatic.library.java.lang.ref.ReferenceQueue$Lock")) {
      var altered = new Properties();
      altered.putAll(facts);
      altered.setProperty(key, "unknown");
      assertThrows(
          IOException.class, () -> AutomaticQueues.admission(profile, altered, manifest), key);
    }
    for (String mode : List.of("missing-sharing-flag", "compiled", "collector", "vm")) {
      var altered = json.deepCopy();
      if (mode.equals("missing-sharing-flag"))
        altered.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m")));
      if (mode.equals("compiled"))
        altered.add(
            "vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xbatch", "-Xmx128m", "-Xshare:off")));
      if (mode.equals("collector")) altered.addProperty("collector", "g1");
      if (mode.equals("vm")) {
        altered.addProperty("vm", "openj9");
        altered.addProperty("collector", "gencon");
      }
      assertTrue(
          !AutomaticQueues.configuration(TargetProfile.parse(altered, fixtures)).isEmpty(), mode);
    }
  }
}
