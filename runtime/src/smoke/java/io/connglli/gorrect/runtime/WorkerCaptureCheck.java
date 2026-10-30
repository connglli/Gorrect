package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CaptureEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Focused runtime/protocol checks without a native observer or an external test dependency. */
public final class WorkerCaptureCheck {
  private WorkerCaptureCheck() {}

  public static void main(String[] args) throws Exception {
    pausedCapture();
    mismatchedCuts(false);
    mismatchedCuts(true);
    omittedCompletion();
    failedWriter();
    captureCancellation();
    callerInterruption();
    workerInterruption();
    evidence();
  }

  private static void pausedCapture() throws Exception {
    var reached = new CountDownLatch(1);
    var resumed = new AtomicInteger();
    var harness = new Harness(2, 4);
    harness.start(
        worker -> {
          worker.begin();
          worker.binding(11, true);
          reached.countDown();
          worker.checkpoint(21);
          resumed.incrementAndGet();
          worker.barrier(31);
          worker.checkpoint(41);
          worker.end();
        },
        worker -> {
          worker.begin();
          reached.await();
          worker.binding(12, false);
          worker.checkpoint(22);
          resumed.incrementAndGet();
          worker.barrier(32);
          worker.checkpoint(42);
          worker.end();
        });
    int captures = 0;
    try {
      while (true) {
        int kind = harness.rendezvous.await();
        if (kind == WorkerRendezvous.CAPTURE) {
          if (++captures == 1) {
            check(resumed.get() == 0, "Worker resumed during capture");
            check(
                harness.contexts.get(0).bindings().equals(List.of(binding(11, true))),
                "Lost worker binding");
            check(
                harness.contexts.get(1).bindings().equals(List.of(binding(12, false))),
                "Lost peer binding");
            check(harness.rendezvous.phase() == 0, "Wrong initial capture phase");
          } else {
            check(
                resumed.get() == 2 && harness.rendezvous.phase() == 2, "Cut did not advance phase");
            check(
                harness.contexts.stream().allMatch(context -> context.bindings().isEmpty()),
                "Stale worker bindings");
          }
          check(
              harness.rendezvous.thread(0) != harness.rendezvous.thread(1),
              "Duplicate worker threads");
        }
        harness.rendezvous.release();
        if (kind == WorkerRendezvous.END) break;
      }
      harness.join();
    } finally {
      harness.close();
    }
    check(captures == 2 && harness.failure.get() == null, "Coordinated workers failed");
    var events = new ArrayList<ExecutionTrace.Event>();
    harness.recorders.get(0).appendTo(events);
    check(
        events.stream().filter(event -> event.kind() == ExecutionTrace.Kind.PHASE_START).count()
            == 3,
        "Missing resumed phase markers");
    check(
        harness.contexts.stream().allMatch(context -> context.windows().size() == 4),
        "Missing phase windows");
  }

  private static void mismatchedCuts(boolean omittedCut) throws Exception {
    var harness = new Harness(2, 1);
    harness.start(
        worker -> {
          worker.begin();
          worker.checkpoint(21);
          worker.end();
        },
        worker -> {
          worker.begin();
          if (!omittedCut) worker.barrier(22);
          worker.end();
        });
    try {
      check(harness.rendezvous.await() == WorkerRendezvous.START, "Missing startup");
      harness.rendezvous.release();
      expect(IllegalStateException.class, harness.rendezvous::await);
    } finally {
      harness.close();
    }
    check(harness.failure.get() != null, "Cancelled mismatch became success");
  }

  private static void omittedCompletion() throws Exception {
    var harness = new Harness(2, 1);
    harness.start(
        worker -> {
          worker.begin();
        },
        worker -> {
          worker.begin();
          worker.checkpoint(22);
          worker.end();
        });
    try {
      check(harness.rendezvous.await() == WorkerRendezvous.START, "Missing startup");
      expect(
          IllegalStateException.class,
          () -> {
            harness.rendezvous.release();
            harness.rendezvous.await();
          });
    } finally {
      harness.close();
    }
    check(harness.failure.get() != null, "Omitted completion became success");
  }

  private static void failedWriter() throws Exception {
    var harness = new Harness(2, 1);
    harness.start(
        worker -> {
          worker.begin();
          worker.binding(11, true);
          worker.binding(12, true);
        },
        worker -> {
          worker.begin();
          worker.checkpoint(22);
          worker.end();
        });
    try {
      check(harness.rendezvous.await() == WorkerRendezvous.START, "Missing startup");
      expect(
          IllegalStateException.class,
          () -> {
            harness.rendezvous.release();
            harness.rendezvous.await();
          });
    } finally {
      harness.close();
    }
    check(
        harness.failure.get() != null && harness.failure.get().getMessage().contains("capacity"),
        "Writer exhaustion lost its failure");
  }

  private static void captureCancellation() throws Exception {
    var resumed = new AtomicInteger();
    var harness = new Harness(2, 1);
    Task task =
        worker -> {
          worker.begin();
          worker.checkpoint(21);
          resumed.incrementAndGet();
          worker.end();
        };
    harness.start(task, task);
    try {
      check(harness.rendezvous.await() == WorkerRendezvous.START, "Missing startup");
      harness.rendezvous.release();
      check(harness.rendezvous.await() == WorkerRendezvous.CAPTURE, "Missing capture");
      check(resumed.get() == 0, "Worker resumed before failed capture");
    } finally {
      harness.close();
    }
    check(
        resumed.get() == 0 && harness.failure.get() != null,
        "Failed capture released successful mutators");
  }

  private static void callerInterruption() throws Exception {
    var gate = new CountDownLatch(1);
    var harness = new Harness(2, 1);
    harness.start(
        worker -> {
          worker.begin();
          worker.end();
        },
        worker -> {
          gate.await();
          worker.begin();
          worker.end();
        });
    try {
      Thread.currentThread().interrupt();
      expect(InterruptedException.class, harness.rendezvous::await);
    } finally {
      Thread.interrupted();
      harness.rendezvous.cancel();
      gate.countDown();
      harness.close();
    }
    check(harness.failure.get() != null, "Interrupted caller left successful workers");
  }

  private static void workerInterruption() throws Exception {
    var interrupted = new CountDownLatch(1);
    var publish = new CountDownLatch(1);
    var callerReturned = new CountDownLatch(1);
    var observedFailure = new AtomicReference<Throwable>();
    var callerFailure = new AtomicReference<Throwable>();
    var harness = new Harness(2, 1);
    harness.start(
        worker -> {
          worker.begin();
          Thread.currentThread().interrupt();
          try {
            worker.checkpoint(21);
            throw new AssertionError("Interrupted worker resumed");
          } catch (IllegalStateException failure) {
            // Hold the originating exception before the wrapper publishes and cancels.
            Thread.interrupted();
            interrupted.countDown();
            publish.await();
            Thread.currentThread().interrupt();
            throw failure;
          }
        },
        worker -> {
          worker.begin();
          publish.await();
          worker.checkpoint(22);
          worker.end();
        });
    var caller =
        new Thread(
            () -> {
              try {
                check(harness.rendezvous.await() == WorkerRendezvous.START, "Missing startup");
                harness.rendezvous.release();
                harness.rendezvous.await();
                harness.rendezvous.release();
                callerFailure.set(new AssertionError("Interrupted group resumed"));
              } catch (IllegalStateException cancelled) {
                observedFailure.set(harness.failure.get());
              } catch (Throwable failure) {
                callerFailure.set(failure);
              } finally {
                callerReturned.countDown();
              }
            });
    caller.start();
    try {
      check(interrupted.await(2, TimeUnit.SECONDS), "Worker did not report interruption");
      check(
          !callerReturned.await(100, TimeUnit.MILLISECONDS),
          "Caller resumed before the worker published its failure");
      publish.countDown();
      check(callerReturned.await(2, TimeUnit.SECONDS), "Worker failure did not release caller");
      harness.join();
      check(callerFailure.get() == null, "Caller failed outside cancellation");
      check(
          observedFailure.get() instanceof IllegalStateException
              && observedFailure.get().getCause() instanceof InterruptedException,
          "Worker interruption cause was lost before caller cancellation");
    } finally {
      publish.countDown();
      harness.close();
      caller.join(2_000);
      check(!caller.isAlive(), "Cancelled caller remained blocked");
    }
  }

  private static void evidence() throws Exception {
    var caller = participant(0, 10, 2, 101, 1, List.of(binding(1, true)));
    var worker = participant(1, 21, 4, 102, 2, List.of(binding(2, false)));
    var evidence = new CaptureEvidence(7, 8, 10, 2, 1, 0, List.of(caller, worker));
    Path path = Files.createTempFile("gorrect-cut-check-", ".gcut");
    try {
      evidence.write(path);
      check(
          CaptureEvidence.read(path, 7, 8).equals(evidence), "Capture evidence round trip failed");
      byte[] bytes = Files.readAllBytes(path);
      expect(IOException.class, () -> CaptureEvidence.read(path, 9, 8));
      expect(IOException.class, () -> CaptureEvidence.read(path, 7, 9));
      for (int offset : new int[] {44, 80, 88, 92, 100, 108, 116}) {
        byte[] damaged = bytes.clone();
        // Counts, presence, participant ID, occurrence, thread, and activation must be checked.
        ByteBuffer.wrap(damaged).putInt(offset, -1);
        Files.write(path, damaged);
        expect(IOException.class, () -> CaptureEvidence.read(path, 7, 8));
      }
      Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 1));
      expect(IOException.class, () -> CaptureEvidence.read(path, 7, 8));
      Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length + 1));
      expect(IOException.class, () -> CaptureEvidence.read(path, 7, 8));
      expect(
          IllegalArgumentException.class,
          () -> new CaptureEvidence(7, 8, 10, 2, 1, 0, List.of(caller)));
      expect(
          IllegalArgumentException.class,
          () -> new CaptureEvidence(7, 8, 10, 2, 1, 0, List.of(worker, caller)));
      expect(
          IllegalArgumentException.class,
          () -> new CaptureEvidence(7, 8, 10, 2, 1, 0, List.of(caller, worker, worker)));
      expect(
          IllegalArgumentException.class,
          () ->
              new CaptureEvidence(
                  7, 8, 10, 2, 1, 0, List.of(caller, participant(1, 21, 4, 101, 2, List.of()))));
      expect(
          IllegalArgumentException.class,
          () ->
              new CaptureEvidence(
                  7, 8, 10, 2, 1, 0, List.of(caller, participant(1, 21, 4, 102, 1, List.of()))));
      expect(
          IllegalArgumentException.class,
          () ->
              new CaptureEvidence(
                  7,
                  8,
                  10,
                  2,
                  1,
                  0,
                  List.of(caller, participant(1, 21, 4, 102, 2, List.of(binding(1, false))))));
      var tooMany = new ArrayList<CaptureEvidence.Participant>();
      tooMany.add(caller);
      for (int index = 1; index <= CaptureEvidence.MAX_PARTICIPANTS; index++)
        tooMany.add(participant(index, 20 + index, 1, 200 + index, 2, List.of()));
      expect(IllegalArgumentException.class, () -> new CaptureEvidence(7, 8, 10, 2, 1, 0, tooMany));
      var maximum = new ArrayList<BindingEvidence.Binding>();
      for (int index = 1; index <= BindingEvidence.MAX_BINDINGS; index++)
        maximum.add(binding(index, true));
      var fullCaller = participant(0, 10, 2, 101, 1, maximum);
      var emptyWorker = participant(1, 21, 4, 102, 2, List.of());
      var full = new CaptureEvidence(7, 8, 10, 2, 1, 0, List.of(fullCaller, emptyWorker));
      full.write(path);
      check(CaptureEvidence.read(path, 7, 8).equals(full), "Maximum binding evidence failed");
      expect(
          IllegalArgumentException.class,
          () ->
              new CaptureEvidence(
                  7,
                  8,
                  10,
                  2,
                  1,
                  0,
                  List.of(
                      fullCaller,
                      participant(
                          1,
                          21,
                          4,
                          102,
                          2,
                          List.of(binding(BindingEvidence.MAX_BINDINGS + 1, true))))));
      var recorder = new BindingRecorder(1);
      expect(IllegalStateException.class, () -> recorder.record(0, true));
      recorder.record(1, false);
      expect(IllegalStateException.class, () -> recorder.record(2, true));
      check(
          recorder.snapshot().equals(List.of(binding(1, false))),
          "Rejected append changed recorder");
      recorder.clear();
      recorder.record(2, true);
      check(
          recorder.snapshot().equals(List.of(binding(2, true))), "Binding recorder did not reset");
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static BindingEvidence.Binding binding(int declaration, boolean present) {
    return new BindingEvidence.Binding(declaration, present);
  }

  private static CaptureEvidence.Participant participant(
      int worker,
      int operation,
      long occurrence,
      long thread,
      long activation,
      List<BindingEvidence.Binding> bindings) {
    return new CaptureEvidence.Participant(
        worker, operation, occurrence, thread, activation, bindings);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void expect(Class<? extends Throwable> type, Checked action) throws Exception {
    try {
      action.run();
    } catch (Throwable failure) {
      if (type.isInstance(failure)) return;
      throw new AssertionError("Unexpected failure", failure);
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface Task {
    void run(WorkerContext worker) throws Exception;
  }

  private static final class Harness {
    final WorkerRendezvous rendezvous;
    final List<WorkerContext> contexts = new ArrayList<>();
    final List<TraceRecorder> recorders = new ArrayList<>();
    final List<Thread> threads = new ArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    Harness(int workers, int bindingCapacity) {
      rendezvous = new WorkerRendezvous(workers);
      for (int index = 0; index < workers; index++) {
        var recorder = new TraceRecorder(index + 1, 100);
        recorders.add(recorder);
        contexts.add(
            new WorkerContext(
                recorder, rendezvous, new NoControl(), index + 1, 10, 1, index, bindingCapacity));
      }
    }

    void start(Task... tasks) {
      for (int index = 0; index < tasks.length; index++) {
        var task = tasks[index];
        var context = contexts.get(index);
        threads.add(
            new Thread(
                () -> {
                  try {
                    task.run(context);
                    if (!context.ended())
                      throw new IllegalStateException("Worker omitted completion");
                  } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    rendezvous.cancel();
                  }
                }));
      }
      threads.forEach(Thread::start);
    }

    void close() throws InterruptedException {
      rendezvous.cancel();
      join();
    }

    void join() throws InterruptedException {
      for (var thread : threads) {
        thread.join(2_000);
        if (thread.isAlive()) {
          thread.interrupt();
          throw new AssertionError("Cancelled worker remained blocked");
        }
      }
    }
  }

  private static final class NoControl implements VmControl {
    public List<ExecutionEvidence.Compilation> prepare(
        long round, int[] workers, Method[] methods) {
      return List.of();
    }

    public void witness(int worker, int group, long round, int phase, int point, long collection) {}

    public void collect(
        int operation, int worker, int group, long round, int phase, long collection) {}

    public String collectionControl() {
      return "system";
    }

    public String gcProblem() {
      return "";
    }

    public boolean collectionEvidence() {
      return false;
    }

    public String problem() {
      return "";
    }

    public void finish() {}

    public void close() {}
  }
}
