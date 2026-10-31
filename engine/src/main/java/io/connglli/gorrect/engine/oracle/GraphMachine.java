package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Location;
import io.connglli.gorrect.engine.gir.MemoryMode;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Case-local state. Heap writes replace immutable records so retained checkpoints stay unchanged.
 */
final class GraphMachine {
  private final OracleLimits limits;
  private final Map<String, Shape> shapes;
  private final Map<ObjectId, HeapObject> objects = new HashMap<>();
  private final Map<WrapperId, HeapReference> referenceObjects = new HashMap<>();
  private final Map<String, Value.Reference> roots = new HashMap<>();
  private Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
  private int worker, group, phase;
  private long round;
  private final Map<Integer, Access> accesses = new HashMap<>();
  private final Map<Integer, Long> occurrences = new HashMap<>();
  private final List<OracleResult.Checkpoint> checkpoints = new ArrayList<>();
  private final List<ExecutionTrace.Event> events = new ArrayList<>();
  private long steps;
  private long slots;
  private long snapshotCells;
  private long wrappers;

  /**
   * A {@code null} value reserves a released binding's name. Live null handles use {@link
   * Value.Reference#NULL}.
   */
  private record Binding(int operation, Value value) {}

  private record Target(ObjectId id, HeapObject object) {}

  /** Live worker bindings; independent search forks must use {@link #copyContext(Context)}. */
  record Context(
      int worker, int group, long round, int phase, Deque<Map<String, Binding>> scopes) {}

  record Slot(ObjectId object, String root, boolean reference, int index) {}

  /**
   * One workload variable access. Separate read/write flags distinguish failed CAS from successful
   * read-modify-write operations.
   */
  record Memory(Slot slot, boolean write, boolean atomic, boolean read) {
    Memory(Slot slot, boolean write, boolean atomic) {
      this(slot, write, atomic, !write);
    }
  }

  /** Forks semantic state for schedule search; only its last marker is retained. */
  GraphMachine(GraphMachine source) {
    limits = source.limits;
    // Declarations never change; payload, root, and binding maps belong to each fork.
    shapes = source.shapes;
    objects.putAll(source.objects);
    referenceObjects.putAll(source.referenceObjects);
    roots.putAll(source.roots);
    occurrences.putAll(source.occurrences);
    scopes = copyContext(source.context()).scopes();
    worker = source.worker;
    group = source.group;
    phase = source.phase;
    round = source.round;
    steps = source.steps;
    slots = source.slots;
    snapshotCells = source.snapshotCells;
    wrappers = source.wrappers;
    if (!source.events.isEmpty()) events.add(source.lastEvent());
  }

  /** Copies binding maps for an independent search fork; immutable bound values may be shared. */
  static Context copyContext(Context source) {
    var scopes = new ArrayDeque<Map<String, Binding>>();
    for (var scope : source.scopes()) scopes.addLast(new HashMap<>(scope));
    return new Context(source.worker(), source.group(), source.round(), source.phase(), scopes);
  }

  long forkCells() {
    return 1L
        + objects.size()
        + referenceObjects.size()
        + roots.size()
        + occurrences.size()
        + contextCells(context());
  }

  private record ModelKey(
      Graph graph,
      List<Map<String, Binding>> scopes,
      Map<Integer, Long> occurrences,
      long steps,
      long slots,
      long snapshotCells,
      long wrappers) {}

  /** Quiescent search keys preserve budgets and bindings as well as the complete logical heap. */
  Object key() {
    return key(graph(), scopes.stream().map(Map::copyOf).toList());
  }

  /**
   * Shared state without the scheduler's incidental active context; contexts are keyed separately.
   */
  Object key(Iterable<Context> contexts) {
    return key(graph(contexts), List.of());
  }

  private ModelKey key(Graph graph, List<Map<String, Binding>> scopes) {
    return new ModelKey(
        graph, scopes, Map.copyOf(occurrences), steps, slots, snapshotCells, wrappers);
  }

  private record ContextKey(
      int worker, int group, long round, int phase, List<Map<String, Binding>> scopes) {}

  /**
   * Freezes values and released-name reservations without activating the context. Phase is the
   * scheduler's current phase, not the context's creation-time phase.
   */
  static Object contextKey(Context context, int phase) {
    return new ContextKey(
        context.worker(),
        context.group(),
        context.round(),
        phase,
        context.scopes().stream().map(Map::copyOf).toList());
  }

  /** Shared key cells exclude contexts, which the caller charges for graph and scope snapshots. */
  long sharedKeyCells() {
    return 1L
        + objects.size()
        + referenceObjects.size()
        + roots.size()
        + occurrences.size()
        + slots;
  }

  long keyCells() {
    return forkCells() + slots;
  }

  static long contextCells(Context context) {
    long cells = 1;
    for (var scope : context.scopes()) cells += 1L + scope.size();
    return cells;
  }

  private static final class Access {
    final Set<Slot> reads = new HashSet<>(), writes = new HashSet<>();
  }

  /**
   * Returns the active live context, retaining its mutable scopes rather than taking a snapshot.
   */
  Context context() {
    return new Context(worker, group, round, phase, scopes);
  }

  /** Creates isolated worker bindings for a group visit, without inheriting caller locals. */
  Context workerContext(int id, int operation, long visit) {
    var locals = new ArrayDeque<Map<String, Binding>>();
    locals.push(new HashMap<>());
    return new Context(id, operation, visit, 0, locals);
  }

  /**
   * Attaches the supplied live bindings and phase; later binding changes belong to that context.
   */
  void activate(Context state, int nextPhase) {
    worker = state.worker();
    group = state.group();
    round = state.round();
    phase = nextPhase;
    scopes = state.scopes();
  }

  /** Advances this site's occurrence counter and returns its one-based visit number. */
  long visit(int operation) {
    long value = occurrences.getOrDefault(operation, 0L) + 1;
    occurrences.put(operation, value);
    return value;
  }

  void marker(int operation, ExecutionTrace.Kind kind, long value) {
    requireStep();
    steps++;
    events.add(event(operation, kind, value));
  }

  private ExecutionTrace.Event event(int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value, worker, group, round, phase);
  }

  void beginPhase() {
    accesses.clear();
  }

  void checkPhase() {
    // Conflict freedom makes the chosen serial interpretation valid for every phase interleaving.
    for (var left : accesses.entrySet())
      for (var right : accesses.entrySet()) {
        if (left.getKey().equals(right.getKey())) continue;
        for (var slot : left.getValue().writes) {
          if (right.getValue().writes.contains(slot) || right.getValue().reads.contains(slot))
            throw new GirException(
                "Conflicting phase access by workers "
                    + left.getKey()
                    + " and "
                    + right.getKey()
                    + ": "
                    + slot);
        }
      }
  }

  private void access(Slot slot, boolean write) {
    if (worker == 0) return;
    var values = accesses.computeIfAbsent(worker, ignored -> new Access());
    (write ? values.writes : values.reads).add(slot);
  }

  GraphMachine(GirProgram program, OracleLimits limits) {
    this.limits = limits;
    shapes = new HashMap<>();
    program.shapes().forEach(shape -> shapes.put(shape.name(), shape));
    if (program.objects().size() > limits.objects()) limit("initial objects");
    for (var initial : program.objects()) {
      var shape = shape(initial.shape());
      if (shape.slots() > limits.slots() - slots) limit("initial slots");
      var references = new ArrayList<Value.Reference>();
      for (int index = 0; index < initial.references().size(); index++) {
        var value = initial.references().get(index);
        if (value.kind() != Value.ReferenceKind.STRONG) {
          if ((long) program.objects().size() + wrappers >= limits.objects())
            limit("initial reference wrappers");
          value =
              new Value.Reference(
                  value.kind(), value.target(), new WrapperId(0, 0, initial.id(), index));
          wrappers++;
        }
        references.add(value);
      }
      objects.put(
          ObjectId.initial(initial.id()), new HeapObject(shape, references, initial.data()));
      slots += shape.slots();
    }
    program.roots().forEach(root -> roots.put(root.name(), root.value()));
    enterScope();
  }

  void enterScope() {
    scopes.push(new HashMap<>());
  }

  void leaveScope() {
    scopes.pop();
  }

  void step(int operation, boolean iteration) {
    requireStep();
    steps++;
    events.add(
        event(
            operation,
            iteration ? ExecutionTrace.Kind.ITERATION : ExecutionTrace.Kind.OPERATION,
            0));
  }

  List<ExecutionTrace.Event> events() {
    return List.copyOf(events);
  }

  void referenceReadMarker(String name) {
    var previous = lastEvent();
    events.set(
        events.size() - 1,
        event(previous.operation(), ExecutionTrace.Kind.REFERENCE_READ, present(name) ? 1 : 0));
  }

  ExecutionTrace.Event lastEvent() {
    return events.getLast();
  }

  long steps() {
    return steps;
  }

  List<OracleResult.Checkpoint> checkpoints() {
    return List.copyOf(checkpoints);
  }

  /**
   * Validation and budget rejections leave the heap, bindings, identities, and counters unchanged.
   */
  void apply(Instruction.Operation operation) {
    if (operation.id() <= 0) throw new GirException("Invalid operation ID");
    requireStep();

    long read = 0;

    switch (operation) {
      case Instruction.Allocate op -> allocate(op);
      case Instruction.AllocateReference op -> allocateReference(op);
      case Instruction.ReadRoot op -> {
        requireDeclaration(op.name());
        var value = root(op.root());
        requireShape(value, shape(op.shape()));

        bind(op.name(), op.id(), value);
        access(new Slot(null, op.root(), true, 0), false);
      }

      case Instruction.WriteRoot op -> {
        root(op.root());
        var value = reference(op.value());

        roots.put(op.root(), value);
        access(new Slot(null, op.root(), true, 0), true);
      }

      case Instruction.ReadReference op -> {
        requireDeclaration(op.name());
        var target = target(op.owner(), op.index(), true);
        var value = target.object().references().get(op.index());
        if (value.kind() != Value.ReferenceKind.STRONG) {
          if (shape(op.shape()).wrapper())
            throw new UnsupportedHistoryException(
                "Registered legacy wrapper exposure is unsupported");
          throw new GirException("Soft/weak slots require acquire");
        }
        requireShape(value, shape(op.shape()));

        bind(op.name(), op.id(), value);
        access(new Slot(target.id(), null, true, op.index()), false);
      }

      case Instruction.WriteReference op -> {
        var target = target(op.owner(), op.index(), true);
        var value = reference(op.value());
        if (op.kind() != Value.ReferenceKind.STRONG && value.wrapperTarget() != null)
          throw new UnsupportedHistoryException("Legacy wrappers require ordinary referents");

        long occurrence = 0;
        if (op.kind() != Value.ReferenceKind.STRONG) {
          if (objects.size() + wrappers >= limits.objects()) limit("reference wrappers");
          occurrence = occurrences.getOrDefault(op.id(), 0L) + 1;
          value =
              new Value.Reference(
                  op.kind(), value.target(), new WrapperId(worker, op.id(), occurrence, -1));
        }

        var references = new ArrayList<>(target.object().references());
        references.set(op.index(), value);
        objects.put(
            target.id(),
            new HeapObject(target.object().shape(), references, target.object().data()));
        access(new Slot(target.id(), null, true, op.index()), true);
        if (op.kind() != Value.ReferenceKind.STRONG) {
          wrappers++;
          occurrences.put(op.id(), occurrence);
        }
      }

      case Instruction.ClearReference op -> {
        if (op.receiver()) {
          referenceValue(op.owner(), op.index());
          break;
        }
        var target = target(op.owner(), op.index(), true);
        var value = target.object().references().get(op.index());
        if (value.kind() == Value.ReferenceKind.STRONG)
          throw new GirException("clear-ref requires a soft/weak slot");

        var references = new ArrayList<>(target.object().references());
        references.set(op.index(), new Value.Reference(value.kind(), null, value.wrapper()));
        objects.put(
            target.id(),
            new HeapObject(target.object().shape(), references, target.object().data()));
      }

      case Instruction.ReferenceState ignored ->
          throw new GirException("Reference state requires a recorded history");
      case Instruction.PollQueue ignored ->
          throw new GirException("Queue polling requires a recorded history");
      case Instruction.SameObject op -> {
        requireDeclaration(op.name());
        read = operand(op.left(), true).equals(operand(op.right(), true)) ? 1L : 0L;
        bind(op.name(), op.id(), new Value.Scalar(read));
      }
      case Instruction.ReadData op -> {
        requireDeclaration(op.name());
        var target = target(op.owner(), op.index(), false);
        read = target.object().data().get(op.index());
        bind(op.name(), op.id(), new Value.Scalar(read));
        access(new Slot(target.id(), null, false, op.index()), false);
      }

      case Instruction.WriteData op -> {
        var target = target(op.owner(), op.index(), false);
        var value = scalar(op.value());

        var data = new ArrayList<>(target.object().data());
        data.set(op.index(), value.value());
        objects.put(
            target.id(),
            new HeapObject(target.object().shape(), target.object().references(), data));
        access(new Slot(target.id(), null, false, op.index()), true);
      }

      case Instruction.CompareSet op -> {
        requireDeclaration(op.name());
        var current = atomicValue(op.location());
        var expected = operand(op.expected(), op.location().reference());
        var update = operand(op.update(), op.location().reference());
        rejectWrapperAtomic(expected);
        rejectWrapperAtomic(update);

        read = current.equals(expected) ? 1 : 0;

        if (read == 1) writeLocation(op.location(), update);
        bind(op.name(), op.id(), new Value.Scalar(read));
      }

      case Instruction.Exchange op -> {
        requireDeclaration(op.name());
        var current = atomicValue(op.location());
        var update = operand(op.update(), op.location().reference());
        rejectWrapperAtomic(update);
        if (current instanceof Value.Reference reference) {
          requireShape(reference, shape(op.shape()));
          read = reference.target() == null ? 0 : 1;
        }
        if (current instanceof Value.Scalar scalar) read = scalar.value();

        writeLocation(op.location(), update);
        bind(op.name(), op.id(), current);
      }

      case Instruction.Release op -> {
        var binding = scopes.peek().get(op.name());
        if (binding == null || binding.value() == null) {
          throw new GirException("Release requires a live local binding " + op.name());
        }
        scopes.peek().put(op.name(), new Binding(binding.operation(), null));
      }

      case Instruction.Checkpoint op -> {
        var graph = snapshot();

        long occurrence = occurrences.getOrDefault(op.id(), 0L) + 1;
        var checkpoint = new OracleResult.Checkpoint(op.id(), occurrence, graph);

        occurrences.put(op.id(), occurrence);
        checkpoints.add(checkpoint);
      }

      case Instruction.Collect ignored -> {}
      case Instruction.Barrier ignored -> {}
    }

    steps++;
    events.add(event(operation.id(), eventKind(operation), read));
  }

  private static ExecutionTrace.Kind eventKind(Instruction.Operation operation) {
    return switch (operation) {
      case Instruction.Barrier ignored -> ExecutionTrace.Kind.BARRIER;
      case Instruction.CompareSet ignored -> ExecutionTrace.Kind.COMPARE_SET;
      case Instruction.ReadData ignored -> ExecutionTrace.Kind.SCALAR_READ;
      case Instruction.SameObject ignored -> ExecutionTrace.Kind.SCALAR_READ;
      case Instruction.Exchange exchange ->
          exchange.location().reference()
              ? ExecutionTrace.Kind.REFERENCE_READ
              : ExecutionTrace.Kind.SCALAR_READ;
      default -> ExecutionTrace.Kind.OPERATION;
    };
  }

  boolean present(String name) {
    return ((Value.Reference) binding(name)).heapTarget() != null;
  }

  Value bound(String name) {
    return binding(name);
  }

  Memory referenceAccess(String owner, int index) {
    var target = target(owner, index, true);
    return new Memory(new Slot(target.id(), null, true, index), false, false);
  }

  /**
   * Resolves the concrete memory slot and read/write/atomic effects without mutating it, including
   * whether CAS succeeds. Returns null for non-memory operations. Required owner/operand checks may
   * reject the operation before the scheduler applies it.
   */
  Memory memory(Instruction.Operation operation) {
    return switch (operation) {
      case Instruction.ReadRoot op ->
          new Memory(new Slot(null, op.root(), true, 0), false, op.mode() == MemoryMode.VOLATILE);
      case Instruction.WriteRoot op ->
          new Memory(new Slot(null, op.root(), true, 0), true, op.mode() == MemoryMode.VOLATILE);
      case Instruction.ReadReference op ->
          memory(new Location(Location.Kind.REFERENCE, op.owner(), op.index()), false, op.mode());
      case Instruction.WriteReference op ->
          memory(new Location(Location.Kind.REFERENCE, op.owner(), op.index()), true, op.mode());
      case Instruction.ReadData op ->
          memory(new Location(Location.Kind.DATA, op.owner(), op.index()), false, op.mode());
      case Instruction.WriteData op ->
          memory(new Location(Location.Kind.DATA, op.owner(), op.index()), true, op.mode());
      case Instruction.CompareSet op -> {
        var access =
            memory(
                op.location(),
                atomicValue(op.location())
                    .equals(operand(op.expected(), op.location().reference())),
                MemoryMode.VOLATILE);
        yield new Memory(access.slot(), access.write(), true, true);
      }
      case Instruction.Exchange op -> {
        var access = memory(op.location(), true, MemoryMode.VOLATILE);
        yield new Memory(access.slot(), true, true, true);
      }
      case Instruction.ClearReference op ->
          op.receiver() ? null : referenceAccess(op.owner(), op.index());
      case Instruction.ReferenceState op ->
          op.receiver() ? null : referenceAccess(op.owner(), op.index());
      default -> null;
    };
  }

  private Memory memory(Location location, boolean write, MemoryMode mode) {
    if (location.kind() == Location.Kind.ROOT) {
      root(location.owner());
      return new Memory(
          new Slot(null, location.owner(), true, 0), write, mode == MemoryMode.VOLATILE);
    }
    var target = target(location.owner(), location.index(), location.reference());
    return new Memory(
        new Slot(target.id(), null, location.reference(), location.index()),
        write,
        mode == MemoryMode.VOLATILE);
  }

  private Value operand(Operand operand, boolean reference) {
    return reference ? reference(operand) : scalar(operand);
  }

  private static void rejectWrapperAtomic(Value value) {
    if (value instanceof Value.Reference reference && reference.wrapperTarget() != null)
      throw new UnsupportedHistoryException("Wrapper-valued atomics are unsupported");
  }

  private Value atomicValue(Location location) {
    Value value;
    if (location.kind() == Location.Kind.ROOT) value = root(location.owner());
    else {
      var target = target(location.owner(), location.index(), location.reference());
      value =
          location.reference()
              ? target.object().references().get(location.index())
              : new Value.Scalar(target.object().data().get(location.index()));
    }
    if (value instanceof Value.Reference reference) {
      if (reference.kind() != Value.ReferenceKind.STRONG)
        throw new GirException("Atomic operations require a strong slot");
      if (reference.wrapperTarget() != null)
        throw new UnsupportedHistoryException("Wrapper-valued atomics are unsupported");
    }
    return value;
  }

  private void writeLocation(Location location, Value value) {
    if (location.kind() == Location.Kind.ROOT) {
      roots.put(location.owner(), (Value.Reference) value);
      return;
    }
    var target = target(location.owner(), location.index(), location.reference());
    var object = target.object();
    if (location.reference()) {
      var refs = new ArrayList<>(object.references());
      refs.set(location.index(), (Value.Reference) value);
      objects.put(target.id(), new HeapObject(object.shape(), refs, object.data()));
    } else {
      var data = new ArrayList<>(object.data());
      data.set(location.index(), ((Value.Scalar) value).value());
      objects.put(target.id(), new HeapObject(object.shape(), object.references(), data));
    }
  }

  private void allocate(Instruction.Allocate op) {
    requireDeclaration(op.name());
    var shape = shape(op.shape());
    if (shape.wrapper()) throw new GirException("Wrapper types require allocate-ref");
    if (objects.size() + wrappers >= limits.objects()) limit("objects");
    if (shape.slots() > limits.slots() - slots) limit("slots");

    long occurrence = occurrences.getOrDefault(op.id(), 0L) + 1;
    var id = new ObjectId(worker, op.id(), occurrence);
    var object =
        new HeapObject(
            shape,
            Collections.nCopies(shape.references(), Value.Reference.NULL),
            Collections.nCopies(shape.data(), 0L));
    var binding = new Binding(op.id(), Value.Reference.strong(id));

    objects.put(id, object);
    scopes.peek().put(op.name(), binding);
    occurrences.put(op.id(), occurrence);
    slots += shape.slots();
  }

  private void allocateReference(Instruction.AllocateReference op) {
    requireDeclaration(op.name());
    var shape = shape(op.shape());
    if (!shape.wrapper()) throw new GirException("allocate-ref requires a wrapper type");
    var target = reference(op.value()).heapTarget();
    if (objects.size() + wrappers >= limits.objects()) limit("reference wrappers");
    if (slots >= limits.slots()) limit("slots");
    long occurrence = occurrences.getOrDefault(op.id(), 0L) + 1;
    var id = new WrapperId(worker, op.id(), occurrence, -1);
    var object = new HeapReference(shape, target);
    var value = Value.Reference.toWrapper(id);

    referenceObjects.put(id, object);
    bind(op.name(), op.id(), value);
    occurrences.put(op.id(), occurrence);
    wrappers++;
    slots++;
  }

  /** Construction edge for a canonical no-queue wrapper; clearing belongs to the frontier. */
  Value.Reference referenceValue(String owner, int index) {
    if (index != -1) return referenceSlot(owner, index);
    var pointer = reference(new Operand.Name(owner));
    if (pointer.heapTarget() == null) throw new GirException("Null wrapper receiver " + owner);
    var id = pointer.wrapperTarget();
    var wrapper = referenceObjects.get(id);
    if (wrapper == null) throw new GirException("Expected first-class wrapper " + owner);
    var target = wrapper.referent();
    return new Value.Reference(
        wrapper.shape().strength(),
        target == null ? null : target.object(),
        id,
        target == null ? null : target.wrapper());
  }

  HeapReference referenceObject(WrapperId id) {
    return referenceObjects.get(id);
  }

  private void requireDeclaration(String name) {
    if (scopes.stream().anyMatch(scope -> scope.containsKey(name))) {
      throw new GirException("Binding already declared " + name);
    }
  }

  private void bind(String name, int operation, Value value) {
    scopes.peek().put(name, new Binding(operation, value));
  }

  private Value binding(String name) {
    for (var scope : scopes) {
      var binding = scope.get(name);
      if (binding != null) {
        if (binding.value() == null) throw new GirException("Binding has been released " + name);
        return binding.value();
      }
    }
    throw new GirException("Unknown binding " + name);
  }

  private Value.Reference reference(Operand operand) {
    return switch (operand) {
      case Operand.Null ignored -> Value.Reference.NULL;
      case Operand.Name name when binding(name.name()) instanceof Value.Reference value -> value;
      default -> throw new GirException("Expected reference operand");
    };
  }

  /** Reads the condition without changing model state or charging its branch step. */
  boolean nonzero(Operand operand) {
    return scalar(operand).value() != 0;
  }

  private Value.Scalar scalar(Operand operand) {
    return switch (operand) {
      case Operand.Literal literal -> new Value.Scalar(literal.value());
      case Operand.Name name when binding(name.name()) instanceof Value.Scalar value -> value;
      default -> throw new GirException("Expected scalar operand");
    };
  }

  private Value.Reference root(String name) {
    var value = roots.get(name);
    if (value == null) throw new GirException("Unknown root " + name);
    return value;
  }

  Value.Reference referenceSlot(String owner, int index) {
    var target = target(owner, index, true);
    return target.object().references().get(index);
  }

  /**
   * Opens a scope with a strong acquired handle after the frontier has validated a non-null target.
   */
  void bindAcquired(Instruction.Acquire operation, ObjectId target) {
    bindAcquiredHeap(operation, HeapId.object(target));
  }

  void bindAcquiredHeap(Instruction.Acquire operation, HeapId target) {
    requireDeclaration(operation.name());
    requireShape(Value.Reference.strongHeap(target), shape(operation.shape()));
    requireStep();
    enterScope();
    bind(operation.name(), operation.id(), Value.Reference.strongHeap(target));
  }

  /** Binds and records a clearing-state choice already validated by the reference frontier. */
  void referenceState(Instruction.ReferenceState operation, boolean cleared) {
    requireDeclaration(operation.name());
    if (operation.receiver()) referenceValue(operation.owner(), -1);
    else referenceSlot(operation.owner(), operation.index());
    requireStep();
    bind(operation.name(), operation.id(), new Value.Scalar(cleared ? 1 : 0));
    marker(operation.id(), ExecutionTrace.Kind.REFERENCE_STATE, cleared ? 1 : 0);
  }

  void chargeSnapshot(Graph graph) {
    long cells =
        1L
            + graph.objects().size()
            + graph.roots().size()
            + graph.handles().size()
            + 2L * graph.wrappers().size();
    for (var object : graph.objects().values()) cells += object.shape().slots();
    if (cells > limits.snapshotCells() - snapshotCells) limit("snapshot cells");
    snapshotCells += cells;
  }

  private Shape shape(String name) {
    var shape = shapes.get(name);
    if (shape == null) throw new GirException("Unknown shape " + name);
    return shape;
  }

  private void requireShape(Value.Reference reference, Shape shape) {
    if (reference.heapTarget() == null) return;
    Shape actual =
        reference.wrapperTarget() == null
            ? objects.get(reference.target()).shape()
            : referenceObjects.containsKey(reference.wrapperTarget())
                ? referenceObjects.get(reference.wrapperTarget()).shape()
                : null;
    if (actual == null || !actual.equals(shape))
      throw new GirException("Reference target does not have shape " + shape.name());
  }

  private Target target(String owner, int index, boolean reference) {
    var value = reference(new Operand.Name(owner));
    if (value.heapTarget() == null) throw new GirException("Null dereference " + owner);
    if (value.wrapperTarget() != null)
      throw new GirException("Wrappers have no ordinary indexed slots");
    var object = objects.get(value.target());
    int size = reference ? object.references().size() : object.data().size();
    if (index < 0 || index >= size)
      throw new GirException("Invalid slot " + owner + "[" + index + "]");
    return new Target(value.target(), object);
  }

  /** Copies the allocated heap and reference roots before reachability projection. */
  Graph graph() {
    var handles = new HashMap<Integer, Value.Reference>();
    for (var scope : scopes) {
      for (var binding : scope.values()) {
        if (binding.value() instanceof Value.Reference reference)
          handles.put(binding.operation(), reference);
      }
    }
    return new Graph(objects, roots, handles, referenceObjects);
  }

  /** All suspended callers and workers contribute strong handle obligations during GC search. */
  Graph graph(Iterable<Context> contexts) {
    var handles = new HashMap<Integer, Value.Reference>();
    for (var context : contexts)
      for (var scope : context.scopes())
        for (var binding : scope.values())
          if (binding.value() instanceof Value.Reference reference)
            handles.put(binding.operation(), reference);
    return new Graph(objects, roots, handles, referenceObjects);
  }

  /** Captures required obligations and charges their cumulative snapshot budget. */
  Graph snapshot() {
    var graph = graph().required();
    chargeSnapshot(graph);
    return graph;
  }

  private void requireStep() {
    if (steps >= limits.steps()) limit("steps");
  }

  private static void limit(String budget) {
    throw new OracleLimitException("Oracle budget exhausted: " + budget);
  }
}
