import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Real native queue-policy fixture; these resources are deliberately held for the whole case. */
public final class Case {
  private static final long RUN = 31;
  private static long checkpoint;
  private static boolean retained, automatic;
  private static Path output;

  public static final class Queue extends ReferenceQueue<Object> {
    public final int idQueue;

    public Queue(int identity) {
      idQueue = identity;
    }
  }

  public static final class QueueRoots {
    public static Queue q1, q2;
  }

  public static final class ZeroRoots {}

  public static final class W extends WeakReference<Object> {
    public final int idWorker = 0, idSite = 1, idSlot = -1;
    public final long idOccurrence;

    public W(long identity, ReferenceQueue<Object> queue) {
      this(identity, null, queue);
    }

    public W(long identity, Object target, ReferenceQueue<Object> queue) {
      super(target, queue);
      idOccurrence = identity;
    }
  }

  public static final class Roots {
    public static W held;
  }

  public static final class AutomaticRoots {
    public static W first, second;
  }

  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    output = Path.of(args[1]);
    Files.createDirectories(output);
    String mode = args[2];
    automatic = mode.startsWith("automatic");
    retained = mode.equals("retained") || mode.equals("automatic-retained");
    if (automatic)
      Files.writeString(
          output.resolve("caller.txt"), Long.toString(Thread.currentThread().getId()));
    if (retained) {
      var code =
          CodeEvidence.read(
              NativeObserver.bindCode(RUN, Case.class), RUN, CodeEvidence.Stage.STARTUP);
      require(code.status() == CodeEvidence.Status.VALID, "retained code binding");
    }
    NativeObserver.configure(
        List.of(
            new NativeObserver.Registration(W.class, HeapSnapshot.ClassKind.WEAK_REFERENCE),
            new NativeObserver.Registration(
                automatic ? AutomaticRoots.class : Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    if (mode.equals("wrong-type")) {
      try {
        NativeObserver.configureQueues(QueueRoots.class, Object.class);
        throw new AssertionError("Invalid queue hierarchy accepted");
      } catch (IllegalStateException expected) {
        // Failed registration must close capture rather than restore a partial graph-only mode.
      }
      try {
        NativeObserver.capture(RUN, 1, 1000);
        throw new AssertionError("Failed queue setup allowed graph fallback");
      } catch (IllegalStateException expected) {
        return;
      }
    }
    boolean zero = mode.equals("zero");
    NativeObserver.configureQueues(
        zero ? ZeroRoots.class : QueueRoots.class, Queue.class, automatic);
    if (automatic) {
      if (mode.equals("automatic-blocked")) blockedAcquisition();
      else automatic();
      return;
    }
    if (mode.equals("duplicate")) {
      var method =
          NativeObserver.class.getDeclaredMethod(
              "configureQueues0", Class.class, Class.class, boolean.class);
      method.setAccessible(true);
      try {
        method.invoke(null, QueueRoots.class, Queue.class, false);
        throw new AssertionError("Repeated native queue registration accepted");
      } catch (InvocationTargetException expected) {
        require(expected.getCause() instanceof IllegalStateException, "duplicate failure cause");
      }
    }
    if (zero) {
      var pair = capture(100_000);
      require(type(pair[1], "LCase$ZeroRoots;").fields().isEmpty(), "zero-resource metadata");
      return;
    }
    var initial = capture(100_000);
    var rootType = type(initial[1], "LCase$QueueRoots;");
    require(
        initial[1].records().stream()
            .anyMatch(
                record ->
                    record.kind() == HeapSnapshot.Kind.CLASS_VISIT && record.a() == rootType.id()),
        "all-null static root owner visit");
    require(
        reference(initial[1], -rootType.id(), field(rootType, "q1"), 8) == 0,
        "actual initially null resource");
    QueueRoots.q1 = new Queue(1);
    QueueRoots.q2 = new Queue(2);
    if (mode.equals("resource-quota")) {
      var limited = capture(16);
      require(
          limited[0].status() == HeapSnapshot.Status.COMPLETE
              && limited[1].status() == HeapSnapshot.Status.RECORD_LIMIT,
          "resource-only quota preserves complete graph");
      return;
    }
    var first = new W(1, QueueRoots.q1);
    var second = new W(2, QueueRoots.q1);
    var other = new W(3, QueueRoots.q2);
    require(first.enqueue() && second.enqueue() && other.enqueue(), "actual enqueue");
    Roots.held = first;
    if (mode.equals("graph-quota")) {
      var limited = capture(1);
      require(
          limited[0].status() == HeapSnapshot.Status.RECORD_LIMIT
              && limited[1].status() == HeapSnapshot.Status.RECORD_LIMIT,
          "global quota invalidates both buffers");
      return;
    }
    var before = capture(100_000);
    checkMetadata(before);
    long q1 = resource(before[1], "q1", 1);
    long q2 = resource(before[1], "q2", 2);
    require(q1 != q2, "distinct actual resources");
    require(memberIds(before[1], q1).equals(Set.of(1L, 2L)), "actual first membership");
    require(memberIds(before[1], q2).equals(Set.of(3L)), "actual second membership");
    W polled = (W) QueueRoots.q1.poll();
    require(polled != null, "actual poll");
    var after = capture(100_000);
    require(
        memberIds(after[1], resource(after[1], "q1", 1))
            .equals(Set.of(polled.idOccurrence == 1 ? 2L : 1L)),
        "membership after actual poll");
    var alien = new WeakReference<Object>(null, QueueRoots.q1);
    require(alien.enqueue(), "unknown actual entry enqueue");
    var unknown = capture(100_000);
    long actual = resource(unknown[1], "q1", 1);
    var queueType = type(unknown[1], "LCase$Queue;");
    long tag;
    if (queueType.fields().stream().anyMatch(f -> f.name().equals("queueLength"))) {
      tag = reference(unknown[1], actual, field(queueType, "head"), 2);
    } else {
      long array = reference(unknown[1], actual, field(queueType, "references"), 2);
      long length = object(unknown[1], array).d();
      long tail = primitive(unknown[1], actual, field(queueType, "tail"));
      tag = reference(unknown[1], array, (tail + length - 1) % length, 3);
    }
    require(tag != 0 && object(unknown[1], tag).b() == 0, "unknown active entry is visible");
    Reference.reachabilityFence(first);
    Reference.reachabilityFence(second);
    Reference.reachabilityFence(other);
    Reference.reachabilityFence(polled);
    Reference.reachabilityFence(alien);
    if (retained) {
      var code =
          CodeEvidence.read(NativeObserver.codeStatus(RUN, true), RUN, CodeEvidence.Stage.FINAL);
      require(code.status() == CodeEvidence.Status.VALID, "retained completion");
    }
    System.out.println(
        "Queue capture passed: " + mode + " on " + System.getProperty("java.vm.name"));
  }

  private static void blockedAcquisition() throws Exception {
    QueueRoots.q1 = new Queue(1);
    QueueRoots.q2 = new Queue(2);
    var field = ReferenceQueue.class.getDeclaredField("lock");
    field.setAccessible(true); // Test-only setup; this opening is never a production-admitted flag.
    Object lock = field.get(QueueRoots.q2);
    var ready = new java.util.concurrent.CountDownLatch(1);
    var holder =
        new Thread(
            () -> {
              synchronized (lock) {
                ready.countDown();
                try {
                  new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    holder.setDaemon(true);
    holder.start();
    ready.await();
    Files.writeString(output.resolve("waiting.txt"), "real private second-queue lock is held");
    capture(100_000);
    throw new AssertionError("Blocked actual queue acquisition unexpectedly completed");
  }

  private static void automatic() throws Exception {
    QueueRoots.q1 = new Queue(1);
    QueueRoots.q2 = new Queue(2);
    AutomaticRoots.first = new W(1, new Object(), QueueRoots.q1);
    AutomaticRoots.second = new W(2, new Object(), QueueRoots.q2);
    boolean arrived = false;
    for (int attempt = 0; attempt < 50; attempt++) {
      System.gc();
      Thread.sleep(20);
      var pair = capture(100_000);
      if (memberIds(pair[1], resource(pair[1], "q1", 1)).equals(Set.of(1L))
          && memberIds(pair[1], resource(pair[1], "q2", 2)).equals(Set.of(2L))) {
        arrived = true;
        break;
      }
    }
    require(arrived, "bounded actual GC-handler notification experiment");
    var partial = capture(1);
    require(
        partial[0].status() == HeapSnapshot.Status.RECORD_LIMIT
            && partial[1].status() == HeapSnapshot.Status.RECORD_LIMIT,
        "actual automatic quota cannot certify membership");
    capture(100_000); // Quota did not leak monitors; a later cut still obtains real custody.
    if (retained) {
      var code =
          CodeEvidence.read(NativeObserver.codeStatus(RUN, true), RUN, CodeEvidence.Stage.FINAL);
      require(code.status() == CodeEvidence.Status.VALID, "automatic retained completion");
    }
    System.out.println("Automatic lock capture passed: " + (retained ? "retained" : "jvmti"));
  }

  private static HeapSnapshot[] capture(int limit) throws Exception {
    long sequence = ++checkpoint;
    byte[] graph, resources, consistency = null;
    if (retained) {
      var result =
          NativeObserver.captureRetained(
              RUN, sequence, limit, new Thread[] {Thread.currentThread()}, new int[] {0}, true);
      graph = result.snapshot();
      resources = result.resources();
      consistency = result.consistency();
      require(
          FrameEvidence.read(result.frames(), RUN, sequence).status() == CodeEvidence.Status.VALID,
          "retained frame status");
      Files.write(output.resolve(sequence + ".gframe"), result.frames());
    } else {
      var result = NativeObserver.captureQueues(RUN, sequence, limit);
      graph = result.snapshot();
      resources = result.resources();
      consistency = result.consistency();
    }
    Files.write(output.resolve(sequence + ".gsnap"), graph);
    Files.write(output.resolve(sequence + ".gqueue"), resources);
    var normal = NativeObserver.decode(graph, RUN, sequence);
    var queue = QueueCapture.read(resources, RUN, sequence).snapshot();
    if (automatic) {
      require(consistency != null, "source-selected actual custody bytes");
      Files.write(output.resolve(sequence + ".gqlock"), consistency);
      var proof = QueueConsistency.read(consistency, RUN, sequence);
      require(
          proof.complete()
              && proof.thread() == Thread.currentThread().getId()
              && proof.locks().size() == 2,
          "actual all-resource caller custody");
      if (queue.status() == HeapSnapshot.Status.COMPLETE) {
        require(
            queue.records().stream()
                .anyMatch(
                    record ->
                        record.kind() == HeapSnapshot.Kind.ROOT
                            && record.a() == proof.threadTag()
                            && record.b() == 25
                            && record.c() == proof.thread()),
            "same-walk actual current-thread JNI-local origin");
        var threadType = type(queue, "Ljava/lang/Thread;");
        require(
            object(queue, proof.threadTag()).b() == threadType.id()
                && queue.records().stream()
                    .anyMatch(
                        record ->
                            record.kind() == HeapSnapshot.Kind.REFERENCE
                                && record.a() == proof.threadTag()
                                && record.b() == -threadType.id()
                                && record.c() == 1),
            "actual caller-specific Thread class provenance");
        var lockType = type(queue, "Ljava/lang/ref/ReferenceQueue$Lock;");
        var roots = type(queue, "LCase$QueueRoots;");
        var queueType = type(queue, "LCase$Queue;");
        for (var lock : proof.locks()) {
          require(
              lock.rootsClass() == roots.id()
                  && lock.rootField() == field(roots, "q" + lock.resource())
                  && reference(queue, -roots.id(), lock.rootField(), 8) == lock.queueTag()
                  && reference(queue, lock.queueTag(), field(queueType, "lock"), 2)
                      == lock.lockTag()
                  && object(queue, lock.lockTag()).b() == lockType.id(),
              "same-walk actual root-to-lock custody");
        }
      }
    }
    require(
        normal.gcStartsBefore() == queue.gcStartsBefore()
            && normal.gcFinishesBefore() == queue.gcFinishesBefore()
            && normal.gcStartsAfter() == queue.gcStartsAfter()
            && normal.gcFinishesAfter() == queue.gcFinishesAfter(),
        "same-walk GC counters");
    return new HeapSnapshot[] {normal, queue};
  }

  private static void checkMetadata(HeapSnapshot[] pair) {
    for (var type : pair[0].classes()) {
      var other = type(pair[1], type.signature());
      require(
          other.id() == type.id()
              && other.kind() == type.kind()
              && other.fields().equals(type.fields()),
          "shared graph metadata");
    }
    require(
        pair[0].classes().stream().noneMatch(type -> type.signature().equals("LCase$Queue;")),
        "resource class stays opaque in graph");
    for (var record : pair[0].records())
      if (record.kind() == HeapSnapshot.Kind.OBJECT && record.b() == 1)
        require(object(pair[1], record.a()).b() == 1, "same physical wrapper tag");
  }

  private static long resource(HeapSnapshot snapshot, String name, int id) {
    var roots = type(snapshot, "LCase$QueueRoots;");
    long tag = reference(snapshot, -roots.id(), field(roots, name), 8);
    var type = type(snapshot, "LCase$Queue;");
    require(
        tag > 0
            && object(snapshot, tag).b() == type.id()
            && primitive(snapshot, tag, field(type, "idQueue")) == id,
        "actual queue identity");
    return tag;
  }

  private static Set<Long> memberIds(HeapSnapshot snapshot, long queue) {
    var type = type(snapshot, "LCase$Queue;");
    var ids = new HashSet<Long>();
    if (type.fields().stream().anyMatch(field -> field.name().equals("queueLength"))) {
      long expected = primitive(snapshot, queue, field(type, "queueLength"));
      long tag = reference(snapshot, queue, field(type, "head"), 2);
      var wrapper = type(snapshot, "LCase$W;");
      var base = type(snapshot, "Ljava/lang/ref/ReferenceQueue;");
      long sentinel = reference(snapshot, -base.id(), field(base, "ENQUEUED"), 8);
      while (tag != 0) {
        require(
            ids.add(primitive(snapshot, tag, field(wrapper, "idOccurrence"))), "duplicate member");
        require(
            reference(snapshot, tag, field(wrapper, "queue"), 2) == sentinel, "actual sentinel");
        long next = reference(snapshot, tag, field(wrapper, "next"), 2);
        if (next == tag) break;
        require(next > 0 && ids.size() <= 4, "linked queue termination");
        tag = next;
      }
      require(ids.size() == expected, "linked length coverage");
    } else {
      long array = reference(snapshot, queue, field(type, "references"), 2);
      long head = primitive(snapshot, queue, field(type, "head"));
      long tail = primitive(snapshot, queue, field(type, "tail"));
      long empty = primitive(snapshot, queue, field(type, "empty"));
      long length = object(snapshot, array).d();
      require(
          type(snapshot, "[Ljava/lang/ref/Reference;").id() == object(snapshot, array).b()
              && length > 0,
          "actual helper metadata");
      long count = empty == 1 ? 0 : head == tail ? length : (tail - head + length) % length;
      for (long i = 0; i < count; i++) {
        long tag = reference(snapshot, array, (head + i) % length, 3);
        var wrapper = type(snapshot, "LCase$W;");
        require(ids.add(primitive(snapshot, tag, field(wrapper, "idOccurrence"))), "ring member");
      }
    }
    return Set.copyOf(ids);
  }

  private static HeapSnapshot.ClassInfo type(HeapSnapshot snapshot, String name) {
    return snapshot.classes().stream()
        .filter(type -> type.signature().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing metadata " + name));
  }

  private static int field(HeapSnapshot.ClassInfo type, String name) {
    return type.fields().stream()
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing field " + name))
        .index();
  }

  private static HeapSnapshot.Record object(HeapSnapshot snapshot, long tag) {
    return snapshot.records().stream()
        .filter(record -> record.kind() == HeapSnapshot.Kind.OBJECT && record.a() == tag)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing object " + tag));
  }

  private static long primitive(HeapSnapshot snapshot, long owner, long field) {
    return snapshot.records().stream()
        .filter(
            record ->
                record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                    && record.a() == owner
                    && record.b() == field)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing primitive " + owner + ":" + field))
        .d();
  }

  private static long reference(HeapSnapshot snapshot, long owner, long field, long kind) {
    return snapshot.records().stream()
        .filter(
            record ->
                record.kind() == HeapSnapshot.Kind.REFERENCE
                    && record.a() == owner
                    && record.d() == field
                    && record.c() == kind)
        .findFirst()
        .map(HeapSnapshot.Record::b)
        .orElse(0L);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
