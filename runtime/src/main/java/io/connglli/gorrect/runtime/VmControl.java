package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * One target process's VM controls and evidence capture. The session calls {@link #prepare} before
 * starting a worker group, then {@link #finish} after all workers have joined, and always closes
 * the bridge. Workers may call {@link #witness} and {@link #collect} concurrently.
 *
 * <p>Implementations may retain method metadata and primitive observations, but no workload object
 * references. Installation, executing frames, and completed GC cycles are separate evidence.
 */
interface VmControl extends AutoCloseable {
  /** Typed host settings; profile validation owns VM-specific levels and numeric bounds. */
  record JitSettings(int level, int warmupRounds, int deadlineMillis) {
    static JitSettings parse(Properties properties) {
      return new JitSettings(
          Integer.parseInt(properties.getProperty("level")),
          Integer.parseInt(properties.getProperty("warmupRounds")),
          Integer.parseInt(properties.getProperty("deadlineMillis")));
    }
  }

  /**
   * Prepares methods for a one-based visit to a parallel block without invoking them. Worker IDs
   * and methods are aligned arrays; each method is the generated entry point for that worker.
   *
   * <p>Returns installation results, or an empty list during modeled warm-up or when JIT controls
   * are absent. A failed request remains an installation result, not a graph verdict. Interruption
   * cancels preparation and propagates to the session.
   */
  List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException;

  /**
   * Captures the calling worker's actual stack at an {@link ExecutionEvidence} witness point. Group
   * is the parallel instruction ID; round is its one-based visit and phase is zero-based.
   * Collection is zero at entry/exit and the worker's one-based request ordinal otherwise.
   *
   * <p>A witness label alone establishes no compiled execution. Disabled capture is a no-op;
   * exhausted or failed capture may abort the case rather than claim complete evidence.
   */
  void witness(int worker, int group, long round, int phase, int point, long collection);

  /**
   * Requests collection for a GIR instruction under the configured policy. Main-thread requests use
   * zero worker/group/round/phase; worker requests use their current visit and phase. Collection is
   * the caller's one-based request ordinal.
   *
   * <p>Unavailable explicit controls fail without fallback. Normal return establishes only that the
   * request returned; independent VM events must establish a completed cycle.
   */
  void collect(int operation, int worker, int group, long round, int phase, long collection);

  /** The selected request policy, retained even when it is unavailable. */
  String collectionControl();

  /** Empty when configured GC control/capture is usable, otherwise its startup failure reason. */
  String gcProblem();

  /** Whether independent GC-cycle capture was initialized; requests still need completion proof. */
  boolean collectionEvidence();

  /** Empty when JIT controls are configured and usable, otherwise their startup failure reason. */
  String problem();

  /** Stops and persists VM witnesses after worker joins; failure can leave incomplete evidence. */
  void finish() throws IOException;

  /** Releases capture resources. Call {@link #finish} first to persist successful evidence. */
  @Override
  void close();

  /**
   * Loads this bundle's control settings before graph construction. Backend discovery failures
   * become an unavailable bridge whose diagnostic methods explain the gap; settings-file I/O
   * failures propagate. The caller owns the returned bridge through session shutdown.
   */
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
      var jit = settings == null ? null : JitSettings.parse(settings);
      if (VmFacts.isOpenJ9()) return new OpenJ9Control(directory, run, jit, control);
      return new HotSpotControl(directory, run, jit, control, evidence);
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
