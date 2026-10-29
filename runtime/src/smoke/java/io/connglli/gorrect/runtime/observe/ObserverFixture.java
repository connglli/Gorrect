package io.connglli.gorrect.runtime.observe;

import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Physical workloads for observer validation; this class does not interpret GIR. */
public final class ObserverFixture {
  private static final long RUN = 42;
  private static final int ITERATIONS = 50_000;
  private static final Properties evidence = new Properties();

  public static final class Node {
    public final int idWorker = 0;
    public final int idSite = 0;
    public final long idOccurrence;
    public Object f0;
    public Object f1;
    public long d0;

    Node(long identity) {
      idOccurrence = identity;
    }
  }

  public static final class Roots {
    public static volatile Node main;
    public static volatile Object[] refs;
    public static volatile long[] data;
    public static volatile Node bridge;
  }

  private ObserverFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Expected native library and artifact directory");
    System.load(Path.of(args[0]).toAbsolutePath().toString());
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    NativeObserver.configure(
        List.of(
            new NativeObserver.Registration(Node.class, HeapSnapshot.ClassKind.OBJECT),
            new NativeObserver.Registration(Object[].class, HeapSnapshot.ClassKind.REFERENCE_ARRAY),
            new NativeObserver.Registration(long[].class, HeapSnapshot.ClassKind.DATA_ARRAY),
            new NativeObserver.Registration(Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    setup();
    save(output, "initial", 1, 10_000);
    for (int i = 1; i <= ITERATIONS; i++) hotMutate(Roots.main, (Node) Roots.main.f0, i);
    Roots.refs[0] = Roots.main;
    Roots.data[1] = -7;
    save(output, "mutated", 2, 10_000);
    evidence.setProperty(
        "compiled.mutate",
        Boolean.toString(
            NativeObserver.hasCompiledCode(
                ObserverFixture.class.getDeclaredMethod(
                    "hotMutate", Node.class, Node.class, long.class))));
    save(output, "limited", 3, 1);
    clear();
    holdLocal(output);
    evidence.setProperty(
        "compiled.local",
        Boolean.toString(
            NativeObserver.hasCompiledCode(
                ObserverFixture.class.getDeclaredMethod("holdLocal", Path.class))));
    testRetention(output);
    virtualAllocationProbe();
    evidence.setProperty("capabilities", Long.toString(NativeObserver.capabilities()));
    evidence.setProperty("vm.name", System.getProperty("java.vm.name"));
    evidence.setProperty("vm.version", System.getProperty("java.vm.version"));
    evidence.setProperty("mutations", Integer.toString(ITERATIONS));
    try (var stream = Files.newOutputStream(output.resolve("evidence.properties"))) {
      evidence.store(stream, "Observer evidence");
    }
    System.out.println("Observer fixture passed on " + System.getProperty("java.vm.name"));
  }

  private static void setup() {
    var first = new Node(1);
    var second = new Node(2);
    first.d0 = 7;
    second.d0 = 9;
    Roots.main = first;
    Roots.refs = new Object[] {second, second, first};
    Roots.data = new long[] {Long.MIN_VALUE, 42, Long.MAX_VALUE};
    first.f0 = second;
    first.f1 = Roots.refs;
    second.f0 = first;
    second.f1 = Roots.data;
    // These arrays are published already; identity tagging is an explicit diagnostic mode.
    NativeObserver.bindArrayIdentity(Roots.refs, "Links", 0, 0, 3);
    NativeObserver.bindArrayIdentity(Roots.data, "Payload", 0, 0, 4);
  }

  private static void hotMutate(Node owner, Node target, long value) {
    owner.f0 = target;
    owner.d0 = value;
  }

  private static void holdLocal(Path output) throws IOException {
    var held = new Node(9);
    held.d0 = 99;
    held.f0 = held;
    // Publication ensures a physical object; capture receives no reference to it.
    Roots.bridge = held;
    Roots.bridge = null;
    System.gc();
    save(output, "local", 4, 10_000);
    if (held.d0 != 99 || held.f0 != held)
      throw new AssertionError("Local reference corrupted across GC/capture");
    Reference.reachabilityFence(held);
  }

  private static void testRetention(Path output) throws InterruptedException, IOException {
    var references = new AtomicReference<List<WeakReference<?>>>();
    var failure = new AtomicReference<Throwable>();
    var worker =
        new Thread(
            () -> {
              try {
                var node = new Node(99);
                var array = new long[] {11, 12};
                Roots.main = node;
                Roots.data = array;
                NativeObserver.bindArrayIdentity(array, "Small", 0, 0, 100);
                save(output, "before-release", 5, 10_000);
                clear();
                references.set(List.of(new WeakReference<>(node), new WeakReference<>(array)));
              } catch (Throwable error) {
                failure.set(error);
              }
            },
            "retention-worker");
    worker.start();
    worker.join();
    if (failure.get() != null) throw new AssertionError("Retention fixture failed", failure.get());
    // The allocating thread has exited, preventing stale locals from influencing this check.
    boolean cleared = false;
    for (int attempt = 0; attempt < 30; attempt++) {
      System.gc();
      if (references.get().stream().allMatch(reference -> reference.refersTo(null))) {
        cleared = true;
        break;
      }
      Thread.sleep(10);
    }
    if (!cleared)
      throw new AssertionError("Observer metadata may retain released workload objects");
    evidence.setProperty("retention.node-and-array-cleared", "true");
    save(output, "after-release", 6, 10_000);
  }

  static long virtualSubject(long value) {
    var node = new Node(value + 1);
    node.d0 = value;
    return node.d0 + 1;
  }

  private static void virtualAllocationProbe() throws ReflectiveOperationException {
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) {
      evidence.setProperty("virtual.bytes", "unsupported");
      return;
    }
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    long sum = 0;
    for (int i = 0; i < 50_000; i++) sum += virtualSubject(i);
    long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    for (int i = 0; i < 100_000; i++) sum += virtualSubject(i);
    long after = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    if (sum != 6_250_075_000L) throw new AssertionError("Virtual workload result mismatch");
    evidence.setProperty("virtual.bytes", Long.toString(after - before));
    evidence.setProperty(
        "compiled.virtual",
        Boolean.toString(
            NativeObserver.hasCompiledCode(
                ObserverFixture.class.getDeclaredMethod("virtualSubject", long.class))));
  }

  private static void clear() {
    Roots.main = null;
    Roots.refs = null;
    Roots.data = null;
    Roots.bridge = null;
  }

  private static void save(Path output, String name, long checkpoint, int limit)
      throws IOException {
    byte[] bytes = NativeObserver.capture(RUN, checkpoint, limit);
    var snapshot = NativeObserver.decode(bytes, RUN, checkpoint);
    if (snapshot.status()
        != (limit == 1 ? HeapSnapshot.Status.RECORD_LIMIT : HeapSnapshot.Status.COMPLETE))
      throw new AssertionError("Unexpected capture status " + snapshot.status());
    Files.write(output.resolve(name + ".gsnap"), bytes);
  }
}
