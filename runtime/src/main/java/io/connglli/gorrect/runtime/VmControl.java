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
interface VmControl extends AutoCloseable {
  List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException;

  void witness(int worker, int group, long round, int phase, int point, long collection);

  void collect(int operation, int worker, int group, long round, int phase, long collection);

  String collectionControl();

  String gcProblem();

  boolean collectionEvidence();

  String problem();

  void finish() throws IOException;

  @Override
  void close();

  static VmControl open(Path directory, long run) throws IOException {
    Path path = directory.resolve("generated/jit.properties");
    Properties settings = null;
    if (Files.exists(path)) {
      settings = new Properties();
      try (var input = Files.newInputStream(path)) {
        settings.load(input);
      }
    }
    var gc = new Properties();
    Path gcPath = directory.resolve("generated/gc.properties");
    if (Files.exists(gcPath))
      try (var input = Files.newInputStream(gcPath)) {
        gc.load(input);
      }
    String control = gc.getProperty("control", settings == null ? "system" : "whitebox-full");
    boolean evidence = Boolean.parseBoolean(gc.getProperty("evidence", "false"));
    if (settings == null && control.equals("system") && !evidence)
      return disabled("JIT control is not configured", control, false);
    try {
      return new HotSpotControl(directory, run, settings, control, evidence);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
      return disabled("VM control unavailable: " + failure, control, evidence);
    }
  }

  private static VmControl disabled(String reason, String control, boolean evidence) {
    return new VmControl() {
      public List<ExecutionEvidence.Compilation> prepare(
          long round, int[] workers, Method[] methods) {
        return List.of();
      }

      public void witness(
          int worker, int group, long round, int phase, int point, long collection) {}

      public void collect(
          int operation, int worker, int group, long round, int phase, long collection) {
        if (!control.equals("system")) throw new UnsupportedOperationException(reason);
        System.gc();
      }

      public String collectionControl() {
        return control;
      }

      public String gcProblem() {
        return !control.equals("system") || evidence ? reason : "";
      }

      public boolean collectionEvidence() {
        return false;
      }

      public String problem() {
        return reason;
      }

      public void finish() {}

      public void close() {}
    };
  }
}
