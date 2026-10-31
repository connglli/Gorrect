package io.connglli.gorrect.runtime;

import java.io.IOException;
import java.util.concurrent.Phaser;

/** Two-phase primitive publication: workers cannot resume while the main thread services a cut. */
final class WorkerRendezvous {
  static final int START = 0, BARRIER = 1, CAPTURE = 2, END = 3;
  private final Phaser phaser;
  private final int[] kinds, operations, phases;
  private final long[] threads;
  private final Object captureMonitor;
  private int captureReady, captureGeneration;
  private boolean started;
  private int nextPhase;

  WorkerRendezvous(int workers) {
    this(workers, false);
  }

  WorkerRendezvous(int workers, boolean stableCaptureFrames) {
    if (workers < 1 || workers > 16)
      throw new IllegalArgumentException("Invalid participant count");
    phaser = new Phaser(workers + 1);
    kinds = new int[workers];
    operations = new int[workers];
    phases = new int[workers];
    threads = new long[workers];
    captureMonitor = stableCaptureFrames ? new Object() : null;
  }

  void arrive(int index, int kind, int operation, int phase) {
    kinds[index] = kind;
    operations[index] = operation;
    phases[index] = phase;
    threads[index] = Thread.currentThread().getId();
    try {
      advance();
      if (kind == CAPTURE && captureMonitor != null) parkCapture();
      advance();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      // The worker wrapper must publish this cause before cancellation releases the caller.
      throw new IllegalStateException("Worker phase was interrupted", exception);
    }
  }

  private void parkCapture() throws InterruptedException {
    synchronized (captureMonitor) {
      int generation = captureGeneration;
      captureReady++;
      captureMonitor.notifyAll();
      try {
        while (generation == captureGeneration && !phaser.isTerminated()) captureMonitor.wait();
        // Notify may win an interrupt race and return normally with the interrupt still pending.
        if (Thread.interrupted()) throw new InterruptedException("Worker capture was interrupted");
        if (phaser.isTerminated()) throw new IllegalStateException("Worker phase was cancelled");
      } finally {
        // A failed wait must withdraw readiness before the wrapper publishes its cause and cancels.
        // Successful release already reset this generation's count; never subtract from the next.
        if (generation == captureGeneration) {
          captureReady--;
          captureMonitor.notifyAll();
        }
      }
    }
  }

  /** Caller-owned capture and acknowledgement; never invoked on a worker. */
  @FunctionalInterface
  interface CaptureAction {
    void run() throws IOException;
  }

  /**
   * Waits until every worker has released the monitor in Object.wait, then holds it through the
   * capture and acknowledgement. Even a spurious wakeup cannot return from wait while this owns the
   * monitor. The existing second Phaser phase still prevents mutations if the action fails.
   */
  void capture(CaptureAction action) throws IOException, InterruptedException {
    if (captureMonitor == null) throw new IllegalStateException("Stable capture is not configured");
    if (Thread.interrupted()) throw new InterruptedException("Capture caller was interrupted");
    synchronized (captureMonitor) {
      while (captureReady != kinds.length && !phaser.isTerminated()) captureMonitor.wait();
      if (phaser.isTerminated()) throw new IllegalStateException("Worker phase was cancelled");
      try {
        action.run();
      } finally {
        captureReady = 0;
        captureGeneration++;
        captureMonitor.notifyAll();
      }
    }
  }

  int await() throws InterruptedException {
    advance();
    int kind = kinds[0];
    if (!started && kind != START
        || started && (kind < BARRIER || kind > END)
        || phases[0] != nextPhase)
      throw new IllegalStateException("Invalid worker synchronization sequence");
    for (int index = 0; index < kinds.length; index++)
      if (kinds[index] != kind
          || phases[index] != phases[0]
          || operations[index] <= 0
          || threads[index] <= 0)
        throw new IllegalStateException("Workers reached mismatched synchronization cuts");
    started = true;
    if (kind == BARRIER || kind == CAPTURE) nextPhase++;
    return kind;
  }

  void release() throws InterruptedException {
    advance();
  }

  int operation(int index) {
    return operations[index];
  }

  int phase() {
    return phases[0];
  }

  long thread(int index) {
    return threads[index];
  }

  void cancel() {
    phaser.forceTermination();
    if (captureMonitor != null) {
      synchronized (captureMonitor) {
        captureMonitor.notifyAll();
      }
    }
  }

  private void advance() throws InterruptedException {
    if (phaser.awaitAdvanceInterruptibly(phaser.arrive()) < 0)
      throw new IllegalStateException("Worker phase was cancelled");
  }
}
