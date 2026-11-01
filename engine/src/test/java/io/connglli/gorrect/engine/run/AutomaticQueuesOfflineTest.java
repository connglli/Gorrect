package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomaticQueuesOfflineTest {
  @TempDir Path directory;

  private TargetProfile profile() {
    var json = new JsonObject();
    json.addProperty("version", 1);
    json.addProperty("vm", "hotspot");
    json.addProperty("java", directory.resolve("absent/bin/java").toString());
    json.addProperty("collector", "serial");
    json.addProperty("observation", "roots-diagnostic");
    json.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xint", "-Xmx128m", "-Xshare:off")));
    var support = new JsonObject();
    for (String name : List.of("runtime", "protocol", "observer"))
      support.addProperty(name, "unused-" + name);
    json.add("support", support);
    return TargetProfile.parse(json, directory);
  }

  private static JsonObject manifest() {
    var images = new JsonObject();
    images.addProperty("java", "a2195ae9f8a91b82f53df7e1d1b9a650937ebbabbf67d0240c6378b4fbd9990b");
    images.addProperty(
        "target/release", "9568390645661374febde48f3c7a019f21cb116d6e4c627de29c119aa7bd0efc");
    images.addProperty(
        "target/lib/libjli.so", "fcb5e6a9031ab64399017ab5a71460887da501238d27ca3d929366b84e195a1a");
    images.addProperty(
        "target/lib/server/libjvm.so",
        "9a00b72837fd20c9450cf113ef6526af174cac87d4746b1713de26f1103d0b68");
    images.addProperty(
        "target/lib/jvm.cfg", "aa9efb969444c1484e29adecab55a122458090616e766b2f1230ef05bc3867e0");
    var manifest = new JsonObject();
    manifest.add("installationArtifacts", images);
    return manifest;
  }

  private static Properties facts() {
    var facts = new Properties();
    facts.setProperty("java.feature", "17");
    facts.setProperty("java.vm.version", "17.0.21+9");
    facts.setProperty("java.vm.name", "OpenJDK 64-Bit Server VM");
    facts.setProperty("flag.UseSerialGC", "true");
    facts.setProperty("flag.UseCompiler", "false");
    facts.setProperty("queue.automatic.shared", "false");
    facts.setProperty("queue.automatic.configured", "true");
    Map.of(
            "java.lang.Object",
            "4431a1f4836392de9979bc93ba9255a1ceb1cf5b46abd0b0ccde4e41023b0304",
            "java.lang.Thread",
            "d8c1fe6b4f6846d8d0114b7e301f17f3174e3eb726a863282ed1d0bc1def2aa7",
            "java.lang.ref.Reference$ReferenceHandler",
            "b9db045cc78fbca13e0c7edd038d98c1351d70acfcf8cb7c93cc1d090aa54b8e",
            "java.lang.ref.ReferenceQueue$Lock",
            "f99cdced8db0e37be588c1e40bfbfdb61bea0d724296bacc63de38c32333d9f5",
            "java.lang.ref.SoftReference",
            "e2c1eebe1531aba5d250363fd23122bead38edb6ceeb79b19fe5338f8594174e",
            "java.lang.ref.WeakReference",
            "28d0bc0f0ab25cf81a6baf3ffe37a8c4a35f7c8f001ccad514f5bd9e38731a3f")
        .forEach((name, hash) -> facts.setProperty("queue.automatic.library." + name, hash));
    return facts;
  }

  @Test
  void retainedAdmissionDoesNotRequireTheOriginalInstallation() throws Exception {
    var profile = profile();
    assertFalse(Files.exists(Path.of(profile.java())));
    assertTrue(AutomaticQueues.configuration(profile).isEmpty());
    assertFalse(AutomaticQueues.preflight(profile).isEmpty());
    AutomaticQueues.admission(profile, facts(), manifest());
  }

  @Test
  void everyRequiredRecordedDigestIsStrictAndHasNoFilesystemOrReportFallback() throws Exception {
    var profile = profile();
    var original = manifest();
    for (String key : original.getAsJsonObject("installationArtifacts").keySet()) {
      for (String damage : List.of("missing", "wrong", "null", "number")) {
        var changed = original.deepCopy();
        var images = changed.getAsJsonObject("installationArtifacts");
        if (damage.equals("missing")) images.remove(key);
        if (damage.equals("wrong")) images.addProperty(key, "0".repeat(64));
        if (damage.equals("null")) images.add(key, JsonNull.INSTANCE);
        if (damage.equals("number")) images.addProperty(key, 17);
        changed.addProperty("result", "SATISFIED");
        assertThrows(
            IOException.class,
            () -> AutomaticQueues.admission(profile, facts(), changed),
            key + damage);
      }
    }
    assertThrows(
        IOException.class, () -> AutomaticQueues.admission(profile, facts(), new JsonObject()));
    var malformed = new JsonObject();
    malformed.addProperty("installationArtifacts", "not an image map");
    assertThrows(IOException.class, () -> AutomaticQueues.admission(profile, facts(), malformed));
  }

  @Test
  void automaticProvenanceSelectionRetainsMissingKeysWithoutChangingOldInventory()
      throws Exception {
    var profile = profile();
    var explicit = Artifacts.installations(profile);
    assertFalse(explicit.containsKey("target/lib/libjli.so"));
    assertFalse(explicit.containsKey("target/lib/jvm.cfg"));
    var selected = Artifacts.installations(profile, true);
    for (String key :
        List.of(
            "java",
            "target/release",
            "target/lib/libjli.so",
            "target/lib/server/libjvm.so",
            "target/lib/jvm.cfg")) assertEquals("missing", selected.get(key), key);
    assertThrows(
        IOException.class,
        () -> {
          var invalid = new JsonObject();
          invalid.add("installationArtifacts", JsonFiles.GSON.toJsonTree(selected));
          AutomaticQueues.admission(profile, facts(), invalid);
        });
  }
}
