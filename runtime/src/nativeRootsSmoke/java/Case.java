import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.NativeRootPlan;
import io.connglli.gorrect.runtime.TargetSession;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/** Actual JNI roots, alias multiplicity, recovery and post-delete evidence on target toolchains. */
public final class Case {
  private static final long RUN = 73;
  private static long checkpoint;
  private static boolean retained;
  private static Path output;

  public static final class N {
    public final int idWorker = 0, idSite = 0;
    public final long idOccurrence;
    public N f;
    public int small = -7;
    public long wide = Long.MIN_VALUE + 42;

    N(long occurrence) {
      idOccurrence = occurrence;
    }
  }

  public static final class Roots {}

  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    output = Path.of(args[1]);
    Files.createDirectories(output);
    retained = args[2].equals("retained");
    if (retained) {
      var code =
          CodeEvidence.read(
              NativeObserver.bindCode(RUN, Case.class), RUN, CodeEvidence.Stage.STARTUP);
      require(code.status() == CodeEvidence.Status.VALID, "startup code");
    }
    NativeObserver.configure(
        List.of(
            new NativeObserver.Registration(N.class, HeapSnapshot.ClassKind.OBJECT),
            new NativeObserver.Registration(Object[].class, HeapSnapshot.ClassKind.REFERENCE_ARRAY),
            new NativeObserver.Registration(long[].class, HeapSnapshot.ClassKind.DATA_ARRAY),
            new NativeObserver.Registration(Roots.class, HeapSnapshot.ClassKind.ROOTS)));
    NativeObserver.configureNativeRoots(RUN, 4, NativeRootPlan.MAX_GENERATIONS, 100_000);
    execute(null);
    var closed =
        NativeObserver.captureNativeRoots(RUN, ++checkpoint, 100_000, 0, 1, null, null, true, true);
    persist(closed, "cleanup");
    var proof = NativeRootEvidence.read(closed.resources(), RUN, checkpoint);
    var raw = NativeObserver.decode(closed.snapshot(), RUN, checkpoint);
    require(
        proof.cleanup()
            && proof.complete()
            && proof.before().stream().allMatch(row -> row.handle() == 0),
        "empty table");
    require(globals(raw).isEmpty(), "post-delete zero workload globals");
    rejected(() -> NativeObserver.readNativeRoot(RUN, 0, N.class, "N"), "stale read");
    rejected(() -> NativeObserver.writeNativeRoot(RUN + 1, 0, null), "cross-run write");
    if (retained)
      require(
          CodeEvidence.read(NativeObserver.codeStatus(RUN, true), RUN, CodeEvidence.Stage.FINAL)
                  .status()
              == CodeEvidence.Status.VALID,
          "final code");
    System.out.println(
        "Native roots passed: " + args[2] + " on " + System.getProperty("java.vm.name"));
  }

  private static void execute(TargetSession ignored) throws Exception {
    var empty = capture();
    require(empty.before().stream().allMatch(row -> row.handle() == 0), "initially empty slots");
    initialize();
    System.gc();
    var first = capture();
    require(
        lastGraph.gcStartsBefore() > 0 && lastGraph.gcFinishesBefore() > 0,
        "completed actual GC while globals alone retain graph");
    require(first.before().get(0).tag() == first.before().get(1).tag(), "alias targets");
    require(
        globals(lastGraph).get(first.before().get(0).tag()) == 2, "alias callback multiplicity");
    require(
        globals(lastGraph).values().stream().mapToInt(Integer::intValue).sum() == 4,
        "complete raw globals");
    rejected(
        () ->
            NativeObserver.captureNativeRoots(RUN, checkpoint + 1, 1, 0, 1, null, null, true, true),
        "cleanup bracket quota");
    rejected(
        () ->
            NativeObserver.captureNativeRoots(
                RUN + 1, checkpoint + 1, 100_000, 0, 1, null, null, true, true),
        "cross-run cleanup");
    rejected(
        () ->
            NativeObserver.captureNativeRoots(
                RUN,
                checkpoint + 1,
                100_000,
                0,
                1,
                new Thread[] {Thread.currentThread()},
                new int[] {0},
                true,
                true),
        "cleanup participants");
    rejected(
        () ->
            NativeObserver.captureNativeRoots(
                RUN, checkpoint + 1, 100_000, 1, 1, null, new int[] {0}, false, false),
        "unpaired capture participants");
    rejected(() -> NativeObserver.writeNativeRoot(RUN + 1, 0, null), "cross-run");
    rejected(() -> NativeObserver.writeNativeRoot(RUN, 4, null), "slot bound");
    rejected(() -> NativeObserver.writeNativeRoot(RUN, 0, new Object()), "unsupported object");
    rejected(
        () -> NativeObserver.readNativeRoot(RUN, 2, Object[].class, "Other"),
        "wrong array nominal shape");
    rejected(
        () -> NativeObserver.readNativeRoot(RUN, 0, long[].class, "D"), "wrong asserted class");
    var unchanged = capture();
    require(first.before().equals(unchanged.before()), "rejected operations preserve slots");
    recoverAndClear();
    System.gc();
    require(capture().before().stream().allMatch(row -> row.handle() == 0), "null deletion");
    NativeObserver.writeNativeRoot(RUN, 0, new N(3));
    NativeObserver.writeNativeRoot(RUN, 0, new N(4));
    var replaced = capture();
    require(
        replaced.before().get(0).generation() > first.before().get(0).generation(),
        "monotone replacement acquisition");
    var recovered = (N) NativeObserver.readNativeRoot(RUN, 0, N.class, "N");
    require(recovered.idOccurrence == 4, "replacement target");
    Reference.reachabilityFence(recovered);
  }

  private static void initialize() {
    var n = new N(1);
    n.f = new N(2);
    Object[] refs = {n.f};
    long[] data = {Long.MIN_VALUE, Long.MAX_VALUE};
    NativeObserver.bindArrayIdentity(refs, "R", 0, 0, 5);
    NativeObserver.bindArrayIdentity(data, "D", 0, 0, 6);
    NativeObserver.writeNativeRoot(RUN, 0, n);
    NativeObserver.writeNativeRoot(RUN, 1, n);
    NativeObserver.writeNativeRoot(RUN, 2, refs);
    NativeObserver.writeNativeRoot(RUN, 3, data);
  }

  private static void recoverAndClear() {
    var n = (N) NativeObserver.readNativeRoot(RUN, 0, N.class, "N");
    var alias = (N) NativeObserver.readNativeRoot(RUN, 1, N.class, "N");
    var refs = (Object[]) NativeObserver.readNativeRoot(RUN, 2, Object[].class, "R");
    var data = (long[]) NativeObserver.readNativeRoot(RUN, 3, long[].class, "D");
    for (int slot = 0; slot < 4; slot++) NativeObserver.writeNativeRoot(RUN, slot, null);
    System.gc();
    require(
        n == alias && refs[0] == n.f && n.small == -7 && n.wide == Long.MIN_VALUE + 42,
        "recovered identity and mixed payload");
    require(data[0] == Long.MIN_VALUE && data[1] == Long.MAX_VALUE, "recovered data array");
    synchronized (n) {
      n.f.small = 17;
    }
    require(n.f.small == 17, "recovered monitor and field access");
    require(NativeObserver.readNativeRoot(RUN, 0, N.class, "N") == null, "typed null read");
    Reference.reachabilityFence(n);
    Reference.reachabilityFence(alias);
    Reference.reachabilityFence(refs);
    Reference.reachabilityFence(data);
  }

  private static HeapSnapshot lastGraph;

  private static NativeRootEvidence capture() throws Exception {
    var pair =
        NativeObserver.captureNativeRoots(
            RUN,
            ++checkpoint,
            100_000,
            1,
            checkpoint,
            retained ? new Thread[] {Thread.currentThread()} : null,
            retained ? new int[] {0} : null,
            false,
            false);
    persist(pair, Long.toString(checkpoint));
    lastGraph = NativeObserver.decode(pair.snapshot(), RUN, checkpoint);
    var proof = NativeRootEvidence.read(pair.resources(), RUN, checkpoint);
    require(
        proof.complete() && lastGraph.status() == HeapSnapshot.Status.COMPLETE,
        "paired complete capture");
    require(
        proof.startsBefore() == lastGraph.gcStartsBefore()
            && proof.finishesAfter() == lastGraph.gcFinishesAfter(),
        "same walk counters");
    var table = new HashMap<Long, Integer>();
    for (var row : proof.before()) if (row.tag() != 0) table.merge(row.tag(), 1, Integer::sum);
    require(table.equals(globals(lastGraph)), "exact raw root multiset");
    if (retained)
      require(
          FrameEvidence.read(pair.frames(), RUN, checkpoint).status() == CodeEvidence.Status.VALID,
          "retained frames");
    return proof;
  }

  private static HashMap<Long, Integer> globals(HeapSnapshot graph) {
    var roots = new HashMap<Long, Integer>();
    for (var record : graph.records())
      if (record.kind() == HeapSnapshot.Kind.ROOT && record.b() == 21)
        roots.merge(record.a(), 1, Integer::sum);
    return roots;
  }

  private static void persist(NativeObserver.NativeRootCapture pair, String name) throws Exception {
    Files.write(output.resolve(name + ".gsnap"), pair.snapshot());
    Files.write(output.resolve(name + ".gnative"), pair.resources());
    if (pair.frames() != null) Files.write(output.resolve(name + ".gframe"), pair.frames());
  }

  private static void rejected(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalStateException expected) {
      return;
    }
    throw new AssertionError("accepted " + message);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
