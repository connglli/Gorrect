package io.connglli.gorrect.runtime.observe;

import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;

/**
 * Measures observer effects with the same temporary objects; allocation counts are evidence only.
 */
public final class ObservationCalibration {
  private ObservationCalibration() {}

  /** Mode is none/tagging/witness/capture; an optional second argument names the native library. */
  public static void main(String[] args) {
    if (args.length < 1
        || args.length > 2
        || !Set.of("none", "tagging", "witness", "capture").contains(args[0]))
      throw new IllegalArgumentException("Expected mode and optional observer library");
    boolean agent = args.length == 2, witness = args[0].equals("witness");
    if (!agent && Set.of("tagging", "capture").contains(args[0]))
      throw new IllegalArgumentException("Mode requires an observer library");
    if (agent) {
      System.load(args[1]);
      NativeObserver.configure(
          List.of(
              new NativeObserver.Registration(
                  ObserverFixture.Node.class, HeapSnapshot.ClassKind.OBJECT),
              new NativeObserver.Registration(
                  ObserverFixture.Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    }
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) {
      System.out.println("unsupported");
      return;
    }
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    var values = new long[100_000];
    var aliases = new boolean[values.length];
    long sum = 0;
    for (int i = 0; i < values.length; i++) sum += subject(i, i, witness, values, aliases);
    if (args[0].equals("capture")) NativeObserver.capture(101, 1, 10_000);

    long thread = Thread.currentThread().getId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (int i = 0; i < values.length; i++) sum += subject(i, i, witness, values, aliases);
    long bytes = bean.getThreadAllocatedBytes(thread) - before;
    if (sum != 10_000_100_000L) throw new AssertionError("Calibration workload changed");
    if (witness)
      for (int i = 0; i < values.length; i++)
        if (values[i] != i || !aliases[i]) throw new AssertionError("Semantic witness changed");
    System.out.println("allocated.bytes=" + bytes);
    System.out.println("observer.capabilities=" + (agent ? NativeObserver.capabilities() : 0));
  }

  private static long subject(
      long value, int index, boolean witness, long[] values, boolean[] aliases) {
    var object = new ObserverFixture.Node(value + 1);
    object.d0 = value;
    var alias = object;
    if (witness) {
      // Only primitive results leave the subject; recording cannot publish the temporary object.
      values[index] = object.d0;
      aliases[index] = object == alias;
    }
    return object.d0 + 1;
  }
}
