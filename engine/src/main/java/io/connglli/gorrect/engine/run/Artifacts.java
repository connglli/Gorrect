package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

/** Run-bundle paths, decoding, support copies, and executable provenance. */
final class Artifacts {
  private Artifacts() {}

  static final String OBSERVER = System.mapLibraryName("gorrect_observer");

  static String hash(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  /**
   * Fingerprints saved GIR, generated inputs/classes, and bundled support using bundle-relative
   * keys. Observations and reports remain untrusted evidence outside this executable manifest.
   */
  static Map<String, String> inputs(Path directory) throws IOException {
    var result = new LinkedHashMap<String, String>();
    for (String name : new String[] {"case.gir", "generated", "support"}) {
      Path path = directory.resolve(name);
      if (Files.isDirectory(path)) {
        try (var files = Files.walk(path)) {
          for (var file : files.filter(Files::isRegularFile).sorted().toList())
            result.put(directory.relativize(file).toString().replace('\\', '/'), hash(file));
        }
      } else if (Files.isRegularFile(path)) result.put(name, hash(path));
    }
    return result;
  }

  /**
   * Checks required executable entries, hashes, and the complete input inventory. Rejects escaping
   * relative paths, altered/missing bytes, and unexpected executable inputs; graph evidence needs
   * its own validation. Neither the bundle nor manifest is modified.
   */
  static void check(Path directory, JsonObject manifest) throws IOException {
    var hashes = JsonFiles.required(manifest, "hashes").getAsJsonObject();
    if (!hashes.has("case.gir")
        || !hashes.has("generated/Case.java")
        || !hashes.has("generated/layout.json")
        || !hashes.has("generated/classes/Case.class"))
      throw new IOException("Incomplete executable manifest");

    for (var entry : hashes.entrySet()) {
      Path file = directory.resolve(entry.getKey()).normalize();
      if (!file.startsWith(directory) || !hash(file).equals(JsonFiles.string(entry.getValue())))
        throw new IOException("Artifact identity mismatch: " + entry.getKey());
    }

    if (!inputs(directory)
        .equals(JsonFiles.GSON.fromJson(hashes, new TypeToken<Map<String, String>>() {}.getType())))
      throw new IOException("Unexpected or missing executable artifact");
  }

  static void copyTree(Path from, Path to) throws IOException {
    if (Files.isDirectory(from))
      try (var files = Files.walk(from)) {
        for (var file : files.toList()) {
          Path target = to.resolve(from.relativize(file));
          if (Files.isDirectory(file)) Files.createDirectories(target);
          else Files.copy(file, target);
        }
      }
    else Files.copy(from, to);
  }

  /** Reads the manifest and checks its format version; callers validate fields they consume. */
  static JsonObject manifest(Path directory) throws IOException {
    var value = JsonFiles.read(directory.resolve("manifest.json"));
    if (JsonFiles.number(JsonFiles.required(value, "version")) != 1)
      throw new IOException("Unsupported run manifest");
    return value;
  }

  /** Reads retained target facts without querying the original JVM or installation. */
  static Properties target(Path directory) throws IOException {
    var target = new Properties();
    try (var input = Files.newInputStream(directory.resolve("target.properties"))) {
      target.load(input);
    }
    return target;
  }

  /** Decodes one bounded capture with matching run/checkpoint tokens; coverage is checked later. */
  static HeapSnapshot snapshot(Path directory, long run, long checkpoint) throws IOException {
    Path file = directory.resolve("snapshots/" + checkpoint + ".gsnap");
    if (Files.size(file) > SnapshotCodec.MAX_BYTES)
      throw new IOException("Snapshot exceeds byte limit");
    return SnapshotCodec.read(Files.readAllBytes(file), run, checkpoint);
  }

  /**
   * Hashes the current configured installation/support files, recording absent paths as missing.
   */
  static Map<String, String> installations(TargetProfile profile) throws IOException {
    var paths = new LinkedHashMap<String, Path>();
    paths.put("java", Path.of(profile.java()));
    paths.put("runtime", Path.of(profile.support().runtime()));
    paths.put("protocol", Path.of(profile.support().protocol()));
    paths.put("observer", Path.of(profile.support().observer()));
    if (profile.support().whitebox() != null)
      paths.put("whitebox", Path.of(profile.support().whitebox()));
    if (Files.exists(Path.of(profile.java()))) {
      Path home = Path.of(profile.java()).toRealPath().getParent().getParent();
      paths.putAll(VmDriver.installations(profile, home));
    }
    var hashes = new LinkedHashMap<String, String>();
    for (var entry : paths.entrySet())
      hashes.put(
          entry.getKey(),
          Files.isRegularFile(entry.getValue()) ? Artifacts.hash(entry.getValue()) : "missing");
    return hashes;
  }

  static String classpath(Path directory, boolean generated) {
    var paths = new ArrayList<String>();
    if (generated) paths.add(directory.resolve("generated/classes").toString());
    paths.add(directory.resolve("support/runtime.jar").toString());
    paths.add(directory.resolve("support/protocol.jar").toString());
    return String.join(File.pathSeparator, paths);
  }

  /** Bundles the configured libraries and rejects support that shadows case/runtime classes. */
  static void copySupport(Path directory, TargetProfile profile) throws IOException {
    Files.copy(Path.of(profile.support().runtime()), directory.resolve("support/runtime.jar"));
    Files.copy(Path.of(profile.support().protocol()), directory.resolve("support/protocol.jar"));
    Files.copy(Path.of(profile.support().observer()), directory.resolve("support/" + OBSERVER));

    if (profile.support().whitebox() != null) {
      try (var jar = new JarFile(profile.support().whitebox())) {
        if (jar.stream()
            .anyMatch(
                entry ->
                    entry.getName().equals("Case.class")
                        || entry.getName().startsWith("Case$")
                        || entry.getName().startsWith("io/connglli/gorrect/")))
          throw new IOException("WhiteBox client shadows generated or runtime classes");
      }
      Files.copy(Path.of(profile.support().whitebox()), directory.resolve("support/whitebox.jar"));
    }
  }

  /**
   * Copies generated/support children into a newly initialized bundle, replacing its empty class
   * directory. The caller checks the copied inventory before launch and retains partial copies on
   * I/O failure for diagnosis.
   */
  static void restoreExecutable(Path saved, Path directory) throws IOException {
    // Output directories exist, so copy children rather than replacing the run directory.
    for (String name : new String[] {"generated", "support"}) {
      try (var children = Files.list(saved.resolve(name))) {
        for (var child : children.toList()) {
          Path target = directory.resolve(name).resolve(child.getFileName());
          if (Files.isDirectory(target)) Files.delete(target);
          Artifacts.copyTree(child, target);
        }
      }
    }
  }
}
