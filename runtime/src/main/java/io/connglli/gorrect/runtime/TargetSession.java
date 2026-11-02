package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CaptureEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.NativeRootPlan;
import io.connglli.gorrect.protocol.ObserverPlan;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.protocol.QueuePlan;
import io.connglli.gorrect.protocol.ReferenceHistory;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main-thread endpoint for one generated case. Open it before graph construction, execute and
 * coordinate workers, capture the final checkpoint, then call {@link #complete} and close it.
 * Generated failure paths call {@link #fail} before closing instead.
 *
 * <p>Control and persistence methods belong to the main thread. Worker observations go through
 * their {@link WorkerContext}; worker buffers are read only while paused at a coordinated cut or
 * after joining. Session bookkeeping retains no workload object references.
 */
public final class TargetSession implements AutoCloseable, ObservationRecorder {
  private final SocketChannel socket;
  private final DataInputStream in;
  private final DataOutputStream out;
  private final long run;
  private final Path directory;
  private final int records;
  private final Map<Integer, TraceRecorder> recorders = new TreeMap<>();
  private final List<ExecutionEvidence.Group> groups = new ArrayList<>();
  private final List<ExecutionEvidence.Window> windows = new ArrayList<>();
  private VmControl control;
  private final boolean referenceRecording;
  private final boolean retained;
  private boolean codeBound, codeFinalizationAttempted;
  private final boolean typedReferences;
  private final QueuePlan queuePlan;
  private final NativeRootPlan nativeRootPlan;
  private boolean nativeRootsConfigured, nativeCleanupAttempted;
  private final QueueRecorder queueRecorder;
  private final Map<Integer, Long> visits = new HashMap<>();
  private long checkpoint;
  private long collections;
  private boolean complete;
  private final BindingRecorder bindings;
  private final int bindingCapacity;
  private int callDepth;

  private TargetSession(String[] args, Class<?> intended) throws IOException {
    this(args, intended, null, null, null);
  }

  private TargetSession(
      String[] args,
      Class<?> intended,
      List<NativeObserver.Registration> registrations,
      Class<?> queueRoots,
      Class<?> queueType)
      throws IOException {
    this(args, intended, registrations, queueRoots, queueType, null);
  }

  private TargetSession(
      String[] args,
      Class<?> intended,
      List<NativeObserver.Registration> registrations,
      Class<?> queueRoots,
      Class<?> queueType,
      Boolean objectClones)
      throws IOException {
    if (args.length != 6) throw new IllegalArgumentException("Invalid target launch arguments");
    run = Long.parseLong(args[1]);
    directory = Path.of(args[2]);
    records = Integer.parseInt(args[4]);
    int capacity = Integer.parseInt(args[5]);
    if (run <= 0 || capacity <= 0 || capacity > ExecutionTrace.MAX_EVENTS)
      throw new IllegalArgumentException("Invalid trace capacity or run token");

    retained = retainedPlan(directory);
    referenceRecording = Files.exists(directory.resolve("generated/references.properties"));
    typedReferences = Files.exists(directory.resolve("generated/typed-references.properties"));
    Path queues = directory.resolve("generated/queues.properties");
    queuePlan = Files.exists(queues) ? QueuePlan.read(queues) : null;
    Path nativePlan = directory.resolve("generated/native-roots.properties");
    nativeRootPlan = Files.exists(nativePlan) ? NativeRootPlan.read(nativePlan) : null;
    queueRecorder = queuePlan == null ? null : new QueueRecorder(capacity);
    boolean localBindings =
        !System.getProperty("gorrect.observation", "named-roots-diagnostic")
            .equals("named-roots-diagnostic");
    bindingCapacity = localBindings ? BindingEvidence.MAX_BINDINGS : 0;
    bindings = new BindingRecorder(bindingCapacity);
    recorders(capacity);

    socket = SocketChannel.open(UnixDomainSocketAddress.of(args[0]));
    in = new DataInputStream(Channels.newInputStream(socket));
    out = new DataOutputStream(Channels.newOutputStream(socket));

    boolean providerSetup = false, queueSetup = false, nativeSetup = false;
    try {
      System.load(args[3]);
      providerSetup = retained;
      if (retained != NativeObserver.retainedMode())
        throw new IllegalStateException("Observer plan and native metadata provider disagree");
      if (retained) {
        if (intended == null)
          throw new IllegalStateException("Retained metadata requires the intended Case mirror");
        byte[] bytes = NativeObserver.bindCode(run, intended);
        codeBound = true;
        Files.write(directory.resolve("traces/code.gcode"), bytes);
        var code = CodeEvidence.read(bytes, run, CodeEvidence.Stage.STARTUP);
        checkCodeStatus(code);
      }
      providerSetup = false;
      queueSetup = queuePlan != null;
      Properties queueFacts = new Properties();
      if (queuePlan != null) {
        if (intended == null
            || queueRoots == null
            || queueType == null
            || registrations == null
            || !intended.getName().equals("Case")
            || !queueRoots.getName().equals("Case$QueueRoots")
            || intended.getClassLoader() != ClassLoader.getSystemClassLoader()
            || queueRoots.getClassLoader() != intended.getClassLoader()
            || !queueType.getName().equals("Case$Queue")
            || queueType.getClassLoader() != intended.getClassLoader()
            || queueType.getSuperclass() != java.lang.ref.ReferenceQueue.class)
          throw new IllegalStateException(
              "Named queues require intended system-defined resource metadata");
        NativeObserver.configure(registrations);
        NativeObserver.configureQueues(queueRoots, queueType, queuePlan.automatic());
        queueFacts = QueueQualification.exercise(queuePlan.automatic());
      } else if (nativeRootPlan != null) {
        nativeSetup = true;
        if (intended == null
            || registrations == null
            || queueRoots != null
            || queueType != null
            || !intended.getName().equals("Case")
            || intended.getClassLoader() != ClassLoader.getSystemClassLoader())
          throw new IllegalStateException(
              "Native roots require intended system-defined graph metadata");
        NativeObserver.configure(registrations);
        NativeObserver.configureNativeRoots(
            run, nativeRootPlan.roots().size(), NativeRootPlan.MAX_GENERATIONS, records);
        nativeRootsConfigured = true;
        nativeSetup = false;
      } else if (registrations != null || queueRoots != null || queueType != null)
        throw new IllegalStateException("Resource setup supplied without source selection");

      queueSetup = false;
      control = VmControl.open(directory, run);

      var properties = VmFacts.read();
      properties.putAll(queueFacts);
      if (objectClones != null)
        properties.setProperty("observer.objectClones.qualified", objectClones.toString());
      if (nativeRootPlan != null)
        properties.setProperty(
            "observer.nativeRoots.qualified", Boolean.toString(nativeRootsConfigured));
      properties.setProperty("main.thread", Long.toString(Thread.currentThread().getId()));
      properties.setProperty("observer.capabilities", Long.toString(NativeObserver.capabilities()));
      properties.setProperty("observer.bindingMetadata", retained ? "retained-class" : "jvmti");
      properties.setProperty("observer.retained.ready", Boolean.toString(retained && codeBound));
      properties.setProperty("jit.problem", control.problem());
      properties.setProperty("gc.control", control.collectionControl());
      properties.setProperty("gc.problem", control.gcProblem());
      properties.setProperty("gc.evidence", Boolean.toString(control.collectionEvidence()));

      try (var stream = Files.newOutputStream(directory.resolve("target.properties"))) {
        properties.store(stream, "Target identity");
      }

      send(ControlMessage.Kind.HELLO, 0, 0, "");
      acknowledge(0);
    } catch (Throwable failure) {
      try {
        cleanupNativeRoots();
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      try {
        finishCode(false);
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      try {
        send(
            ControlMessage.Kind.FAILED,
            0,
            0,
            (nativeSetup
                    && (failure instanceof IllegalArgumentException
                        || failure instanceof IllegalStateException)
                ? ControlMessage.NATIVE_ROOTS_UNSUPPORTED + failureDetail(failure)
                : startupFailureDetail(providerSetup, queueSetup, failure)));
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      try {
        if (control != null) control.close();
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      try {
        socket.close();
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private static boolean retainedPlan(Path directory) throws IOException {
    Path plan = directory.resolve("generated/observer.properties");
    if (!Files.exists(plan)) return false;
    return ObserverPlan.read(plan).bindingMetadata().equals("retained-class");
  }

  /**
   * Loads the observer and VM bridge, writes startup facts, and waits for engine acknowledgement.
   * Failure closes any opened endpoint and bridge. The caller owns the returned session. Retained
   * metadata requires the overload that supplies the actual generated class mirror.
   *
   * @param args socket path, positive run ID, bundle directory, observer library, capture-record
   *     limit, and total trace capacity, in that order
   */
  public static TargetSession open(String[] args) throws IOException {
    return new TargetSession(args, null);
  }

  /**
   * Opens with the actual generated class mirror. Retained mode binds and persists its code
   * certificate before HELLO; initialization remains blocked until the engine validates it. The
   * mirror is a startup argument only and is not stored in the returned session.
   */
  public static TargetSession open(String[] args, Class<?> intended) throws IOException {
    return new TargetSession(args, Objects.requireNonNull(intended));
  }

  /** Records the generated classes' real shallow-copy qualification before startup admission. */
  public static TargetSession openObjectClones(String[] args, Class<?> intended, boolean qualified)
      throws IOException {
    return new TargetSession(args, Objects.requireNonNull(intended), null, null, null, qualified);
  }

  /** Opens a source-selected named-queue case, configuring copied metadata before HELLO/ACK. */
  public static TargetSession open(
      String[] args,
      Class<?> intended,
      List<NativeObserver.Registration> registrations,
      Class<?> queueRoots,
      Class<?> queueType)
      throws IOException {
    return new TargetSession(
        args,
        Objects.requireNonNull(intended),
        List.copyOf(registrations),
        Objects.requireNonNull(queueRoots),
        Objects.requireNonNull(queueType));
  }

  /** Opens a source-selected native-root case before constructing workload objects. */
  public static TargetSession openNative(
      String[] args, Class<?> intended, List<NativeObserver.Registration> registrations)
      throws IOException {
    return new TargetSession(
        args, Objects.requireNonNull(intended), List.copyOf(registrations), null, null);
  }

  /** Publishes a deliberate native root without retaining a Java shadow. */
  public void writeNativeRoot(int slot, Object value) {
    if (!nativeRootsConfigured || nativeCleanupAttempted)
      throw new IllegalStateException("Native roots are not open");
    NativeObserver.writeNativeRoot(run, slot, value);
  }

  /** Returns an independent strong handle with the asserted nominal shape. */
  public Object readNativeRoot(int slot, Class<?> type, String shape) {
    if (!nativeRootsConfigured || nativeCleanupAttempted)
      throw new IllegalStateException("Native roots are not open");
    return NativeObserver.readNativeRoot(run, slot, type, shape);
  }

  @Override
  public void queue(
      int operation,
      int kind,
      int queue,
      int result,
      int creator,
      int site,
      long occurrence,
      int slot) {
    if (queueRecorder == null) throw new IllegalStateException("Queue evidence was not selected");
    queueRecorder.record(
        recorders.get(0).size(), operation, kind, queue, result, creator, site, occurrence, slot);
  }

  /** Checks entry capacity before recording or changing the primitive logical call depth. */
  public void callEnter(int operation) {
    if (!retained || operation <= 0 || callDepth >= 128)
      throw new IllegalStateException("Invalid retained helper entry");
    recorders.get(0).requireCapacity();
    record(operation, ExecutionTrace.Kind.CALL_ENTER.ordinal(), 0L);
    callDepth++;
  }

  /** Budget-independent cleanup; generated code records normal exit only after this returns. */
  public void callLeave() {
    if (callDepth <= 0) throw new IllegalStateException("No active helper");
    callDepth--;
  }

  /**
   * Records without allocation or serialization. Kind is an ExecutionTrace.Kind ordinal; exhaustion
   * stops the case before it can claim a complete trace.
   */
  public void record(int operation, int kind, long value) {
    recorders.get(0).record(operation, kind, value, 0, 0, 0);
  }

  /** Main-thread observations use recorder zero; worker contexts write to their own recorders. */
  public void reference(
      int operation,
      int kind,
      long value,
      int strength,
      int wrapperWorker,
      int wrapperSite,
      long wrapperOccurrence,
      int wrapperSlot,
      int targetWorker,
      int targetSite,
      long targetOccurrence) {
    recorders
        .get(0)
        .reference(
            operation,
            kind,
            value,
            strength,
            wrapperWorker,
            wrapperSite,
            wrapperOccurrence,
            wrapperSlot,
            targetWorker,
            targetSite,
            targetOccurrence,
            0,
            0,
            0);
  }

  @Override
  public void referenceTyped(
      int operation,
      int kind,
      long value,
      int strength,
      int wrapperWorker,
      int wrapperSite,
      long wrapperOccurrence,
      int wrapperSlot,
      int targetWorker,
      int targetSite,
      long targetOccurrence,
      int targetKind,
      int targetSlot) {
    if (!typedReferences) throw new IllegalStateException("Tagged references were not selected");
    recorders
        .get(0)
        .referenceTyped(
            operation,
            kind,
            value,
            strength,
            wrapperWorker,
            wrapperSite,
            wrapperOccurrence,
            wrapperSlot,
            targetWorker,
            targetSite,
            targetOccurrence,
            targetKind,
            targetSlot,
            0,
            0,
            0);
  }

  private void recorders(int capacity) throws IOException {
    var bounds = new Properties();
    Path plan = directory.resolve("generated/recorders.properties");
    if (Files.exists(plan))
      try (var input = Files.newInputStream(plan)) {
        bounds.load(input);
      }
    else bounds.setProperty("0", Integer.toString(capacity));

    int remaining = capacity;
    var ids = bounds.stringPropertyNames().stream().map(Integer::parseInt).sorted().toList();
    if (!ids.contains(0)) throw new IOException("Recorder plan omits the main worker");

    // Counts only size storage; writeTrace emits actual appends, never unused or predicted records.
    for (int id : ids) {
      int requested = Integer.parseInt(bounds.getProperty(Integer.toString(id)));
      if (id < 0 || id > 64 || requested < 0 || requested > ExecutionTrace.MAX_EVENTS)
        throw new IOException("Invalid recorder bound");
      int size = Math.min(requested, remaining);
      remaining -= size;
      recorders.put(id, new TraceRecorder(id, size, referenceRecording));
    }
  }

  /** A generated worker entry point, invoked once on its own thread for this group visit. */
  @FunctionalInterface
  public interface WorkerTask {
    /** Executes with its private recorder; generated code must bracket the body with begin/end. */
    void run(WorkerContext worker) throws Exception;
  }

  /**
   * Prepares and runs one parallel-block visit, publishing prior construction through Thread.start
   * and joining every worker before return. IDs and tasks are aligned arrays; owner declares each
   * generated worker method used for compilation preparation.
   *
   * <p>Each task must record normal completion through its context. A worker failure cancels peer
   * rendezvous; the session joins peers and rethrows the first observed failure. Every worker must
   * execute matching startup, barrier/capture, and completion rendezvous. The main thread owns
   * native capture and control acknowledgements while every worker remains paused. Main-thread
   * interruption cancels and joins workers before propagating. Once execution starts, group/window
   * evidence is retained for both successful and failed visits.
   */
  public void parallel(int operation, int[] ids, WorkerTask[] tasks, Class<?> owner)
      throws Exception {
    try {
      parallelVisit(operation, ids, tasks, owner);
    } finally {
      bindings.clear();
    }
  }

  private void parallelVisit(int operation, int[] ids, WorkerTask[] tasks, Class<?> owner)
      throws Exception {
    if (operation <= 0 || ids.length != tasks.length || ids.length == 0 || ids.length > 16)
      throw new IllegalArgumentException("Invalid worker group");

    var unique = new HashSet<Integer>();
    for (int id : ids)
      if (id <= 0 || id > 64 || !unique.add(id) || !recorders.containsKey(id))
        throw new IllegalArgumentException("Invalid, duplicate, or missing worker recorder");

    long round = visits.merge(operation, 1L, Long::sum);
    Method[] methods = new Method[ids.length];
    for (int index = 0; index < ids.length; index++)
      methods[index] =
          owner.getDeclaredMethod(
              ExecutionEvidence.workerMethod(ids[index]), TargetSession.class, WorkerContext.class);

    var compilations = control.prepare(round, ids, methods);

    var rendezvous = new WorkerRendezvous(ids.length, retained);
    var failure = new AtomicReference<Throwable>();
    var contexts = new ArrayList<WorkerContext>();
    var threads = new ArrayList<Thread>();

    for (int index = 0; index < ids.length; index++) {
      int id = ids[index];
      var recorder = recorders.get(id);
      if (recorder == null) throw new IllegalStateException("Missing recorder for worker " + id);
      var context =
          new WorkerContext(
              recorder, rendezvous, control, id, operation, round, index, bindingCapacity);
      contexts.add(context);
      var task = tasks[index];
      threads.add(
          new Thread(
              () -> {
                try {
                  task.run(context);
                  if (!context.ended())
                    throw new IllegalStateException("Worker omitted completion");
                } catch (Throwable error) {
                  failure.compareAndSet(null, error);
                  rendezvous.cancel();
                }
              },
              "gorrect-worker-" + id + "-" + round));
    }

    long gcBefore = NativeObserver.gcFinishes();
    boolean joined = false;
    try {
      for (var thread : threads) thread.start();
      int cut = 0;
      while (true) {
        int kind = rendezvous.await();
        if (kind == WorkerRendezvous.CAPTURE) {
          int ordinal = ++cut;
          if (retained)
            rendezvous.capture(
                () ->
                    captureWorkers(operation, round, ordinal, ids, contexts, threads, rendezvous));
          else captureWorkers(operation, round, ordinal, ids, contexts, threads, rendezvous);
        }
        rendezvous.release();
        if (kind == WorkerRendezvous.END) break;
      }
      for (int index = 0; index < threads.size(); index++) {
        threads.get(index).join();
        record(operation, ExecutionTrace.Kind.JOIN.ordinal(), ids[index]);
      }
      joined = failure.get() == null;
    } catch (Exception | Error error) {
      Throwable first = failure.get();
      rendezvous.cancel();
      threads.forEach(Thread::interrupt);
      joinCancelled(threads);
      if (first instanceof Exception workerFailure) throw workerFailure;
      if (first instanceof Error workerFailure) throw workerFailure;
      throw error;
    } finally {
      long gcAfter = NativeObserver.gcFinishes();
      groups.add(
          new ExecutionEvidence.Group(
              operation,
              round,
              Arrays.stream(ids).boxed().toList(),
              joined,
              gcBefore,
              gcAfter,
              compilations));
      contexts.forEach(context -> windows.addAll(context.windows()));
    }

    if (failure.get() instanceof Exception error) throw error;

    if (failure.get() instanceof Error error) throw error;
  }

  private void captureWorkers(
      int group,
      long round,
      int cut,
      int[] ids,
      List<WorkerContext> contexts,
      List<Thread> threads,
      WorkerRendezvous rendezvous)
      throws IOException {
    checkpoint++;
    var participants = new ArrayList<CaptureEvidence.Participant>();
    participants.add(
        new CaptureEvidence.Participant(
            0, group, round, Thread.currentThread().getId(), 1, bindings.snapshot()));
    for (int index = 0; index < ids.length; index++) {
      int operation = rendezvous.operation(index);
      long occurrence = visits.merge(operation, 1L, Long::sum);
      participants.add(
          new CaptureEvidence.Participant(
              ids[index],
              operation,
              occurrence,
              rendezvous.thread(index),
              round,
              contexts.get(index).bindings()));
    }
    new CaptureEvidence(run, checkpoint, group, round, cut, rendezvous.phase(), participants)
        .write(directory.resolve("snapshots/" + checkpoint + ".gcut"));
    if (retained) {
      var controls = new Thread[threads.size() + 1];
      controls[0] = Thread.currentThread();
      for (int index = 0; index < threads.size(); index++) controls[index + 1] = threads.get(index);
      int[] workers = new int[ids.length + 1];
      System.arraycopy(ids, 0, workers, 1, ids.length);
      capture(controls, workers, false);
    } else capture(null, null, false);
    var canonical = participants.get(1);
    send(ControlMessage.Kind.CHECKPOINT, canonical.operation(), canonical.occurrence(), "");
    acknowledge(checkpoint);
  }

  private static void joinCancelled(List<Thread> threads) {
    boolean interrupted = Thread.interrupted();
    for (var thread : threads)
      while (thread.isAlive()) {
        try {
          thread.join();
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
    if (interrupted) Thread.currentThread().interrupt();
  }

  /**
   * Issues a main-thread collection request for a GIR operation. The session supplies its next
   * one-based ordinal; policy and completion evidence belong to the VM bridge. No graph object is
   * passed to the bridge.
   */
  public void collect(int operation) {
    control.collect(operation, 0, 0, 0, 0, ++collections);
  }

  /**
   * Captures named-root evidence, persists its bytes, and waits for engine acknowledgement before
   * resuming mutations. Workers must be joined; live bindings need the selected profile's evidence.
   * Operation zero denotes the final capture; other operations retain a per-site visit number.
   */
  public void checkpoint(int operation) throws IOException {
    if (callDepth != 0) throw new IllegalStateException("Capture inside a helper");
    long occurrence = visits.merge(operation, 1L, Long::sum);
    checkpoint++;

    try {
      if (bindingCapacity != 0)
        new BindingEvidence(
                run, checkpoint, Thread.currentThread().getId(), 0, 1, bindings.snapshot())
            .write(directory.resolve("snapshots/" + checkpoint + ".gbind"));

      if (nativeRootPlan != null) captureNativeRoots(operation, occurrence);
      else if (retained)
        capture(new Thread[] {Thread.currentThread()}, new int[] {0}, operation == 0);
      else capture(null, null, false);
      send(ControlMessage.Kind.CHECKPOINT, operation, occurrence, "");
      acknowledge(checkpoint);
    } finally {
      bindings.clear();
    }
  }

  private void captureNativeRoots(int operation, long occurrence) throws IOException {
    var evidence =
        NativeObserver.captureNativeRoots(
            run,
            checkpoint,
            records,
            operation,
            occurrence,
            retained ? new Thread[] {Thread.currentThread()} : null,
            retained ? new int[] {0} : null,
            operation == 0,
            false);
    Files.write(directory.resolve("snapshots/" + checkpoint + ".gsnap"), evidence.snapshot());
    Files.write(directory.resolve("snapshots/" + checkpoint + ".gnative"), evidence.resources());
    if (evidence.frames() != null) {
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gframe"), evidence.frames());
      FrameEvidence.read(evidence.frames(), run, checkpoint);
    }
    NativeRootEvidence.read(evidence.resources(), run, checkpoint);
  }

  private void cleanupNativeRoots() throws IOException {
    if (!nativeRootsConfigured || nativeCleanupAttempted) return;
    nativeCleanupAttempted = true;
    var evidence =
        NativeObserver.captureNativeRoots(
            run, checkpoint + 1, records, 0, 1, null, null, true, true);
    Files.write(directory.resolve("traces/native-cleanup.gsnap"), evidence.snapshot());
    Files.write(directory.resolve("traces/native-cleanup.gnative"), evidence.resources());
    var roots = NativeRootEvidence.read(evidence.resources(), run, checkpoint + 1);
    var graph = NativeObserver.decode(evidence.snapshot(), run, checkpoint + 1);
    if (!roots.cleanup()
        || !roots.complete()
        || graph.status() != HeapSnapshot.Status.COMPLETE
        || graph.jvmtiError() != 0
        || graph.records().stream()
            .anyMatch(record -> record.kind() == HeapSnapshot.Kind.ROOT && record.b() == 21))
      throw new IOException("Native root cleanup certification failed");
  }

  private void capture(Thread[] threads, int[] workers, boolean terminal) throws IOException {
    byte[] snapshot;
    if (retained) {
      var evidence =
          NativeObserver.captureRetained(run, checkpoint, records, threads, workers, terminal);
      // Persist raw bytes even when later frame decoding fails, preserving independent refutations.
      snapshot = evidence.snapshot();
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gsnap"), snapshot);
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gframe"), evidence.frames());
      FrameEvidence.read(evidence.frames(), run, checkpoint);
      if (queuePlan != null) {
        if (evidence.resources() == null)
          throw new IOException("Missing retained queue resource result");
        writeQueues(evidence.resources(), evidence.consistency());
      }
    } else if (queuePlan != null) {
      var evidence = NativeObserver.captureQueues(run, checkpoint, records);
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gsnap"), evidence.snapshot());
      writeQueues(evidence.resources(), evidence.consistency());
    } else {
      snapshot = NativeObserver.capture(run, checkpoint, records);
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gsnap"), snapshot);
    }
  }

  private void writeQueues(byte[] bytes, byte[] consistency) throws IOException {
    Files.write(directory.resolve("snapshots/" + checkpoint + ".gqueue"), bytes);
    if (queuePlan.automatic()) {
      if (consistency == null) throw new IOException("Missing automatic queue consistency result");
      Files.write(directory.resolve("snapshots/" + checkpoint + ".gqlock"), consistency);
      if (!QueueConsistency.read(consistency, run, checkpoint).complete())
        throw new IOException("Automatic queue custody or cleanup failed");
    } else if (consistency != null) throw new IOException("Unselected queue consistency result");
    QueueCapture.read(bytes, run, checkpoint);
  }

  /** Records only nullness before capture, without acquiring or retaining a workload reference. */
  public void binding(int declaration, boolean present) {
    bindings.record(declaration, present);
  }

  /** Persists complete traces and VM witnesses, then reports success after the final checkpoint. */
  public void complete() throws IOException {
    if (callDepth != 0) throw new IllegalStateException("Completion inside a helper");
    cleanupNativeRoots();
    writeTrace(true);
    control.finish();
    finishCode(true);

    send(ControlMessage.Kind.COMPLETE, 0, 0, "");
    complete = true;
  }

  /**
   * Persists incomplete traces and available VM witnesses, then reports a bounded failure message.
   */
  public void fail(Throwable failure) throws IOException {
    try {
      cleanupNativeRoots();
    } catch (Throwable cleanup) {
      failure.addSuppressed(cleanup);
    }
    writeTrace(false);
    control.finish();
    finishCode(false);

    send(ControlMessage.Kind.FAILED, 0, 0, failureDetail(failure));
  }

  /** Preserves failed native resource status before classifying an unavailable provider. */
  static void checkCodeStatus(CodeEvidence code) {
    if (code.status() == CodeEvidence.Status.VALID) return;
    String detail =
        "Retained startup certificate: " + code.status() + " (error " + code.jvmtiError() + ")";
    if (code.jvmtiError() == CodeEvidence.OUT_OF_MEMORY) throw new OutOfMemoryError(detail);
    throw new IllegalStateException(detail);
  }

  /**
   * Only expected provider setup rejections are unsupported; later and resource failures are not.
   */
  static String startupFailureDetail(boolean providerSetup, Throwable failure) {
    boolean unsupported =
        providerSetup
            && (failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException);
    return (unsupported ? ControlMessage.RETAINED_UNSUPPORTED : "") + failureDetail(failure);
  }

  static String startupFailureDetail(boolean providerSetup, boolean queueSetup, Throwable failure) {
    if (queueSetup
        && (failure instanceof IllegalArgumentException
            || failure instanceof IllegalStateException))
      return ControlMessage.QUEUES_UNSUPPORTED + failureDetail(failure);
    return startupFailureDetail(providerSetup, failure);
  }

  private static String failureDetail(Throwable failure) {
    // Bound failure text to the modified-UTF length used by control frames.
    String detail = failure.getClass().getName() + ": " + failure.getMessage();
    return detail.substring(0, Math.min(detail.length(), 4000));
  }

  private void finishCode(boolean finished) throws IOException {
    if (!retained || !codeBound || codeFinalizationAttempted) return;
    // A failed JNI return may already have finalized native state. Never retry or replace it.
    codeFinalizationAttempted = true;
    byte[] bytes = NativeObserver.codeStatus(run, finished);
    Files.write(directory.resolve("traces/code-final.gcode"), bytes);
    CodeEvidence.read(bytes, run, CodeEvidence.Stage.FINAL);
  }

  private void writeTrace(boolean finished) throws IOException {
    var events = new ArrayList<ExecutionTrace.Event>();
    recorders.values().forEach(recorder -> recorder.appendTo(events));
    new ExecutionTrace(run, finished, events).write(directory.resolve("traces/execution.gtrace"));

    if (referenceRecording) {
      var references = new ArrayList<ReferenceHistory.Event>();
      recorders.values().forEach(recorder -> recorder.appendReferences(references));
      var history = new ReferenceHistory(run, finished, references);
      if (typedReferences) history.writeTyped(directory.resolve("traces/references.gtyped"));
      else history.write(directory.resolve("traces/references.ghistory"));
    }

    if (queueRecorder != null)
      queueRecorder.history(run, finished).write(directory.resolve("traces/queues.qhistory"));

    new ExecutionEvidence(run, finished, groups, windows)
        .write(directory.resolve("traces/workers.gevidence"));
  }

  private void send(ControlMessage.Kind kind, int operation, long occurrence, String detail)
      throws IOException {
    new ControlMessage(kind, run, checkpoint, operation, occurrence, detail).write(out);
  }

  private void acknowledge(long sequence) throws IOException {
    var message = ControlMessage.read(in, run);
    if (message.kind() != ControlMessage.Kind.ACK || message.sequence() != sequence)
      throw new IOException("Unexpected engine acknowledgement");
  }

  /**
   * Releases the endpoint and bridge, retaining an incomplete trace if completion was not reported.
   */
  @Override
  public void close() throws IOException {
    var bridge = control;
    try (socket;
        bridge) {
      cleanupNativeRoots();
      if (!complete) {
        try {
          writeTrace(false);
        } catch (Throwable failure) {
          try {
            finishCode(false);
          } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
        finishCode(false);
      }
    }
  }
}
