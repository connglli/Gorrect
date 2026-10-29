package io.connglli.gorrect.protocol;

import java.util.List;

/**
 * Raw evidence from one bounded heap walk. Tags identify physical objects within the target JVM.
 * Enum ordinals are wire values and must stay aligned with the native writer.
 */
public record HeapSnapshot(
    long run,
    long checkpoint,
    Status status,
    int jvmtiError,
    long capabilities,
    long gcStartsBefore,
    long gcFinishesBefore,
    long gcStartsAfter,
    long gcFinishesAfter,
    List<ClassInfo> classes,
    List<ArrayIdentity> arrays,
    List<MethodInfo> methods,
    List<Record> records) {
  public static final long TAGGING = 1;
  public static final long GC_EVENTS = 2;
  public static final long COMPILATION_EVENTS = 4;
  public static final long COMPILATION_OVERFLOW = 8;
  public static final long REFERENCE_FIELDS = 16;

  /** Capture completion only; COMPLETE does not establish GIR binding coverage. */
  public enum Status {
    COMPLETE,
    RECORD_LIMIT,
    JVMTI_ERROR
  }

  public enum ClassKind {
    OBJECT,
    REFERENCE_ARRAY,
    DATA_ARRAY,
    ROOTS,
    SOFT_REFERENCE,
    WEAK_REFERENCE
  }

  public HeapSnapshot {
    classes = List.copyOf(classes);
    arrays = List.copyOf(arrays);
    methods = List.copyOf(methods);
    records = List.copyOf(records);
  }

  /** Field indices are JVMTI indices, not offsets or declaration positions supplied by GIR. */
  public record FieldInfo(int index, int modifiers, String name, String descriptor) {}

  public record ClassInfo(int id, ClassKind kind, String signature, List<FieldInfo> fields) {
    public ClassInfo {
      fields = List.copyOf(fields);
    }
  }

  /**
   * Diagnostic identity binding; creating it can force an array to escape. It retains no object and
   * may outlive the array, so its presence does not establish liveness.
   */
  public record ArrayIdentity(long tag, String shape, int worker, int site, long occurrence) {}

  /** Method tokens are opaque and local to the target process; they are not execution evidence. */
  public record MethodInfo(long token, String owner, String name, String descriptor) {}

  /**
   * Fixed-width native records. Unknown origins retain their raw tags and indices. The wire
   * contract is documented in {@code docs/observation.md}.
   */
  public record Record(Kind kind, long a, long b, long c, long d, long e, long f, long g) {}

  public enum Kind {
    OBJECT,
    REFERENCE,
    PRIMITIVE_FIELD,
    ROOT,
    ARRAY_VALUES,
    ARRAY_ELEMENT,
    CLASS_VISIT
  }
}
