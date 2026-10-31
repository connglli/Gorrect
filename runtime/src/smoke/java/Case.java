import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.runtime.TargetSession;
import io.connglli.gorrect.runtime.WorkerContext;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native retained-provider fixture with the generated Case's actual method names and descriptors.
 */
public final class Case {
  private static final long RUN = 17;
  private static final long STACK_LOCAL = 24; // JVMTI_HEAP_REFERENCE_STACK_LOCAL.
  private static final Phaser rendezvous = new Phaser(3);
  private static final AtomicReference<Throwable> failure = new AtomicReference<>();
  private static final Object cutMonitor = new Object();
  private static int ready, released;
  private static Path output;
  private static CodeEvidence startup;
  private static long checkpoint;

  public static final class Node {
    public final int idWorker = 0;
    public final int idSite = 0;
    public final long idOccurrence;

    Node(long occurrence) {
      idOccurrence = occurrence;
    }
  }

  public static final class Roots {
    public static volatile Node bridge;
  }

  private Case() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("Expected library, output and mode");
    System.load(args[0]);
    output = Path.of(args[1]);
    Files.createDirectories(output);
    if (!NativeObserver.retainedMode()
        || (NativeObserver.capabilities() & HeapSnapshot.LOCAL_VARIABLES) != 0)
      throw new AssertionError("Retained mode must not request local-variable access");
    byte[] expected;
    try (var input = Case.class.getResourceAsStream("Case.class")) {
      if (input == null) throw new AssertionError("Missing fixture class bytes");
      expected = input.readAllBytes();
    }
    if (args[2].equals("duplicate")) new DuplicateLoader().define(expected).getDeclaredMethods();
    Class<?> intended = args[2].equals("wrong-mirror") ? Node.class : Case.class;
    byte[] bytes = NativeObserver.bindCode(RUN, intended);
    startup = CodeEvidence.read(bytes, RUN, CodeEvidence.Stage.STARTUP);
    Files.write(output.resolve("code.gcode"), bytes);
    if (args[2].equals("wrong-mirror") || args[2].equals("duplicate")) {
      if (startup.status() == CodeEvidence.Status.VALID)
        throw new AssertionError("Invalid startup was certified");
      return;
    }
    if (startup.status() != CodeEvidence.Status.VALID
        || startup.flags() != 15
        || !Arrays.equals(startup.code(), expected))
      throw new AssertionError("Startup code was not certified exactly");
    try {
      NativeObserver.bindCode(RUN, Case.class);
      throw new AssertionError("Repeated binding succeeded");
    } catch (IllegalStateException expectedFailure) {
      // A rejected second binding must not alter the valid first certificate.
    }
    if (args[2].equals("abort")) {
      var aborted =
          CodeEvidence.read(NativeObserver.codeStatus(RUN, false), RUN, CodeEvidence.Stage.FINAL);
      if (aborted.status() == CodeEvidence.Status.VALID || aborted.epoch() <= startup.epoch())
        throw new AssertionError("Aborted execution acquired valid completion");
      return;
    }
    NativeObserver.configure(
        List.of(
            new NativeObserver.Registration(Node.class, HeapSnapshot.ClassKind.OBJECT),
            new NativeObserver.Registration(Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    if (args[2].equals("truncated")) {
      var limited = recursiveCapture(80);
      if (FrameEvidence.read(limited.frames(), RUN, 1).status() != CodeEvidence.Status.LIMIT)
        throw new AssertionError("Truncated frame stack was certified");
      NativeObserver.decode(limited.snapshot(), RUN, 1);
      NativeObserver.codeStatus(RUN, true);
      return;
    }
    try {
      NativeObserver.captureRetained(
          RUN,
          98,
          10_000,
          new Thread[] {Thread.currentThread(), Thread.currentThread()},
          new int[] {0, 1},
          false);
      throw new AssertionError("Duplicate participant was accepted");
    } catch (IllegalStateException expectedFailure) {
      // Invalid control input must leave the active certificate usable.
    }
    try {
      NativeObserver.capture(RUN, 99, 10_000);
      throw new AssertionError("Retained capture fell back to the legacy path");
    } catch (IllegalStateException expectedFailure) {
      // Every retained capture must include the participant/frame envelope.
    }
    execute(null);
    save(new Thread[] {Thread.currentThread()}, new int[] {0}, true, false);
    bytes = NativeObserver.codeStatus(RUN, true);
    Files.write(output.resolve("code-final.gcode"), bytes);
    var finished = CodeEvidence.read(bytes, RUN, CodeEvidence.Stage.FINAL);
    if (finished.status() != CodeEvidence.Status.VALID
        || finished.epoch() != startup.epoch()
        || finished.classToken() != startup.classToken()
        || finished.loaderToken() != startup.loaderToken())
      throw new AssertionError("Completion changed a valid certificate");
    try {
      NativeObserver.captureRetained(
          RUN, 100, 10_000, new Thread[] {Thread.currentThread()}, new int[] {0}, true);
      throw new AssertionError("Capture continued after finalization");
    } catch (IllegalStateException expectedFailure) {
      // Final certification closes the capture lifetime.
    }
    System.out.println("Retained observer passed on " + System.getProperty("java.vm.name"));
  }

  private static NativeObserver.RetainedCapture recursiveCapture(int remaining) {
    if (remaining != 0) return recursiveCapture(remaining - 1);
    return NativeObserver.captureRetained(
        RUN, 1, 10_000, new Thread[] {Thread.currentThread()}, new int[] {0}, false);
  }

  private static final class DuplicateLoader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass("Case", bytes, 0, bytes.length);
    }
  }

  private static void execute(TargetSession ignored) throws Exception {
    var held = new Node(1);
    Roots.bridge = held;
    Roots.bridge = null;
    save(new Thread[] {Thread.currentThread()}, new int[] {0}, false, true);
    var first = new Thread(() -> runWorker(1));
    var second = new Thread(() -> runWorker(2));
    first.start();
    second.start();
    boolean finished = false;
    try {
      for (int round = 0; round < 2; round++) {
        if (rendezvous.arriveAndAwaitAdvance() < 0)
          throw new AssertionError("Worker rendezvous failed", failure.get());
        synchronized (cutMonitor) {
          while (ready != (round + 1) * 2) cutMonitor.wait();
          save(
              new Thread[] {Thread.currentThread(), first, second},
              new int[] {0, 1, 2},
              false,
              true);
          released = round + 1;
          cutMonitor.notifyAll();
        }
        rendezvous.arriveAndAwaitAdvance();
      }
      finished = true;
    } finally {
      if (!finished) {
        synchronized (cutMonitor) {
          released = Integer.MAX_VALUE;
          cutMonitor.notifyAll();
        }
        rendezvous.forceTermination();
      }
      first.join();
      second.join();
    }
    if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
    Reference.reachabilityFence(held);
  }

  private static void runWorker(int worker) {
    try {
      if (worker == 1) worker1(null, null);
      else worker2(null, null);
    } catch (Throwable problem) {
      failure.compareAndSet(null, problem);
      rendezvous.forceTermination();
    }
  }

  private static void worker1(TargetSession ignored, WorkerContext context) {
    var held = new Node(2);
    Roots.bridge = held;
    Roots.bridge = null;
    park();
    Reference.reachabilityFence(held);
  }

  private static void worker2(TargetSession ignored, WorkerContext context) {
    Node empty = null;
    park();
    Reference.reachabilityFence(empty);
  }

  private static void park() {
    for (int round = 0; round < 2; round++) {
      if (rendezvous.arriveAndAwaitAdvance() < 0) throw new IllegalStateException("Cancelled cut");
      synchronized (cutMonitor) {
        ready++;
        cutMonitor.notifyAll();
        try {
          while (released <= round) cutMonitor.wait();
        } catch (InterruptedException problem) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted cut", problem);
        }
      }
      if (rendezvous.arriveAndAwaitAdvance() < 0)
        throw new IllegalStateException("Cancelled release");
    }
  }

  private static void save(Thread[] threads, int[] workers, boolean terminal, boolean roots)
      throws Exception {
    long sequence = ++checkpoint;
    var evidence =
        NativeObserver.captureRetained(RUN, sequence, 50_000, threads, workers, terminal);
    Files.write(output.resolve(sequence + ".gsnap"), evidence.snapshot());
    Files.write(output.resolve(sequence + ".gframe"), evidence.frames());
    var snapshot = NativeObserver.decode(evidence.snapshot(), RUN, sequence);
    var frames = FrameEvidence.read(evidence.frames(), RUN, sequence);
    if (snapshot.status() != HeapSnapshot.Status.COMPLETE
        || frames.status() != CodeEvidence.Status.VALID
        || frames.beforeEpoch() != startup.epoch()
        || frames.afterEpoch() != startup.epoch()
        || frames.participants().size() != threads.length)
      throw new AssertionError("Incomplete retained capture");
    for (int index = 0; index < threads.length; index++) {
      var participant = frames.participants().get(index);
      String name = workers[index] == 0 ? terminal ? "main" : "execute" : "worker" + workers[index];
      long method =
          startup.methods().stream()
              .filter(value -> value.name().equals(name))
              .findFirst()
              .orElseThrow()
              .token();
      var before = participant.before().stream().filter(frame -> frame.method() == method).toList();
      var after = participant.after().stream().filter(frame -> frame.method() == method).toList();
      if (participant.thread() != threads[index].getId()
          || participant.worker() != workers[index]
          || before.size() != 1
          || !before.equals(after)
          || before.get(0).bci() < 0)
        throw new AssertionError("Unstable or incorrect role frame: " + name);
      if (roots && workers[index] != 2) {
        var selected = before.get(0);
        boolean physical =
            snapshot.records().stream()
                .anyMatch(
                    record ->
                        record.kind() == HeapSnapshot.Kind.ROOT
                            && record.b() == STACK_LOCAL
                            && record.c() == participant.thread()
                            && record.d() == selected.depth()
                            && record.e() >= 0
                            && record.f() == method
                            && (record.g() == -1 || record.g() == selected.bci()));
        if (!physical) throw new AssertionError("No physical local origin for " + name);
      }
    }
  }
}
