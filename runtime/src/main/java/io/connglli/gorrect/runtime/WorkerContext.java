package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Phaser;

/**
 * A generated worker's recording and phase context for one parallel-block visit. The worker calls
 * {@link #begin} once, executes matching top-level barriers, then calls {@link #end} once. The
 * session reads its windows only after joining it. This context stores no graph references.
 */
public final class WorkerContext implements ObservationRecorder {
  private final TraceRecorder recorder;
  private final Phaser phaser;
  private final VmControl control;
  private final int worker, group;
  private final long round;
  private final List<ExecutionEvidence.Window> windows = new ArrayList<>();
  private int phase;
  private long begin;
  private long gcBefore = -1, gcAfter = -1;
  private long collections, observedCollection;
  private boolean ended;

  WorkerContext(
      TraceRecorder recorder, Phaser phaser, VmControl control, int worker, int group, long round) {
    this.recorder = recorder;
    this.phaser = phaser;
    this.control = control;
    this.worker = worker;
    this.group = group;
    this.round = round;
  }

  public void record(int operation, int kind, long value) {
    recorder.record(operation, kind, value, group, round, phase);
  }

  @Override
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
    recorder.reference(
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
        group,
        round,
        phase);
  }

  /** Waits for peer startup, opens phase zero's active interval, and records the entry witness. */
  public void begin() {
    await();
    begin = System.nanoTime();
    record(group, ExecutionTrace.Kind.WORKER_START.ordinal(), 0);
    control.witness(worker, group, round, phase, ExecutionEvidence.ENTER, 0);
  }

  /**
   * Closes the current active interval before waiting for peers, then opens the next phase.
   * Operation identifies the GIR barrier. Peer failure cancels the wait with an exception.
   */
  public void barrier(int operation) {
    record(operation, ExecutionTrace.Kind.BARRIER.ordinal(), 0);
    finishWindow();

    await();

    phase++;
    begin = System.nanoTime();
    gcBefore = -1;
    gcAfter = -1;
    observedCollection = 0;
    record(operation, ExecutionTrace.Kind.PHASE_START.ordinal(), 0);
  }

  /** Records normal worker completion and the exit witness; it does not join other workers. */
  public void end() {
    record(group, ExecutionTrace.Kind.WORKER_END.ordinal(), 0);
    finishWindow();
    control.witness(worker, group, round, phase, ExecutionEvidence.EXIT, 0);
    ended = true;
  }

  /**
   * Records before/after frame witnesses and pause counters around a GIR collection request.
   * Ordinals advance across phases within this visit. Only the phase's last request with observed
   * pause progress is retained in its window; supported cycle-completion capture uses a separate
   * request ledger.
   */
  public void collect(int operation) {
    long collection = ++collections;
    control.witness(worker, group, round, phase, ExecutionEvidence.BEFORE_COLLECTION, collection);
    long before = NativeObserver.gcFinishes();

    control.collect(operation, worker, group, round, phase, collection);

    long after = NativeObserver.gcFinishes();
    control.witness(worker, group, round, phase, ExecutionEvidence.AFTER_COLLECTION, collection);

    if (before >= 0 && after > before) {
      gcBefore = before;
      gcAfter = after;
      observedCollection = collection;
    }
  }

  boolean ended() {
    return ended;
  }

  List<ExecutionEvidence.Window> windows() {
    return List.copyOf(windows);
  }

  private void await() {
    if (phaser.arriveAndAwaitAdvance() < 0)
      throw new IllegalStateException("Worker phase was cancelled");
  }

  private void finishWindow() {
    windows.add(
        new ExecutionEvidence.Window(
            worker,
            group,
            round,
            phase,
            Thread.currentThread().getId(),
            begin,
            System.nanoTime(),
            gcBefore,
            gcAfter,
            observedCollection));
  }
}
