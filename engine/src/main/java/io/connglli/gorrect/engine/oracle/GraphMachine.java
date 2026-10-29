package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
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
  private final Map<String, Shape> shapes = new HashMap<>();
  private final Map<ObjectId, HeapObject> objects = new HashMap<>();
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

  /**
   * A {@code null} value reserves a released binding's name. Live null handles use {@link
   * Value.Reference#NULL}.
   */
  private record Binding(int operation, Value value) {}

  private record Target(ObjectId id, HeapObject object) {}

  record Context(
      int worker, int group, long round, int phase, Deque<Map<String, Binding>> scopes) {}

  private record Slot(ObjectId object, String root, boolean reference, int index) {}

  private static final class Access {
    final Set<Slot> reads = new HashSet<>(), writes = new HashSet<>();
  }

  Context context() {
    return new Context(worker, group, round, phase, scopes);
  }

  Context workerContext(int id, int operation, long visit) {
    var locals = new ArrayDeque<Map<String, Binding>>();
    locals.push(new HashMap<>());
    return new Context(id, operation, visit, 0, locals);
  }

  void activate(Context state, int nextPhase) {
    worker = state.worker();
    group = state.group();
    round = state.round();
    phase = nextPhase;
    scopes = state.scopes();
  }

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
    program.shapes().forEach(shape -> shapes.put(shape.name(), shape));
    if (program.objects().size() > limits.objects()) limit("initial objects");
    for (var initial : program.objects()) {
      var shape = shape(initial.shape());
      if (shape.slots() > limits.slots() - slots) limit("initial slots");
      objects.put(
          ObjectId.initial(initial.id()),
          new HeapObject(shape, initial.references(), initial.data()));
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
        requireShape(value, shape(op.shape()));
        bind(op.name(), op.id(), value);
        access(new Slot(target.id(), null, true, op.index()), false);
      }
      case Instruction.WriteReference op -> {
        var target = target(op.owner(), op.index(), true);
        var value = reference(op.value());
        var references = new ArrayList<>(target.object().references());
        references.set(op.index(), value);
        objects.put(
            target.id(),
            new HeapObject(target.object().shape(), references, target.object().data()));
        access(new Slot(target.id(), null, true, op.index()), true);
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
    events.add(
        event(
            operation.id(),
            operation instanceof Instruction.Barrier
                ? ExecutionTrace.Kind.BARRIER
                : operation instanceof Instruction.ReadData
                    ? ExecutionTrace.Kind.SCALAR_READ
                    : ExecutionTrace.Kind.OPERATION,
            read));
  }

  private void allocate(Instruction.Allocate op) {
    requireDeclaration(op.name());
    var shape = shape(op.shape());
    if (objects.size() >= limits.objects()) limit("objects");
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

  private Shape shape(String name) {
    var shape = shapes.get(name);
    if (shape == null) throw new GirException("Unknown shape " + name);
    return shape;
  }

  private void requireShape(Value.Reference reference, Shape shape) {
    if (reference.target() != null && !objects.get(reference.target()).shape().equals(shape)) {
      throw new GirException("Reference target does not have shape " + shape.name());
    }
  }

  private Target target(String owner, int index, boolean reference) {
    var value = reference(new Operand.Name(owner));
    if (value.target() == null) throw new GirException("Null dereference " + owner);
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
    return new Graph(objects, roots, handles);
  }

  /** Captures required obligations and charges their cumulative snapshot budget. */
  Graph snapshot() {
    var graph = graph().required();
    long cells = 1L + graph.objects().size() + graph.roots().size() + graph.handles().size();
    for (var object : graph.objects().values()) cells += object.shape().slots();
    if (cells > limits.snapshotCells() - snapshotCells) limit("snapshot cells");
    snapshotCells += cells;
    return graph;
  }

  private void requireStep() {
    if (steps >= limits.steps()) limit("steps");
  }

  private static void limit(String budget) {
    throw new OracleLimitException("Oracle budget exhausted: " + budget);
  }
}
