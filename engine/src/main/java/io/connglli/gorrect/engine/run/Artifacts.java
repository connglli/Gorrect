package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.JsonFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bundle provenance covers inputs and executables; observed values remain verifier evidence. */
final class Artifacts {
  private Artifacts() {}

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

  static void check(Path directory, com.google.gson.JsonObject manifest) throws IOException {
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
        .equals(
            JsonFiles.GSON.fromJson(
                hashes, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType())))
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
}
