package io.connglli.gorrect.runtime.observe;

import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Target-side entry point, requiring the library as a startup JVMTI agent and a loaded JNI library.
 * Observer metadata holds copied primitive facts and weak tags. Source-selected native slots alone
 * own deliberate workload JNI globals; registration, slot access and capture are serialized. GIR
 * graph capture uses quiescent named-root boundaries; counter and frame queries may also run in
 * workers.
 */
public final class NativeObserver {
  public record Registration(Class<?> type, HeapSnapshot.ClassKind kind) {
    public Registration {
      Objects.requireNonNull(type);
      Objects.requireNonNull(kind);
    }
  }

  private static boolean queuesConfigured, automaticQueues;

  private NativeObserver() {}

  /**
   * Initializes and registers a case's classes once, before mutators start. This runs static
   * initializers and passes only class metadata to native code. Kinds must match supported layouts;
   * duplicate/incompatible registration fails. A failed setup must abort the case rather than reuse
   * a partly initialized observer.
   */
  public static void configure(List<Registration> classes) {
    for (var entry : classes) {
      if (!entry.type().isArray()) {
        try {
          Class.forName(entry.type().getName(), true, entry.type().getClassLoader());
        } catch (ClassNotFoundException exception) {
          throw new IllegalArgumentException("Class cannot be prepared", exception);
        }
      }
    }
    configure0(
        classes.stream().map(Registration::type).toArray(Class<?>[]::new),
        classes.stream().mapToInt(entry -> entry.kind().ordinal()).toArray());
  }

  /**
   * Adds dedicated static queue/helper roles after graph configuration. The native policy copies
   * metadata and weak tags only. Automatic mode also keeps a weak roots-class mirror; temporary
   * queue/lock locals never retain wrappers or referents.
   */
  public static void configureQueues(Class<?> queueRoots, Class<?> queueType) {
    configureQueues(queueRoots, queueType, false);
  }

  /**
   * Selects actual private-monitor custody; unsupported metadata aborts rather than falling back.
   */
  public static void configureQueues(Class<?> queueRoots, Class<?> queueType, boolean automatic) {
    Objects.requireNonNull(queueRoots);
    if (queuesConfigured) throw new IllegalStateException("Queue policy is already configured");
    try {
      Class.forName(queueRoots.getName(), true, queueRoots.getClassLoader());
      Class.forName(Objects.requireNonNull(queueType).getName(), true, queueType.getClassLoader());
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException("Queue roots cannot be prepared", failure);
    }
    configureQueues0(queueRoots, Objects.requireNonNull(queueType), automatic);
    automaticQueues = automatic;
    queuesConfigured = true;
  }

  /** Two independent recording statuses from exactly one heap walk. */
  public record QueueSnapshots(byte[] snapshot, byte[] resources, byte[] consistency) {
    public QueueSnapshots(byte[] snapshot, byte[] resources) {
      this(snapshot, resources, null);
    }

    public QueueSnapshots {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(resources);
    }
  }

  /**
   * Resource quota does not truncate an otherwise complete graph; global abort invalidates both.
   */
  public static QueueSnapshots captureQueues(long run, long checkpoint, int records) {
    if (!queuesConfigured) throw new IllegalStateException("Queue capture is not configured");
    byte[][] data = captureQueues0(run, checkpoint, records);
    if (data == null || data.length != (automaticQueues ? 3 : 2))
      throw new IllegalStateException("Incomplete queue capture result");
    return new QueueSnapshots(
        data[0], data[1], automaticQueues ? Objects.requireNonNull(data[2]) : null);
  }

  /**
   * Diagnostic array identity binding. This JNI call can force escape/materialization; use it only
   * for published arrays or experiments that explicitly permit that effect.
   */
  public static void bindArrayIdentity(
      Object array, String shape, int worker, int site, long occurrence) {
    Objects.requireNonNull(array);
    if (shape == null || !shape.matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new IllegalArgumentException("Invalid shape name");
    bindArrayIdentity0(array, shape, worker, site, occurrence);
  }

  /**
   * Reads worker (0), site (1), or occurrence (2) from the array's published weak-tag binding. The
   * binding must already exist. No result allocation, workload registry, or recorder lock is
   * involved; passing the array through JNI is an explicit diagnostic observation effect.
   */
  public static long arrayIdentity(Object array, int component) {
    Objects.requireNonNull(array);
    if (!array.getClass().isArray() || component < 0 || component > 2)
      throw new IllegalArgumentException("Invalid array identity query");
    return arrayIdentity0(array, component);
  }

  /**
   * Returns native evidence, including partial records on exhaustion. Makes no explicit GC request;
   * the heap walk may materialize objects, and creating the result may trigger normal allocation
   * GC. Run/checkpoint tokens must be positive and records is the preallocated record bound. The
   * returned bytes retain partial/error status for the host to diagnose; a returned array alone
   * does not establish sufficient capture coverage.
   */
  public static byte[] capture(long run, long checkpoint, int records) {
    return capture0(run, checkpoint, records);
  }

  /** Configures one bounded run and qualifies actual alias-global callback multiplicity. */
  public static void configureNativeRoots(long run, int slots, long generations, int records) {
    configureNativeRoots0(run, slots, generations, records);
  }

  /** Stores only a deliberate JNI global; failure preserves the previous slot. */
  public static void writeNativeRoot(long run, int slot, Object value) {
    writeNativeRoot0(run, slot, value);
  }

  /** Acquires an independent Java handle after checking the asserted nominal shape. */
  public static Object readNativeRoot(long run, int slot, Class<?> type, String shape) {
    return readNativeRoot0(run, slot, Objects.requireNonNull(type), Objects.requireNonNull(shape));
  }

  /** Paired raw graph, native table and optional retained caller-frame evidence. */
  public record NativeRootCapture(byte[] snapshot, byte[] resources, byte[] frames) {
    public NativeRootCapture {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(resources);
    }
  }

  /** Cleanup deletes all slots before its separate walk and closes this run against reuse. */
  public static NativeRootCapture captureNativeRoots(
      long run,
      long checkpoint,
      int records,
      int site,
      long visit,
      Thread[] threads,
      int[] workers,
      boolean terminal,
      boolean cleanup) {
    byte[][] data =
        captureNativeRoots0(
            run, checkpoint, records, site, visit, threads, workers, terminal, cleanup);
    if (data == null || data.length != (threads == null ? 2 : 3))
      throw new IllegalStateException("Incomplete native root capture result");
    return new NativeRootCapture(
        data[0], data[1], threads == null ? null : Objects.requireNonNull(data[2]));
  }

  /** Whether this startup agent selected retained metadata; this is not a readiness certificate. */
  public static boolean retainedMode() {
    return retainedMode0();
  }

  /**
   * Binds the intended loaded Case before construction and returns its startup code certificate.
   * Only one binding is allowed. Native state keeps weak class/loader references and copied bytes;
   * callers must persist and validate the result before permitting graph initialization.
   */
  public static byte[] bindCode(long run, Class<?> intended) {
    return bindCode0(run, Objects.requireNonNull(intended));
  }

  /**
   * Finalizes this run's certificate once. An aborted execution cannot return valid completion;
   * subsequent capture/binding/finalization requests fail rather than reopening its lifetime.
   */
  public static byte[] codeStatus(long run, boolean complete) {
    return codeStatus0(run, complete);
  }

  /** Raw evidence from one retained capture. Each byte array belongs to its caller. */
  public record RetainedCapture(
      byte[] snapshot, byte[] frames, byte[] resources, byte[] consistency) {
    public RetainedCapture(byte[] snapshot, byte[] frames) {
      this(snapshot, frames, null, null);
    }

    public RetainedCapture(byte[] snapshot, byte[] frames, byte[] resources) {
      this(snapshot, frames, resources, null);
    }

    public RetainedCapture {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(frames);
    }
  }

  /**
   * Brackets the heap walk with actual stack samples for caller-first, uniquely identified control
   * threads. Arrays must remain unchanged through return; workers must remain parked through
   * acknowledgement. Arguments contain no workload values; native temporary references expire
   * before return. Terminal selects the caller's main role after execute returns. Failed frame
   * evidence never becomes a native-table fallback.
   */
  public static RetainedCapture captureRetained(
      long run, long checkpoint, int records, Thread[] threads, int[] workers, boolean terminal) {
    Objects.requireNonNull(threads);
    Objects.requireNonNull(workers);
    byte[][] evidence = captureRetained0(run, checkpoint, records, threads, workers, terminal);
    if (evidence == null || evidence.length != (automaticQueues ? 4 : queuesConfigured ? 3 : 2))
      throw new IllegalStateException("Incomplete retained capture result");
    return new RetainedCapture(
        evidence[0],
        evidence[1],
        queuesConfigured ? Objects.requireNonNull(evidence[2]) : null,
        automaticQueues ? Objects.requireNonNull(evidence[3]) : null);
  }

  /** Checks wire integrity and capture identity; graph coverage is checked by the engine. */
  public static HeapSnapshot decode(byte[] bytes, long run, long checkpoint) throws IOException {
    return SnapshotCodec.read(bytes, run, checkpoint);
  }

  /** Returns HeapSnapshot capability bits; these alone do not establish root coverage. */
  public static long capabilities() {
    return capabilities0();
  }

  /** Reported GC pause finishes, or -1 when the agent cannot observe GC events. */
  public static native long gcFinishes();

  /** Evidence of installed code, not proof that a particular invocation executed that code. */
  public static boolean hasCompiledCode(Method method) {
    return wasCompiled0(Objects.requireNonNull(method));
  }

  /**
   * Raw owner/name/descriptor/kind tuples from the current OpenJ9 stack, or null if unavailable.
   * Inspects frames without passing workload objects or walking the heap.
   */
  public static native String[] openJ9Stack();

  private static native void configure0(Class<?>[] classes, int[] kinds);

  private static native void configureQueues0(
      Class<?> queueRoots, Class<?> queueType, boolean automatic);

  private static native byte[][] captureQueues0(long run, long checkpoint, int records);

  private static native void bindArrayIdentity0(
      Object array, String shape, int worker, int site, long occurrence);

  private static native long arrayIdentity0(Object array, int component);

  private static native void configureNativeRoots0(
      long run, int slots, long generations, int records);

  private static native void writeNativeRoot0(long run, int slot, Object value);

  private static native Object readNativeRoot0(long run, int slot, Class<?> type, String shape);

  private static native byte[][] captureNativeRoots0(
      long run,
      long checkpoint,
      int records,
      int site,
      long visit,
      Thread[] threads,
      int[] workers,
      boolean terminal,
      boolean cleanup);

  private static native byte[] capture0(long run, long checkpoint, int records);

  private static native boolean retainedMode0();

  private static native byte[] bindCode0(long run, Class<?> intended);

  private static native byte[] codeStatus0(long run, boolean complete);

  private static native byte[][] captureRetained0(
      long run, long checkpoint, int records, Thread[] threads, int[] workers, boolean terminal);

  private static native long capabilities0();

  private static native boolean wasCompiled0(Method method);
}
