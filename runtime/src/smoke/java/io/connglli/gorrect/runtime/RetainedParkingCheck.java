package io.connglli.gorrect.runtime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic interrupted parking and cause-publication checks using the real rendezvous. */
public final class RetainedParkingCheck {
  private RetainedParkingCheck() {}

  private enum Scenario {
    PRE_INTERRUPTED,
    PARKED_INTERRUPTED,
    CALLER_INTERRUPTED,
    ACTION_FAILURE,
    CANCELLED
  }

  public static void main(String[] args) throws Exception {
    boolean safe = true;
    for (var scenario : Scenario.values()) safe &= run(scenario);
    safe &= releaseGeneration();
    if (!safe)
      throw new AssertionError(
          "Retained parking permits capture after interrupted worker leaves wait");
  }

  private static boolean run(Scenario scenario) throws Exception {
    boolean preInterrupt = scenario == Scenario.PRE_INTERRUPTED;
    boolean callerInterrupt = scenario == Scenario.CALLER_INTERRUPTED;
    boolean actionFailure = scenario == Scenario.ACTION_FAILURE;
    boolean cancelBeforeCapture = scenario == Scenario.CANCELLED;
    var rendezvous = new WorkerRendezvous(1, true);
    var enterCapture = new CountDownLatch(1);
    var workerCaught = new CountDownLatch(1);
    var publish = new CountDownLatch(1);
    var wrapperFailure = new AtomicReference<Throwable>();
    var workerOrigin = new AtomicReference<Throwable>();
    var surfaced = new AtomicReference<Throwable>();
    var actionCalls = new AtomicInteger();
    var resumed = new AtomicInteger();
    var callerReturned = new CountDownLatch(1);
    var threads = new ArrayList<Thread>();
    try {
      Thread worker =
          new Thread(
              () -> {
                try {
                  rendezvous.arrive(0, WorkerRendezvous.START, 10, 0);
                  enterCapture.await();
                  if (preInterrupt) Thread.currentThread().interrupt();
                  rendezvous.arrive(0, WorkerRendezvous.CAPTURE, 21, 0);
                  resumed.incrementAndGet();
                } catch (Throwable error) {
                  workerOrigin.set(error);
                  Thread.interrupted();
                  workerCaught.countDown();
                  try {
                    publish.await();
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  wrapperFailure.compareAndSet(null, error);
                  rendezvous.cancel();
                }
              },
              "probe-parked-worker");
      threads.add(worker);
      worker.start();
      if (rendezvous.await() != WorkerRendezvous.START) throw new AssertionError("Missing startup");
      rendezvous.release();
      Phaser phaser = (Phaser) field("phaser").get(rendezvous);
      Thread release =
          new Thread(
              () -> {
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((phaser.getPhase() != 2 || phaser.getUnarrivedParties() != 1)
                    && System.nanoTime() < limit) Thread.onSpinWait();
                enterCapture.countDown();
              });
      threads.add(release);
      release.start();
      if (rendezvous.await() != WorkerRendezvous.CAPTURE)
        throw new AssertionError("Missing capture");
      release.join(2000);
      if (preInterrupt) {
        if (!workerCaught.await(2, TimeUnit.SECONDS))
          throw new AssertionError("Pre-interrupted worker did not fail");
      } else {
        Object monitor = field("captureMonitor").get(rendezvous);
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
          boolean ready;
          synchronized (monitor) {
            ready = field("captureReady").getInt(rendezvous) == 1;
          }
          if (ready && worker.getState() == Thread.State.WAITING) break;
          if (System.nanoTime() >= limit) throw new AssertionError("Worker did not park");
          Thread.onSpinWait();
        }
        if (!callerInterrupt && !actionFailure && !cancelBeforeCapture) {
          worker.interrupt();
          if (!workerCaught.await(2, TimeUnit.SECONDS))
            throw new AssertionError("Waiting worker did not fail");
        }
      }
      if (cancelBeforeCapture) rendezvous.cancel();
      Thread caller =
          new Thread(
              () -> {
                if (callerInterrupt) Thread.currentThread().interrupt();
                try {
                  rendezvous.capture(
                      () -> {
                        actionCalls.incrementAndGet();
                        throw new IOException("independent capture failure");
                      });
                  surfaced.set(new AssertionError("Capture action unexpectedly succeeded"));
                } catch (Throwable error) {
                  Throwable first = wrapperFailure.get();
                  rendezvous.cancel();
                  publish.countDown();
                  try {
                    worker.join(2000);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                  surfaced.set(first != null ? first : error);
                } finally {
                  callerReturned.countDown();
                }
              },
              "probe-capture-caller");
      threads.add(caller);
      caller.start();
      boolean returnedBeforePublication = callerReturned.await(100, TimeUnit.MILLISECONDS);
      int callsBeforePublication = actionCalls.get();
      publish.countDown();
      if (!callerReturned.await(2, TimeUnit.SECONDS)) {
        rendezvous.cancel();
        throw new AssertionError("Caller remained blocked");
      }
      caller.join(2000);
      worker.join(2000);
      if (worker.isAlive() || caller.isAlive() || resumed.get() != 0)
        throw new AssertionError("Cancelled mutator resumed or remained alive");
      boolean result;
      if (cancelBeforeCapture)
        result = surfaced.get() instanceof IllegalStateException && actionCalls.get() == 0;
      else if (callerInterrupt)
        result = surfaced.get() instanceof InterruptedException && actionCalls.get() == 0;
      else if (actionFailure)
        result = surfaced.get() instanceof IOException && actionCalls.get() == 1;
      else
        result =
            !returnedBeforePublication
                && callsBeforePublication == 0
                && surfaced.get() == workerOrigin.get()
                && surfaced.get() instanceof IllegalStateException
                && surfaced.get().getCause() instanceof InterruptedException;
      System.out.println(
          scenario
              + ": safe="
              + result
              + ", actionBeforeCausePublication="
              + callsBeforePublication
              + ", surfaced="
              + surfaced.get().getClass().getSimpleName()
              + ", workerResumed="
              + resumed.get());
      return result;
    } finally {
      enterCapture.countDown();
      publish.countDown();
      cleanup(rendezvous, threads);
    }
  }

  private static boolean releaseGeneration() throws Exception {
    var rendezvous = new WorkerRendezvous(1, true);
    var captured = new CountDownLatch(1);
    var caught = new CountDownLatch(1);
    var publish = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var resumed = new AtomicInteger();
    var threads = new ArrayList<Thread>();
    try {
      Thread worker =
          new Thread(
              () -> {
                try {
                  rendezvous.arrive(0, WorkerRendezvous.START, 10, 0);
                  rendezvous.arrive(0, WorkerRendezvous.CAPTURE, 21, 0);
                  resumed.incrementAndGet();
                } catch (Throwable error) {
                  Thread.interrupted();
                  caught.countDown();
                  try {
                    publish.await();
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  failure.compareAndSet(null, error);
                  rendezvous.cancel();
                }
              });
      threads.add(worker);
      worker.start();
      if (rendezvous.await() != WorkerRendezvous.START) throw new AssertionError("Missing startup");
      rendezvous.release();
      if (rendezvous.await() != WorkerRendezvous.CAPTURE)
        throw new AssertionError("Missing capture");
      rendezvous.capture(
          () -> {
            worker.interrupt();
            captured.countDown();
          });
      if (!captured.await(2, TimeUnit.SECONDS) || !caught.await(2, TimeUnit.SECONDS))
        throw new AssertionError("Interrupted release did not reach wrapper");
      Object monitor = field("captureMonitor").get(rendezvous);
      int ready;
      synchronized (monitor) {
        ready = field("captureReady").getInt(rendezvous);
      }
      var returned = new CountDownLatch(1);
      var surfaced = new AtomicReference<Throwable>();
      Thread caller =
          new Thread(
              () -> {
                try {
                  rendezvous.release();
                  rendezvous.await();
                  surfaced.set(new AssertionError("Interrupted group continued"));
                } catch (Throwable error) {
                  surfaced.set(failure.get() != null ? failure.get() : error);
                } finally {
                  returned.countDown();
                }
              });
      threads.add(caller);
      caller.start();
      boolean blocked = !returned.await(100, TimeUnit.MILLISECONDS);
      publish.countDown();
      if (!returned.await(2, TimeUnit.SECONDS))
        throw new AssertionError("Release caller remained blocked");
      caller.join(2000);
      worker.join(2000);
      boolean result =
          ready == 0
              && blocked
              && resumed.get() == 0
              && !worker.isAlive()
              && surfaced.get().getCause() instanceof InterruptedException;
      System.out.println(
          "interrupt during normal generation release: safe="
              + result
              + ", nextGenerationReady="
              + ready
              + ", workerResumed="
              + resumed.get()
              + ", blocked="
              + blocked
              + ", surfaced="
              + surfaced.get()
              + ", cause="
              + surfaced.get().getCause());
      return result;
    } finally {
      publish.countDown();
      cleanup(rendezvous, threads);
    }
  }

  private static void cleanup(WorkerRendezvous rendezvous, List<Thread> threads)
      throws InterruptedException {
    rendezvous.cancel();
    threads.forEach(Thread::interrupt);
    for (var thread : threads) {
      thread.join(2_000);
      if (thread.isAlive())
        throw new AssertionError("Probe left a live thread: " + thread.getName());
    }
  }

  // Read-only access forces the first Phaser arrival and observes readiness; no state is injected.
  private static Field field(String name) throws Exception {
    Field result = WorkerRendezvous.class.getDeclaredField(name);
    result.setAccessible(true);
    return result;
  }
}
