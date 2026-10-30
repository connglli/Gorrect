package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;

/** One target process's endpoint and bounded scalar recorder. It owns no workload references. */
public final class TargetSession implements AutoCloseable, ObservationRecorder {
  private final SocketChannel socket;
  private final DataInputStream in;
  private final DataOutputStream out;
  private final long run;
  private final Path directory;
  private final int records;
  private final Map<Integer, TraceRecorder> recorders = new TreeMap<>();
  private final List<ExecutionEvidence.Group> groups = new ArrayList<>();
  private final List<ExecutionEvidence.Window> windows = new ArrayList<>();
  private VmControl control;
  private final boolean referenceRecording;
  private final Map<Integer, Long> visits = new HashMap<>();
  private long checkpoint;
  private long collections;
  private boolean complete;

  private TargetSession(String[] args) throws IOException {
    if (args.length != 6) throw new IllegalArgumentException("Invalid target launch arguments");
    run = Long.parseLong(args[1]);
    directory = Path.of(args[2]);
    records = Integer.parseInt(args[4]);
    int capacity = Integer.parseInt(args[5]);
    if (run <= 0 || capacity <= 0 || capacity > ExecutionTrace.MAX_EVENTS)
      throw new IllegalArgumentException("Invalid trace capacity or run token");
    referenceRecording = Files.exists(directory.resolve("generated/references.properties"));
    recorders(capacity);
    socket = SocketChannel.open(UnixDomainSocketAddress.of(args[0]));
    in = new DataInputStream(Channels.newInputStream(socket));
    out = new DataOutputStream(Channels.newOutputStream(socket));
    try {
      System.load(args[3]);
      control = VmControl.open(directory, run);
      var properties = HotSpotFacts.read();
      properties.setProperty("main.thread", Long.toString(Thread.currentThread().getId()));
      properties.setProperty("observer.capabilities", Long.toString(NativeObserver.capabilities()));
      properties.setProperty("jit.problem", control.problem());
      properties.setProperty("gc.control", control.collectionControl());
      properties.setProperty("gc.problem", control.gcProblem());
      properties.setProperty("gc.evidence", Boolean.toString(control.collectionEvidence()));
      try (var stream = Files.newOutputStream(directory.resolve("target.properties"))) {
        properties.store(stream, "Target identity");
      }
      send(ControlMessage.Kind.HELLO, 0, 0, "");
      acknowledge(0);
    } catch (Throwable failure) {
      if (control != null) control.close();
      socket.close();
      throw failure;
    }
  }

  public static TargetSession open(String[] args) throws IOException {
    return new TargetSession(args);
  }

  /**
   * Records without allocation or serialization. Kind is an ExecutionTrace.Kind ordinal; exhaustion
   * stops the case before it can claim a complete trace.
   */
  public void record(int operation, int kind, long value) {
    recorders.get(0).record(operation, kind, value, 0, 0, 0);
  }

  /** Main-thread observations use recorder zero; worker contexts write to their own recorders. */
  public void reference(
      int operation,
      int kind,
      long value,
      int strength,
      int wrapperWorker,
      int wrapperSite,
      long wrapperOccurrence,
      int wrapperSlot,
      int targetWorker,
      int targetSite,
      long targetOccurrence) {
    recorders
        .get(0)
        .reference(
            operation,
            kind,
            value,
            strength,
            wrapperWorker,
            wrapperSite,
            wrapperOccurrence,
            wrapperSlot,
            targetWorker,
            targetSite,
            targetOccurrence,
            0,
            0,
            0);
  }

  private void recorders(int capacity) throws IOException {
    var bounds = new Properties();
    Path plan = directory.resolve("generated/recorders.properties");
    if (Files.exists(plan))
      try (var input = Files.newInputStream(plan)) {
        bounds.load(input);
      }
    else bounds.setProperty("0", Integer.toString(capacity));
    int remaining = capacity;
    var ids = bounds.stringPropertyNames().stream().map(Integer::parseInt).sorted().toList();
    if (!ids.contains(0)) throw new IOException("Recorder plan omits the main worker");
    // Counts only size storage; writeTrace emits actual appends, never unused or predicted records.
    for (int id : ids) {
      int requested = Integer.parseInt(bounds.getProperty(Integer.toString(id)));
      if (id < 0 || id > 64 || requested < 0 || requested > ExecutionTrace.MAX_EVENTS)
        throw new IOException("Invalid recorder bound");
      int size = Math.min(requested, remaining);
      remaining -= size;
      recorders.put(id, new TraceRecorder(id, size, referenceRecording));
    }
  }

  @FunctionalInterface
  public interface WorkerTask {
    void run(WorkerContext worker) throws Exception;
  }

  /**
   * Starts isolated worker frames, publishes construction through Thread.start, and joins peers.
   */
  public void parallel(int operation, int[] ids, WorkerTask[] tasks, Class<?> owner)
      throws Exception {
    if (ids.length != tasks.length || ids.length == 0 || ids.length > 16)
      throw new IllegalArgumentException("Invalid worker group");
    long round = visits.merge(operation, 1L, Long::sum);
    Method[] methods = new Method[ids.length];
    for (int index = 0; index < ids.length; index++)
      methods[index] =
          owner.getDeclaredMethod(
              ExecutionEvidence.workerMethod(ids[index]), TargetSession.class, WorkerContext.class);
    var compilations = control.prepare(round, ids, methods);
    var phaser = new Phaser(ids.length);
    var failure = new AtomicReference<Throwable>();
    var contexts = new ArrayList<WorkerContext>();
    var threads = new ArrayList<Thread>();
    for (int index = 0; index < ids.length; index++) {
      int id = ids[index];
      var recorder = recorders.get(id);
      if (recorder == null) throw new IllegalStateException("Missing recorder for worker " + id);
      var context = new WorkerContext(recorder, phaser, control, id, operation, round);
      contexts.add(context);
      var task = tasks[index];
      threads.add(
          new Thread(
              () -> {
                try {
                  task.run(context);
                  if (!context.ended())
                    throw new IllegalStateException("Worker omitted completion");
                } catch (Throwable error) {
                  failure.compareAndSet(null, error);
                  phaser.forceTermination();
                }
              },
              "gorrect-worker-" + id + "-" + round));
    }
    long gcBefore = NativeObserver.gcFinishes();
    boolean joined = false;
    try {
      for (var thread : threads) thread.start();
      for (int index = 0; index < threads.size(); index++) {
        threads.get(index).join();
        record(operation, ExecutionTrace.Kind.JOIN.ordinal(), ids[index]);
      }
      joined = failure.get() == null;
    } catch (InterruptedException | RuntimeException | Error error) {
      phaser.forceTermination();
      threads.forEach(Thread::interrupt);
      joinCancelled(threads);
      throw error;
    } finally {
      long gcAfter = NativeObserver.gcFinishes();
      groups.add(
          new ExecutionEvidence.Group(
              operation,
              round,
              java.util.Arrays.stream(ids).boxed().toList(),
              joined,
              gcBefore,
              gcAfter,
              compilations));
      contexts.forEach(context -> windows.addAll(context.windows()));
    }
    if (failure.get() instanceof Exception error) throw error;
    if (failure.get() instanceof Error error) throw error;
  }

  private static void joinCancelled(List<Thread> threads) {
    boolean interrupted = Thread.interrupted();
    for (var thread : threads)
      while (thread.isAlive()) {
        try {
          thread.join();
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
    if (interrupted) Thread.currentThread().interrupt();
  }

  /** A collection control point receives no graph object arguments. */
  public void collect(int operation) {
    control.collect(operation, 0, 0, 0, 0, ++collections);
  }

  /** Call at a named-root checkpoint; generated code must have no live GIR reference bindings. */
  public void checkpoint(int operation) throws IOException {
    long occurrence = visits.merge(operation, 1L, Long::sum);
    checkpoint++;
    byte[] bytes = NativeObserver.capture(run, checkpoint, records);
    Files.write(directory.resolve("snapshots").resolve(checkpoint + ".gsnap"), bytes);
    send(ControlMessage.Kind.CHECKPOINT, operation, occurrence, "");
    acknowledge(checkpoint);
  }

  public void complete() throws IOException {
    writeTrace(true);
    control.finish();
    send(ControlMessage.Kind.COMPLETE, 0, 0, "");
    complete = true;
  }

  public void fail(Throwable failure) throws IOException {
    writeTrace(false);
    control.finish();
    // Bound failure text to the modified-UTF length used by control frames.
    String detail = failure.getClass().getName() + ": " + failure.getMessage();
    send(ControlMessage.Kind.FAILED, 0, 0, detail.substring(0, Math.min(detail.length(), 4000)));
  }

  private void writeTrace(boolean finished) throws IOException {
    var events = new ArrayList<ExecutionTrace.Event>();
    recorders.values().forEach(recorder -> recorder.appendTo(events));
    new ExecutionTrace(run, finished, events).write(directory.resolve("traces/execution.gtrace"));
    if (referenceRecording) {
      var references = new ArrayList<ReferenceHistory.Event>();
      recorders.values().forEach(recorder -> recorder.appendReferences(references));
      new ReferenceHistory(run, finished, references)
          .write(directory.resolve("traces/references.ghistory"));
    }
    new ExecutionEvidence(run, finished, groups, windows)
        .write(directory.resolve("traces/workers.gevidence"));
  }

  private void send(ControlMessage.Kind kind, int operation, long occurrence, String detail)
      throws IOException {
    new ControlMessage(kind, run, checkpoint, operation, occurrence, detail).write(out);
  }

  private void acknowledge(long sequence) throws IOException {
    var message = ControlMessage.read(in, run);
    if (message.kind() != ControlMessage.Kind.ACK || message.sequence() != sequence)
      throw new IOException("Unexpected engine acknowledgement");
  }

  @Override
  public void close() throws IOException {
    try {
      if (!complete) writeTrace(false);
    } finally {
      if (control != null) control.close();
      socket.close();
    }
  }
}
