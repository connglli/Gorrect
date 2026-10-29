package io.connglli.gorrect.runtime.observe;

import io.connglli.gorrect.protocol.HeapSnapshot;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Compares the same temporary-object workload with no agent, tagging, and heap walking. */
public final class AllocationProbe {
  private AllocationProbe() {}

  public static void main(String[] args) {
    if (args.length > 0) {
      System.load(args[0]);
      NativeObserver.configure(
          List.of(
              new NativeObserver.Registration(
                  ObserverFixture.Node.class, HeapSnapshot.ClassKind.OBJECT),
              new NativeObserver.Registration(
                  Object[].class, HeapSnapshot.ClassKind.REFERENCE_ARRAY),
              new NativeObserver.Registration(long[].class, HeapSnapshot.ClassKind.DATA_ARRAY),
              new NativeObserver.Registration(
                  ObserverFixture.Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    }
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) {
      System.out.println("unsupported");
      return;
    }
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    long sum = 0;
    for (int i = 0; i < 100_000; i++) sum += ObserverFixture.virtualSubject(i);
    if (args.length > 1) NativeObserver.capture(101, 1, 10_000);
    // Compare measured bytes across modes; scalar replacement and counter overhead vary by VM.
    long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    for (int i = 0; i < 100_000; i++) sum += ObserverFixture.virtualSubject(i);
    long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
    if (sum != 10_000_100_000L) throw new AssertionError("Allocation workload changed");
    System.out.println("allocated.bytes=" + bytes);
  }
}
