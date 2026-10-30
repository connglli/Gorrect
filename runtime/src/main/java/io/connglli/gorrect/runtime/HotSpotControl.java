package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.CollectionEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/** HotSpot requests use optional WhiteBox APIs; JFR records VM cycles and actual worker frames. */
final class HotSpotControl implements VmControl {
  @Name("gorrect.WorkerExecution")
  @StackTrace(true)
  static final class Witness extends Event {
    public long run, round, collection;
    public int version = ExecutionEvidence.FRAME_VERSION;
    public int worker, group, phase, point;
  }

  @Name(CollectionEvidence.REQUEST_EVENT)
  @StackTrace(false)
  static final class CollectionRequest extends Event {
    public int version = CollectionEvidence.VERSION;
    public long run, round, collection;
    public int worker, group, phase, operation;
    public String control;
    public boolean returned;
  }

  private final boolean jitConfigured;
  private final String gcControl;
  private final Object whitebox;
  private final Method dontInline, enqueue, compilationLevel, fullGC;
  private final int level, warmup, timeout;
  private final long run;
  private final Path output;
  private final Recording recording;
  private boolean finished;

  HotSpotControl(
      Path directory,
      long run,
      VmControl.JitSettings settings,
      String gcControl,
      boolean gcEvidence)
      throws ReflectiveOperationException {
    this.run = run;
    this.gcControl = gcControl;
    jitConfigured = settings != null;
    output = directory.resolve("traces/workers.jfr");
    level = settings == null ? 0 : settings.level();
    warmup = settings == null ? 0 : settings.warmupRounds();
    timeout = settings == null ? 0 : settings.deadlineMillis();

    Class<?> type = null;
    if (jitConfigured || gcControl.equals("whitebox-full")) {
      try {
        type = Class.forName("jdk.test.whitebox.WhiteBox", true, null);
      } catch (ClassNotFoundException missing) {
        type = Class.forName("sun.hotspot.WhiteBox", true, null);
      }
    }

    whitebox = type == null ? null : type.getMethod("getWhiteBox").invoke(null);
    dontInline =
        jitConfigured
            ? type.getMethod("testSetDontInlineMethod", Executable.class, boolean.class)
            : null;
    enqueue =
        jitConfigured
            ? type.getMethod("enqueueMethodForCompilation", Executable.class, int.class)
            : null;
    compilationLevel =
        jitConfigured ? type.getMethod("getMethodCompilationLevel", Executable.class) : null;
    fullGC = gcControl.equals("whitebox-full") ? type.getMethod("fullGC") : null;

    // A declared native method need not be registered by this client/VM pair. Probe before any
    // workload exists; its cycle is outside every modeled request and cannot satisfy coverage.
    if (fullGC != null) fullGC.invoke(whitebox);

    // Request policy is usable independently of evidence collection and compiler control.
    if (!jitConfigured && !gcEvidence) {
      recording = null;
      return;
    }

    if (!FlightRecorder.isAvailable()) throw new IllegalStateException("JFR unavailable");

    recording = new Recording();
    try {
      if (FlightRecorder.getFlightRecorder().getEventTypes().stream()
          .noneMatch(event -> event.getName().equals("jdk.GarbageCollection")))
        throw new IllegalStateException("JFR GC events unavailable");
      recording.setMaxSize(8L * 1024 * 1024);
      if (jitConfigured)
        recording.enable(Witness.class).withThreshold(Duration.ZERO).withStackTrace();
      recording.enable(CollectionRequest.class).withThreshold(Duration.ZERO);
      recording.enable("jdk.GarbageCollection").withThreshold(Duration.ZERO);
      recording.enable("jdk.JVMInformation").with("period", "beginChunk");
      recording.start();
    } catch (RuntimeException | Error failure) {
      recording.close();
      throw failure;
    }
  }

  @Override
  public List<ExecutionEvidence.Compilation> prepare(long round, int[] workers, Method[] methods)
      throws InterruptedException {
    if (!jitConfigured) return List.of();

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
    if (!jitConfigured) return;
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
  public void collect(
      int operation, int worker, int group, long round, int phase, long collection) {
    if (recording == null) {
      requestCollection();
      return;
    }

    var event = new CollectionRequest();
    event.run = run;
    event.worker = worker;
    event.group = group;
    event.round = round;
    event.phase = phase;
    event.operation = operation;
    event.collection = collection;
    event.control = gcControl;

    event.begin();

    try {
      requestCollection();
      event.returned = true;
    } finally {
      event.end();
      event.commit();
    }
  }

  private void requestCollection() {
    try {
      if (fullGC == null) System.gc();
      else fullGC.invoke(whitebox);
    } catch (IllegalAccessException | InvocationTargetException failure) {
      throw new IllegalStateException("WhiteBox collection failed", failure);
    }
  }

  @Override
  public String collectionControl() {
    return gcControl;
  }

  @Override
  public String gcProblem() {
    return "";
  }

  @Override
  public boolean collectionEvidence() {
    return recording != null;
  }

  @Override
  public String problem() {
    return jitConfigured ? "" : "JIT control is not configured";
  }

  @Override
  public void finish() throws IOException {
    if (recording != null && !finished) {
      recording.stop();
      recording.dump(output);
      finished = true;
    }
  }

  @Override
  public void close() {
    if (recording != null) recording.close();
  }
}
