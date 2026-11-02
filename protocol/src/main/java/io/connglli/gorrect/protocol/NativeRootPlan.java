package io.connglli.gorrect.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Source-selected native names in zero-based slot order, without expected targets or payloads. */
public record NativeRootPlan(List<String> roots) {
  public static final int MAX_SLOTS = 64;
  public static final int MAX_BYTES = 65_536;
  // Default GIR step bound: each non-null write consumes one step and one acquisition.
  public static final long MAX_GENERATIONS = 100_000;

  public NativeRootPlan {
    roots = List.copyOf(roots);
    if (roots.isEmpty()
        || roots.size() > MAX_SLOTS
        || new HashSet<>(roots).size() != roots.size()
        || roots.stream()
            .anyMatch(name -> !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.equals("null")))
      throw new IllegalArgumentException("Invalid native root plan");
  }

  private String text() {
    return "version=1\nroots=" + String.join(",", roots) + "\n";
  }

  public void write(Path path) throws IOException {
    String text = text();
    if (text.length() > MAX_BYTES) throw new IOException("Native root plan exceeds byte limit");
    Files.writeString(path, text, StandardCharsets.US_ASCII);
  }

  public static NativeRootPlan read(Path path) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("Native root plan exceeds byte limit");
    String text = Files.readString(path, StandardCharsets.US_ASCII);
    try {
      String prefix = "version=1\nroots=";
      if (!text.startsWith(prefix) || !text.endsWith("\n"))
        throw new IllegalArgumentException("Invalid native root plan framing");
      var plan =
          new NativeRootPlan(
              Arrays.asList(text.substring(prefix.length(), text.length() - 1).split(",", -1)));
      if (!plan.text().equals(text))
        throw new IllegalArgumentException("Noncanonical native root plan");
      return plan;
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }
}
