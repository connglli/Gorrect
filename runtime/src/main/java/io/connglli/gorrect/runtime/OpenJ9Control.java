package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.StackEvidence;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Launch policy requests compilation; native VM frames independently witness actual calls. */
final class OpenJ9Control implements VmControl {
  private final Path output;
  private final long run;
  private final boolean configured;
  private final int warmup, timeout;
  private final String control, problem;
  private final List<StackEvidence.Witness> witnesses = new ArrayList<>();
  private int frameCount;
  private long characters;

  OpenJ9Control(Path directory, long run, Properties settings, String control) {
    output = directory.resolve("traces/workers.gstacks");
    this.run = run;
    this.control = control;
    configured = settings != null;
    warmup = settings == null ? 0 : Integer.parseInt(settings.getProperty("warmupRounds"));
    timeout = settings == null ? 0 : Integer.parseInt(settings.getProperty("deadlineMillis"));
    // Exercise the extension before graph construction; discovery alone does not establish
    // usability.
    problem =
        !configured
            ? "JIT control is not configured"
            : NativeObserver.openJ9Stack() == null ? "OpenJ9 extended stacks unavailable" : "";
  }

  @Override
  public List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException {
    if (!configured || round <= warmup) return List.of();
    var result = new ArrayList<ExecutionEvidence.Compilation>();
    var installed = new boolean[methods.length];
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    do {
      boolean pending = false;
      for (int index = 0; index < methods.length; index++) {
        installed[index] = NativeObserver.hasCompiledCode(methods[index]);
        pending |= !installed[index];
      }
      if (!pending || System.nanoTime() >= deadline) break;
      Thread.sleep(1);
    } while (true);
    for (int index = 0; index < methods.length; index++)
      result.add(
          new ExecutionEvidence.Compilation(
              workers[index],
              methods[index].getName(),
              1,
              installed[index] ? 1 : 0,
              installed[index] ? "" : "No compiled code after modeled warm-up"));
    return result;
  }

  @Override
  public void witness(int worker, int group, long round, int phase, int point, long collection) {
    if (!configured || !problem.isEmpty()) return;
    String[] raw = NativeObserver.openJ9Stack();
    if (raw == null || raw.length % 4 != 0)
      throw new IllegalStateException("OpenJ9 stack capture failed");
    var frames = new ArrayList<StackEvidence.Frame>();
    long size = 0;
    for (int index = 0; index < raw.length; index += 4) {
      frames.add(
          new StackEvidence.Frame(
              raw[index], raw[index + 1], raw[index + 2], Integer.parseInt(raw[index + 3])));
      size += raw[index].length() + raw[index + 1].length() + raw[index + 2].length();
    }
    synchronized (witnesses) {
      if (witnesses.size() >= StackEvidence.MAX_WITNESSES
          || frameCount + frames.size() > StackEvidence.MAX_FRAMES
          || characters + size > 8L * 1024 * 1024)
        throw new IllegalStateException("OpenJ9 stack evidence buffer exhausted");
      frameCount += frames.size();
      characters += size;
      witnesses.add(
          new StackEvidence.Witness(
              worker,
              group,
              round,
              phase,
              point,
              collection,
              Thread.currentThread().getId(),
              frames));
    }
  }

  @Override
  public void collect(
      int operation, int worker, int group, long round, int phase, long collection) {
    if (!control.equals("system")) throw new UnsupportedOperationException(gcProblem());
    System.gc();
  }

  @Override
  public String collectionControl() {
    return control;
  }

  @Override
  public String gcProblem() {
    if (!control.equals("system")) return "OpenJ9 supports system GC control only";
    return "";
  }

  @Override
  public boolean collectionEvidence() {
    return false;
  }

  @Override
  public String problem() {
    return problem;
  }

  @Override
  public void finish() throws IOException {
    if (configured)
      new StackEvidence(
              run,
              ProcessHandle.current().pid(),
              ManagementFactory.getRuntimeMXBean().getStartTime(),
              witnesses)
          .write(output);
  }

  @Override
  public void close() {}
}
