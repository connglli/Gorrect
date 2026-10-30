package io.connglli.gorrect.runtime;

import java.util.concurrent.Phaser;

/** Two-phase primitive publication: workers cannot resume while the main thread services a cut. */
final class WorkerRendezvous {
  static final int START = 0, BARRIER = 1, CAPTURE = 2, END = 3;
  private final Phaser phaser;
  private final int[] kinds, operations, phases;
  private final long[] threads;
  private boolean started;
  private int nextPhase;

  WorkerRendezvous(int workers) {
    if (workers < 1 || workers > 16)
      throw new IllegalArgumentException("Invalid participant count");
    phaser = new Phaser(workers + 1);
    kinds = new int[workers];
    operations = new int[workers];
    phases = new int[workers];
    threads = new long[workers];
  }

  void arrive(int index, int kind, int operation, int phase) {
    kinds[index] = kind;
    operations[index] = operation;
    phases[index] = phase;
    threads[index] = Thread.currentThread().getId();
    try {
      advance();
      advance();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      // The worker wrapper must publish this cause before cancellation releases the caller.
      throw new IllegalStateException("Worker phase was interrupted", exception);
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
  }

  private void advance() throws InterruptedException {
    if (phaser.awaitAdvanceInterruptibly(phaser.arrive()) < 0)
      throw new IllegalStateException("Worker phase was cancelled");
  }
}
