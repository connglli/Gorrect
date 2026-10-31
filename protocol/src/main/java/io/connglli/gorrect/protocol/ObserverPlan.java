package io.connglli.gorrect.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Hashed internal provider selection. Canonical framing rejects hidden duplicate properties. */
public record ObserverPlan(String bindingMetadata) {
  public ObserverPlan {
    if (!Set.of("jvmti", "retained-class").contains(bindingMetadata))
      throw new IllegalArgumentException("Unknown binding metadata provider");
  }

  /** Replaces the file with the complete version 1 selection, without expected graph values. */
  public void write(Path path) throws IOException {
    Files.writeString(
        path, "version=1\nbindingMetadata=" + bindingMetadata + "\n", StandardCharsets.US_ASCII);
  }

  /** Reads only the emitted bounded format; unknown, duplicate or conflicting fields are errors. */
  public static ObserverPlan read(Path path) throws IOException {
    if (Files.size(path) > 128) throw new IOException("Observer plan exceeds byte limit");
    String text = Files.readString(path, StandardCharsets.US_ASCII);
    for (String provider : Set.of("jvmti", "retained-class"))
      if (text.equals("version=1\nbindingMetadata=" + provider + "\n"))
        return new ObserverPlan(provider);
    throw new IOException("Invalid observer plan");
  }
}
