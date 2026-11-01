package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Narrow image/launch premises for the first JNI monitor adapter, never portable GC semantics. */
final class AutomaticQueues {
  private static final Set<String> OPTIONS = Set.of("-Xint", "-Xmx128m", "-Xshare:off");
  private static final String RELEASE =
      "9568390645661374febde48f3c7a019f21cb116d6e4c627de29c119aa7bd0efc";
  private static final String LAUNCHER =
      "a2195ae9f8a91b82f53df7e1d1b9a650937ebbabbf67d0240c6378b4fbd9990b";
  private static final String LAUNCH_LIBRARY =
      "fcb5e6a9031ab64399017ab5a71460887da501238d27ca3d929366b84e195a1a";
  private static final String VM_SELECTION =
      "aa9efb969444c1484e29adecab55a122458090616e766b2f1230ef05bc3867e0";
  private static final String VM =
      "9a00b72837fd20c9450cf113ef6526af174cac87d4746b1713de26f1103d0b68";
  private static final Map<String, String> LIBRARIES =
      Map.of(
          "java.lang.Object", "4431a1f4836392de9979bc93ba9255a1ceb1cf5b46abd0b0ccde4e41023b0304",
          "java.lang.Thread", "d8c1fe6b4f6846d8d0114b7e301f17f3174e3eb726a863282ed1d0bc1def2aa7",
          "java.lang.ref.Reference$ReferenceHandler",
              "b9db045cc78fbca13e0c7edd038d98c1351d70acfcf8cb7c93cc1d090aa54b8e",
          "java.lang.ref.ReferenceQueue$Lock",
              "f99cdced8db0e37be588c1e40bfbfdb61bea0d724296bacc63de38c32333d9f5",
          "java.lang.ref.SoftReference",
              "e2c1eebe1531aba5d250363fd23122bead38edb6ceeb79b19fe5338f8594174e",
          "java.lang.ref.WeakReference",
              "28d0bc0f0ab25cf81a6baf3ffe37a8c4a35f7c8f001ccad514f5bd9e38731a3f");

  private AutomaticQueues() {}

  static List<String> configuration(TargetProfile profile) {
    var problems = new ArrayList<String>();
    if (!profile.vm().equals("hotspot")
        || !profile.collector().equals("serial")
        || profile.vmArgs().size() != OPTIONS.size()
        || !Set.copyOf(profile.vmArgs()).equals(OPTIONS))
      problems.add(
          "Automatic queue adapter requires selected HotSpot17 Serial, -Xint -Xmx128m -Xshare:off");
    return List.copyOf(problems);
  }

  /** Live launch only; offline admission never resolves or hashes the configured installation. */
  static List<String> preflight(TargetProfile profile) {
    var problems = new ArrayList<>(configuration(profile));
    if (problems.isEmpty()) {
      try {
        image(profile);
      } catch (IOException | RuntimeException unavailable) {
        problems.add("Automatic queue image is unqualified: " + unavailable.getMessage());
      }
    }
    return List.copyOf(problems);
  }

  private static void image(TargetProfile profile) throws IOException {
    Path home = Path.of(profile.java()).toRealPath().getParent().getParent();
    if (!Artifacts.hash(home.resolve("release")).equals(RELEASE)
        || !Artifacts.hash(home.resolve("bin/java")).equals(LAUNCHER)
        || !Artifacts.hash(home.resolve("lib/libjli.so")).equals(LAUNCH_LIBRARY)
        || !Artifacts.hash(home.resolve("lib/server/libjvm.so")).equals(VM)
        || !Artifacts.hash(home.resolve("lib/jvm.cfg")).equals(VM_SELECTION))
      throw new IOException("Automatic queue runtime image bytes are unqualified");
  }

  /**
   * Retained image digests join effective launch and custody; no target installation is consulted.
   */
  static void admission(TargetProfile profile, Properties facts, JsonObject manifest)
      throws IOException {
    var problems = configuration(profile);
    if (!problems.isEmpty()) throw new IOException(String.join("; ", problems));
    try {
      var images = JsonFiles.object(JsonFiles.required(manifest, "installationArtifacts"));
      for (var image :
          Map.of(
                  "java",
                  LAUNCHER,
                  "target/release",
                  RELEASE,
                  "target/lib/libjli.so",
                  LAUNCH_LIBRARY,
                  "target/lib/server/libjvm.so",
                  VM,
                  "target/lib/jvm.cfg",
                  VM_SELECTION)
              .entrySet())
        if (!JsonFiles.string(JsonFiles.required(images, image.getKey())).equals(image.getValue()))
          throw new IOException("Retained automatic queue image is unqualified: " + image.getKey());
    } catch (IllegalArgumentException malformed) {
      throw new IOException("Missing or malformed retained automatic image provenance", malformed);
    }
    if (!facts.getProperty("java.feature", "").equals("17")
        || !facts.getProperty("java.vm.version", "").equals("17.0.21+9")
        || !facts.getProperty("java.vm.name", "").equals("OpenJDK 64-Bit Server VM")
        || !facts.getProperty("flag.UseSerialGC", "").equals("true")
        || !facts.getProperty("flag.UseCompiler", "").equals("false")
        || !facts.getProperty("queue.automatic.shared", "").equals("false")
        || !facts.getProperty("queue.automatic.configured", "").equals("true"))
      throw new IOException("Automatic queue effective image/mode/setup is unqualified");
    for (var library : LIBRARIES.entrySet())
      if (!facts
          .getProperty("queue.automatic.library." + library.getKey(), "")
          .equals(library.getValue()))
        throw new IOException(
            "Automatic queue bootstrap/helper image is unqualified: " + library.getKey());
  }
}
