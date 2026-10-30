package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shared process environment, deadline, and termination rules. */
final class ProcessSupport {
  private ProcessSupport() {}

  /**
   * Removes ambient Java-option/classpath injection, then applies already validated profile values.
   */
  static void cleanEnvironment(ProcessBuilder builder, Map<String, String> additions) {
    builder
        .environment()
        .keySet()
        .removeIf(
            name -> TargetProfile.ENVIRONMENT_OPTIONS.contains(name.toUpperCase(Locale.ROOT)));
    builder.environment().putAll(additions);
  }

  /** Converts a target nanoTime deadline to a positive, int-bounded wait or throws on expiry. */
  static int milliseconds(long deadline) throws SocketTimeoutException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining <= 0) throw new SocketTimeoutException("Run deadline exceeded");
    return (int) Math.min(remaining, Integer.MAX_VALUE);
  }

  /**
   * Stops descendants and the parent, bounds the cleanup wait, and preserves caller interruption.
   */
  static void terminate(Process process) {
    if (!process.isAlive()) return;
    process.descendants().forEach(child -> child.destroyForcibly());
    process.destroy();
    try {
      if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
