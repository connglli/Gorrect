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
  public static final long LOCAL_VARIABLES = 32;

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
  public record MethodInfo(
      long token, String owner, String name, String descriptor, List<LocalVariable> locals) {
    public MethodInfo {
      locals = List.copyOf(locals);
    }

    public MethodInfo(long token, String owner, String name, String descriptor) {
      this(token, owner, name, descriptor, List.of());
    }
  }

  /** Compiler local-variable scope, in bytecode indices; it does not establish a live root. */
  public record LocalVariable(long start, int length, int slot, String name, String descriptor) {}

  /**
   * Fixed-width native record: kind followed by seven 64-bit cells. Unlisted cells are zero;
   * reference/root kinds and primitive type codes retain their JVMTI values.
   *
   * <ul>
   *   <li>{@link Kind#OBJECT}: a = tag, b = class ID (zero if unregistered), c = byte size, d =
   *       array length or -1.
   *   <li>{@link Kind#REFERENCE}: a = referrer tag, b = target tag, c = reference kind, d =
   *       field/element index or -1, e = referrer class tag.
   *   <li>{@link Kind#PRIMITIVE_FIELD}: a = owner tag, b = field index, c = primitive type, d =
   *       value bits, e = owner class tag.
   *   <li>{@link Kind#ROOT}: a = target tag, b = root kind, c = Java thread ID, d = frame depth, e
   *       = slot, f = opaque method token, g = raw VM method location (BCI when qualified).
   *       Unavailable thread/method values use zero; depth/slot/location use -1.
   *   <li>{@link Kind#ARRAY_VALUES}: a = tag, b = count, c = primitive element type. This records
   *       callback delivery, including empty arrays when supplied.
   *   <li>{@link Kind#ARRAY_ELEMENT}: a = tag, b = index, c = primitive type, d = value bits, e =
   *       class tag. The current producer records long elements.
   *   <li>{@link Kind#CLASS_VISIT}: a = registered class ID. The class must have been visited
   *       before absent static reference edges can establish null roots.
   * </ul>
   *
   * <p>Ordinary object tags are positive; registered class mirrors use the negative class ID. Tags
   * and method tokens identify entities only within their target JVM. Unknown origins retain their
   * raw values; missing fields do not supply a logical identity or binding.
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
