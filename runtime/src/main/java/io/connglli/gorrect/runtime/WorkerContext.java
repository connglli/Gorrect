package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A generated worker's recording and phase context for one parallel-block visit. The worker calls
 * {@link #begin} once, executes matching top-level barriers and checkpoints, then calls {@link
 * #end} once. The session reads bindings only at paused cuts and windows after joining. This
 * context stores no graph references.
 */
public final class WorkerContext implements ObservationRecorder {
  private final TraceRecorder recorder;
  private final WorkerRendezvous rendezvous;
  private final BindingRecorder bindings;
  private final int participant;
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
      TraceRecorder recorder,
      WorkerRendezvous rendezvous,
      VmControl control,
      int worker,
      int group,
      long round,
      int participant,
      int bindingCapacity) {
    this.recorder = recorder;
    this.rendezvous = rendezvous;
    this.participant = participant;
    bindings = new BindingRecorder(bindingCapacity);
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
    rendezvous.arrive(participant, WorkerRendezvous.START, group, phase);
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

    rendezvous.arrive(participant, WorkerRendezvous.BARRIER, operation, phase);

    nextPhase(operation);
  }

  /** Records only primitive binding nullness for the next coordinated cut. */
  public void binding(int declaration, boolean present) {
    bindings.record(declaration, present);
  }

  /**
   * Pauses with all peers while the main thread captures and acknowledges the graph. Failure
   * cancels every rendezvous waiter. Records the checkpoint marker and resumed phase itself.
   */
  public void checkpoint(int operation) throws IOException {
    record(operation, ExecutionTrace.Kind.OPERATION.ordinal(), 0);
    finishWindow();
    rendezvous.arrive(participant, WorkerRendezvous.CAPTURE, operation, phase);
    bindings.clear();
    nextPhase(operation);
  }

  private void nextPhase(int operation) {
    phase++;
    begin = System.nanoTime();
    gcBefore = -1;
    gcAfter = -1;
    observedCollection = 0;
    record(operation, ExecutionTrace.Kind.PHASE_START.ordinal(), 0);
  }

  /** Records completion and its exit witness, then waits for matched peer completion. */
  public void end() {
    record(group, ExecutionTrace.Kind.WORKER_END.ordinal(), 0);
    finishWindow();
    control.witness(worker, group, round, phase, ExecutionEvidence.EXIT, 0);
    rendezvous.arrive(participant, WorkerRendezvous.END, group, phase);
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

  List<BindingEvidence.Binding> bindings() {
    return bindings.snapshot();
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
