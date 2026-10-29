package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Target-specific controls and witness capture; graph operations do not depend on their success.
 */
interface JitControl extends AutoCloseable {
  List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException;

  void witness(int worker, int group, long round, int phase, int point, long collection);

  void collect();

  String problem();

  void finish() throws IOException;

  @Override
  void close();

  static JitControl open(Path directory, long run) throws IOException {
    Path path = directory.resolve("generated/jit.properties");
    if (!Files.exists(path)) return disabled("JIT control is not configured");
    var settings = new Properties();
    try (var input = Files.newInputStream(path)) {
      settings.load(input);
    }
    try {
      return new HotSpotControl(directory, run, settings);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
      return disabled("JIT control unavailable: " + failure);
    }
  }

  private static JitControl disabled(String reason) {
    return new JitControl() {
      public List<ExecutionEvidence.Compilation> prepare(
          long round, int[] workers, Method[] methods) {
        return List.of();
      }

      public void witness(
          int worker, int group, long round, int phase, int point, long collection) {}

      public void collect() {
        System.gc();
      }

      public String problem() {
        return reason;
      }

      public void finish() {}

      public void close() {}
    };
  }
}
