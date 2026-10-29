package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/** WhiteBox requests compilation; JFR records actual generated-method frames at witness points. */
final class HotSpotControl implements JitControl {
  @Name("gorrect.WorkerExecution")
  @StackTrace(true)
  static final class Witness extends Event {
    public long run, round, collection;
    public int version = ExecutionEvidence.FRAME_VERSION;
    public int worker, group, phase, point;
  }

  private final Object whitebox;
  private final Method dontInline, enqueue, compilationLevel, fullGC;
  private final int level, warmup, timeout;
  private final long run;
  private final Path output;
  private final Recording recording;
  private boolean finished;

  HotSpotControl(Path directory, long run, Properties settings)
      throws ReflectiveOperationException {
    this.run = run;
    output = directory.resolve("traces/workers.jfr");
    level = Integer.parseInt(settings.getProperty("level"));
    warmup = Integer.parseInt(settings.getProperty("warmupRounds"));
    timeout = Integer.parseInt(settings.getProperty("deadlineMillis"));
    Class<?> type;
    try {
      type = Class.forName("jdk.test.whitebox.WhiteBox", true, null);
    } catch (ClassNotFoundException missing) {
      type = Class.forName("sun.hotspot.WhiteBox", true, null);
    }
    whitebox = type.getMethod("getWhiteBox").invoke(null);
    dontInline = type.getMethod("testSetDontInlineMethod", Executable.class, boolean.class);
    enqueue = type.getMethod("enqueueMethodForCompilation", Executable.class, int.class);
    compilationLevel = type.getMethod("getMethodCompilationLevel", Executable.class);
    fullGC = type.getMethod("fullGC");
    recording = new Recording();
    try {
      recording.setMaxSize(8L * 1024 * 1024);
      recording.enable(Witness.class).withThreshold(Duration.ZERO).withStackTrace();
      recording.start();
    } catch (RuntimeException | Error failure) {
      recording.close();
      throw failure;
    }
  }

  @Override
  public List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException {
    var result = new ArrayList<ExecutionEvidence.Compilation>();
    var requested = new boolean[methods.length];
    var problems = new String[methods.length];
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    for (int index = 0; index < methods.length; index++) {
      problems[index] = "";
      try {
        dontInline.invoke(whitebox, methods[index], true);
        if (round > warmup) {
          // Reuse installed code; an unnecessary enqueue can be rejected despite usable code.
          int existing = ((Number) compilationLevel.invoke(whitebox, methods[index])).intValue();
          requested[index] =
              existing >= level
                  || (System.nanoTime() < deadline
                      && (boolean) enqueue.invoke(whitebox, methods[index], level));
          if (!requested[index]) problems[index] = "Compilation request rejected";
        }
      } catch (ReflectiveOperationException | LinkageError failure) {
        problems[index] = failure.toString();
      }
    }
    if (round <= warmup) return List.of();
    int[] installed = new int[methods.length];
    while (true) {
      boolean pending = false;
      for (int index = 0; index < methods.length; index++) {
        try {
          installed[index] =
              ((Number) compilationLevel.invoke(whitebox, methods[index])).intValue();
        } catch (ReflectiveOperationException | LinkageError failure) {
          problems[index] = failure.toString();
        }
        if (requested[index] && installed[index] < level && problems[index].isEmpty())
          pending = true;
      }
      if (!pending || System.nanoTime() >= deadline) break;
      Thread.sleep(1);
    }
    for (int index = 0; index < methods.length; index++) {
      if (requested[index] && installed[index] < level && problems[index].isEmpty())
        problems[index] = "Compilation deadline exceeded";
      result.add(
          new ExecutionEvidence.Compilation(
              workers[index], methods[index].getName(), level, installed[index], problems[index]));
    }
    return result;
  }

  @Override
  public void witness(int worker, int group, long round, int phase, int point, long collection) {
    var event = new Witness();
    event.run = run;
    event.worker = worker;
    event.group = group;
    event.round = round;
    event.phase = phase;
    event.point = point;
    event.collection = collection;
    event.commit();
  }

  @Override
  public void collect() {
    try {
      fullGC.invoke(whitebox);
    } catch (IllegalAccessException | InvocationTargetException failure) {
      throw new IllegalStateException("WhiteBox collection failed", failure);
    }
  }

  @Override
  public String problem() {
    return "";
  }

  @Override
  public void finish() throws IOException {
    if (!finished) {
      recording.stop();
      recording.dump(output);
      finished = true;
    }
  }

  @Override
  public void close() {
    recording.close();
  }
}
