package io.connglli.gorrect.runtime.observe;

import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Target-side entry point, requiring the library as a startup JVMTI agent and a loaded JNI library.
 * Native state holds copied metadata and weak tags, never workload JNI globals. Registration and
 * capture are serialized. GIR graph capture uses quiescent named-root boundaries; counter and frame
 * queries may also run in workers.
 */
public final class NativeObserver {
  public record Registration(Class<?> type, HeapSnapshot.ClassKind kind) {
    public Registration {
      Objects.requireNonNull(type);
      Objects.requireNonNull(kind);
    }
  }

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
   * Returns native evidence, including partial records on exhaustion. Makes no explicit GC request;
   * the heap walk may materialize objects, and creating the result may trigger normal allocation
   * GC. Run/checkpoint tokens must be positive and records is the preallocated record bound. The
   * returned bytes retain partial/error status for the host to diagnose; a returned array alone
   * does not establish sufficient capture coverage.
   */
  public static byte[] capture(long run, long checkpoint, int records) {
    return capture0(run, checkpoint, records);
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

  private static native void bindArrayIdentity0(
      Object array, String shape, int worker, int site, long occurrence);

  private static native byte[] capture0(long run, long checkpoint, int records);

  private static native long capabilities0();

  private static native boolean wasCompiled0(Method method);
}
