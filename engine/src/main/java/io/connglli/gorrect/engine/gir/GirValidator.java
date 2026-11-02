package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks static GIR contracts without executing instructions. */
public final class GirValidator {
  /** Bounds recursive parser, validator, and formatter stack use. */
  public static final int MAX_NESTING = 128;

  public static final Set<String> STRESS_REQUIREMENTS =
      Set.of("concurrent-overlap", "compiled-execution", "gc-during-workers");

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final Map<String, Shape> shapes = new HashMap<>();
  private final Set<String> roots = new HashSet<>();
  private final Map<String, GirProgram.Method> methods = new HashMap<>();
  private final Map<String, Shape> queues = new HashMap<>();
  private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
  private long nextOperation = 1;
  private int nextWorker = 1;
  private int workerDepth = -1;
  private int conditionalDepth;
  private boolean collectReferenceTypes;
  private final Map<Integer, Shape> referenceOwners = new HashMap<>();
  private final Map<Integer, Shape> referenceValues = new HashMap<>();
  private final Map<Integer, Shape> cloneShapes = new HashMap<>();

  record ReferenceTypes(Map<Integer, Shape> owners, Map<Integer, Shape> values) {}

  /** Reuses lexical validation to resolve exact source types for the conservative support proof. */
  static ReferenceTypes referenceTypes(GirProgram program) {
    var validator = new GirValidator();
    validator.collectReferenceTypes = true;
    validator.validateProgram(program);
    return new ReferenceTypes(
        Map.copyOf(validator.referenceOwners), Map.copyOf(validator.referenceValues));
  }

  private record Binding(Shape shape, boolean live, boolean pinned) {
    Binding(Shape shape, boolean live) {
      this(shape, live, false);
    }
  }

  private GirValidator() {}

  /**
   * Checks every block, including zero-iteration bodies. Target-shape assertions and null
   * dereferences are checked when instructions execute.
   *
   * @throws GirException if any static GIR contract is invalid
   */
  public static void validate(GirProgram program) {
    new GirValidator().validateProgram(program);
  }

  /**
   * Validates every lexical block and returns each clone site's inferred array shape. Storage
   * planning uses this immutable map without treating a source declaration as an executed clone.
   */
  public static Map<Integer, Shape> arrayCloneShapes(GirProgram program) {
    var validator = new GirValidator();
    validator.validateProgram(program);
    return Map.copyOf(validator.cloneShapes);
  }

  static boolean isName(String name) {
    return name != null && !name.equals("null") && NAME.matcher(name).matches();
  }

  private void validateProgram(GirProgram program) {
    if (program.version() != 1) fail("Unsupported GIR version " + program.version());
    if (!STRESS_REQUIREMENTS.containsAll(program.requirements()))
      fail("Unsupported GIR stress requirements");
    for (var shape : program.shapes()) {
      name(shape.name());
      if (shapes.putIfAbsent(shape.name(), shape) != null) fail("Duplicate shape " + shape.name());
    }
    if (program.queues().size() > 64) fail("Named queue limit exceeded");
    for (var queue : program.queues()) {
      name(queue.name());
      var shape = shape(queue.shape());
      if (!shape.wrapper()) fail("Named queue requires a wrapper shape");
      if (queues.putIfAbsent(queue.name(), shape) != null) fail("Duplicate queue " + queue.name());
    }
    var ids = new HashSet<ObjectId>();
    for (var object : program.objects()) {
      if (object.id() <= 0 || !ids.add(ObjectId.initial(object.id()))) {
        fail("Invalid or duplicate initial object " + object.id());
      }
      var shape = shape(object.shape());
      if (shape.wrapper()) fail("Wrapper types require allocate-ref");
      if (object.references().size() != shape.references()
          || object.data().size() != shape.data()) {
        fail("Wrong slot count for initial object " + object.id());
      }
    }
    for (var object : program.objects()) {
      object.references().forEach(value -> initialReference(value, ids));
    }
    if (NativeRootProgram.roots(program).size() > 64) fail("Native root slot limit exceeded");
    for (var root : program.roots()) {
      name(root.name());
      if (!roots.add(root.name())) fail("Duplicate root " + root.name());
      if (root.storage() == GirProgram.RootStorage.JNI_GLOBAL && root.value().target() != null)
        fail("Native roots must be initially null");
      initialReference(root.value(), ids);
      if (root.value().kind() != Value.ReferenceKind.STRONG)
        fail("Named roots require strong references");
    }
    for (var method : program.methods()) {
      name(method.name());
      if (methods.putIfAbsent(method.name(), method) != null)
        fail("Duplicate method " + method.name());
      for (var parameter : method.parameters()) {
        name(parameter.name());
        if (parameter.shape() != null) shape(parameter.shape());
      }
    }
    for (var method : program.methods()) block(method.body(), 0, method.parameters());
    block(program.body(), 0);
    long declaration = nextOperation;
    for (var method : program.methods())
      for (var parameter : method.parameters())
        if (declaration > Integer.MAX_VALUE || parameter.declaration() != declaration++)
          fail("Parameter declaration IDs must follow source instructions");
    MethodProgram.validateBounds(program, cloneShapes);
    var queueGaps = ReferenceProgram.automaticQueueGaps(program);
    if (!queueGaps.isEmpty()) fail(String.join("; ", queueGaps));
    var labels = new HashSet<String>();
    for (var entry : program.labels().entrySet()) {
      if (entry.getKey() <= 0 || entry.getKey() >= nextOperation) {
        fail("Label does not identify an instruction: " + entry.getValue());
      }
      if (!isName(entry.getValue())) fail("Invalid label " + entry.getValue());
      if (!labels.add(entry.getValue())) fail("Duplicate label " + entry.getValue());
    }
  }

  private static void initialReference(Value.Reference value, Set<ObjectId> ids) {
    if (value.wrapper() != null || value.wrapperTarget() != null)
      fail("Wrapper identities are assigned by the implementation");
    if (value.target() != null && !ids.contains(value.target())) {
      fail("Unknown initial reference target " + value.target());
    }
  }

  private void block(List<Instruction> instructions, int depth) {
    block(instructions, depth, null, null, List.of());
  }

  private void block(
      List<Instruction> instructions, int depth, List<GirProgram.Parameter> parameters) {
    block(instructions, depth, null, null, parameters);
  }

  private void block(
      List<Instruction> instructions, int depth, String acquired, Shape acquiredShape) {
    block(instructions, depth, acquired, acquiredShape, List.of());
  }

  private void block(
      List<Instruction> instructions,
      int depth,
      String acquired,
      Shape acquiredShape,
      List<GirProgram.Parameter> parameters) {
    if (depth > MAX_NESTING) fail("GIR nesting limit exceeded");
    scopes.push(new HashMap<>());
    try {
      for (var parameter : parameters)
        declare(parameter.name(), parameter.shape() == null ? null : shape(parameter.shape()));
      if (acquired != null) {
        declare(acquired, acquiredShape);
        scopes.peek().put(acquired, new Binding(acquiredShape, true, true));
      }
      for (var instruction : instructions) {
        // Reparsing assigns these indices; accepting gaps would change allocation identities.
        if (instruction.id() != nextOperation++) {
          fail("Internal instruction IDs must follow source order");
        }
        switch (instruction) {
          case Instruction.Call call -> {
            var method = methods.get(call.method());
            if (method == null) fail("Unknown method " + call.method());
            if (method.parameters().size() != call.arguments().size()) fail("Wrong call arity");
            for (int index = 0; index < method.parameters().size(); index++) {
              var parameter = method.parameters().get(index);
              var argument = call.arguments().get(index);
              operand(argument, parameter.shape() != null);
              if (parameter.shape() != null
                  && argument instanceof Operand.Name named
                  && !shape(parameter.shape()).equals(binding(named.name()).shape()))
                fail("Call argument shape mismatch");
            }
          }
          case Instruction.IfPresent present -> {
            if (binding(present.name()).shape() == null)
              fail("if-present requires a reference handle");
            conditionalDepth++;
            block(present.body(), depth + 1);
            conditionalDepth--;
          }
          case Instruction.IfNonzero branch -> {
            operand(branch.condition(), false);
            conditionalDepth++;
            block(branch.thenBody(), depth + 1);
            block(branch.elseBody(), depth + 1);
            conditionalDepth--;
          }
          case Instruction.Acquire acquire -> {
            if (acquire.receiver()) {
              if (acquire.index() != -1) fail("Invalid receiver encoding");
              wrapper(acquire.owner());
            } else slot(acquire.owner(), acquire.index(), true);
            var shape = shape(acquire.shape());
            conditionalDepth++;
            block(acquire.body(), depth + 1, acquire.name(), shape);
            conditionalDepth--;
          }
          case Instruction.Parallel parallel -> parallel(parallel, depth + 1);
          case Instruction.Monitor monitor -> {
            var owner = binding(monitor.owner()).shape();
            if (owner == null || owner.wrapper())
              fail("Monitor requires an object or array handle");
            block(monitor.body(), depth + 1);
          }
          case Instruction.Scope scope -> block(scope.body(), depth + 1);
          case Instruction.Repeat repeat -> {
            if (repeat.count() < 0) fail("Negative repeat count at " + repeat.id());
            block(repeat.body(), depth + 1);
          }
          case Instruction.Operation operation -> {
            if (operation instanceof Instruction.Barrier
                && (workerDepth < 0 || depth != workerDepth))
              fail("Barriers must be direct worker statements");
            if (operation instanceof Instruction.Checkpoint
                && workerDepth >= 0
                && depth != workerDepth) fail("Checkpoints must be direct worker statements");
            operation(operation);
          }
        }
      }
    } finally {
      scopes.pop();
    }
  }

  private void parallel(Instruction.Parallel parallel, int depth) {
    if (workerDepth >= 0) fail("Parallel blocks cannot be nested in workers");
    if (parallel.workers().isEmpty() || parallel.workers().size() > 16)
      fail("Parallel blocks require 1..16 workers");
    var names = new HashSet<String>();
    var callerScopes = new ArrayDeque<>(scopes);
    List<Boolean> synchronization = null;
    boolean conditional = conditionalDepth > 0;
    try {
      for (var worker : parallel.workers()) {
        name(worker.name());
        if (!names.add(worker.name())
            || worker.id() != nextWorker++
            || worker.id() > ExecutionTrace.MAX_WORKERS)
          fail("Duplicate or invalid worker identity");
        // Workers never inherit a caller's local bindings, including released-name reservations.
        scopes.clear();
        workerDepth = depth;
        block(worker.body(), depth);
        var kinds =
            worker.body().stream()
                .filter(
                    op -> op instanceof Instruction.Barrier || op instanceof Instruction.Checkpoint)
                .map(op -> op instanceof Instruction.Checkpoint)
                .toList();
        if (conditional && kinds.contains(true))
          fail("Worker capture groups cannot be conditional");
        if (synchronization != null && !synchronization.equals(kinds))
          fail("Workers must have matching barrier and checkpoint sequences");
        synchronization = kinds;
      }
    } finally {
      workerDepth = -1;
      scopes.clear();
      scopes.addAll(callerScopes);
    }
  }

  private void operation(Instruction.Operation operation) {
    switch (operation) {
      case Instruction.Allocate op -> {
        if (shape(op.shape()).wrapper()) fail("Wrapper types require allocate-ref");
        declare(op.name(), shape(op.shape()));
      }
      case Instruction.Clone op -> {
        var source = binding(op.owner()).shape();
        if (source == null || !source.array()) fail("clone requires an array handle");
        declare(op.name(), source);
        cloneShapes.put(op.id(), source);
      }
      case Instruction.CopyArray op -> {
        var source = binding(op.source()).shape();
        var destination = binding(op.destination()).shape();
        if (source == null
            || destination == null
            || !source.array()
            || !destination.array()
            || source.kind() != destination.kind()) fail("copy-array requires compatible arrays");
        copyRange(op.sourceIndex(), op.length(), (int) source.slots());
        copyRange(op.destinationIndex(), op.length(), (int) destination.slots());
      }
      case Instruction.AllocateReference op -> {
        var shape = shape(op.shape());
        if (!shape.wrapper()) fail("allocate-ref requires a wrapper type");
        operand(op.value(), true);
        if (op.queue() != null) {
          if (!shape.equals(queue(op.queue()))) fail("Queue wrapper shape mismatch");
        }
        declare(op.name(), shape);
      }
      case Instruction.ReadRoot op -> {
        if (op.mode() == null) fail("Missing memory mode");
        root(op.root());
        declare(op.name(), shape(op.shape()));
      }

      case Instruction.WriteRoot op -> {
        if (op.mode() == null) fail("Missing memory mode");
        root(op.root());
        operand(op.value(), true);
      }

      case Instruction.ReadReference op -> {
        if (collectReferenceTypes) referenceOwners.put(op.id(), binding(op.owner()).shape());
        if (op.mode() == null) fail("Missing memory mode");
        slot(op.owner(), op.index(), true);
        declare(op.name(), shape(op.shape()));
      }

      case Instruction.WriteReference op -> {
        if (collectReferenceTypes) {
          referenceOwners.put(op.id(), binding(op.owner()).shape());
          if (op.value() instanceof Operand.Name name && binding(name.name()).shape() != null)
            referenceValues.put(op.id(), binding(name.name()).shape());
        }
        if (op.mode() == null) fail("Missing memory mode");
        slot(op.owner(), op.index(), true);
        operand(op.value(), true);
        if (op.kind() == null) fail("Missing reference kind");
        if (op.kind() != Value.ReferenceKind.STRONG && op.mode() != MemoryMode.PLAIN)
          fail("Wrapped stores use ordinary accesses");
      }

      case Instruction.ClearReference op -> {
        if (op.receiver()) {
          if (op.index() != -1) fail("Invalid receiver encoding");
          wrapper(op.owner());
        } else slot(op.owner(), op.index(), true);
      }
      case Instruction.ReferenceState op -> {
        if (op.receiver()) {
          if (op.index() != -1) fail("Invalid receiver encoding");
          wrapper(op.owner());
        } else slot(op.owner(), op.index(), true);
        declare(op.name(), null);
      }

      case Instruction.EnqueueReference op -> {
        wrapper(op.owner());
        declare(op.name(), null);
      }
      case Instruction.PollQueue op -> {
        if (op.named()) {
          declare(op.name(), queue(op.queue()));
        } else if (op.name() != null) fail("Legacy poll has no result binding");
      }
      case Instruction.IdentityHash op -> operand(op.owner(), true);
      case Instruction.SameObject op -> {
        operand(op.left(), true);
        operand(op.right(), true);
        declare(op.name(), null);
      }
      case Instruction.ReadData op -> {
        if (op.mode() == null) fail("Missing memory mode");
        slot(op.owner(), op.index(), false);
        declare(op.name(), null);
      }

      case Instruction.WriteData op -> {
        if (op.mode() == null) fail("Missing memory mode");
        slot(op.owner(), op.index(), false);
        operand(op.value(), false);
      }

      case Instruction.CompareSet op -> {
        location(op.location());
        operand(op.expected(), op.location().reference());
        operand(op.update(), op.location().reference());
        declare(op.name(), null);
      }

      case Instruction.Exchange op -> {
        location(op.location());
        operand(op.update(), op.location().reference());
        if (!op.location().reference() && op.shape() != null)
          fail("Data exchange has no reference shape");
        declare(op.name(), op.location().reference() ? shape(op.shape()) : null);
      }

      case Instruction.Release op -> {
        var binding = scopes.peek().get(op.name());
        if (binding == null || !binding.live())
          fail("Release requires a live local binding " + op.name());
        if (binding.pinned()) fail("Acquired references remain strong until scope exit");
        scopes.peek().put(op.name(), new Binding(binding.shape(), false));
      }

      case Instruction.Checkpoint ignored -> {}
      case Instruction.Collect ignored -> {}
      case Instruction.Barrier ignored -> {}
    }
  }

  private void location(Location location) {
    if (location == null || location.kind() == null) fail("Missing atomic location");
    if (location.kind() == Location.Kind.ROOT) {
      root(location.owner());
      if (location.index() != 0) fail("Root location has no index");
    } else slot(location.owner(), location.index(), location.reference());
  }

  private static void copyRange(int index, int length, int size) {
    if (index < 0 || length < 0 || index > size || length > size - index)
      fail("Invalid copy-array range");
  }

  private Shape queue(String name) {
    var shape = queues.get(name);
    if (shape == null) fail("Unknown named queue " + name);
    return shape;
  }

  private void wrapper(String owner) {
    var shape = binding(owner).shape();
    if (shape == null || !shape.wrapper()) fail("Expected wrapper handle " + owner);
  }

  private void slot(String owner, int index, boolean reference) {
    var binding = binding(owner);
    if (binding.shape() == null) fail("Expected reference handle " + owner);
    int size = reference ? binding.shape().references() : binding.shape().data();
    if (index < 0 || index >= size) fail("Invalid slot " + owner + "[" + index + "]");
  }

  private void operand(Operand operand, boolean reference) {
    boolean actual =
        switch (operand) {
          case Operand.Name value -> binding(value.name()).shape() != null;
          case Operand.Literal ignored -> false;
          case Operand.Null ignored -> true;
          case null -> throw new GirException("Missing operand");
        };
    if (reference != actual) fail("Operand has wrong value type");
  }

  private void declare(String name, Shape shape) {
    name(name);
    if (scopes.stream().anyMatch(scope -> scope.containsKey(name))) {
      fail("Binding already declared " + name);
    }
    scopes.peek().put(name, new Binding(shape, true));
  }

  private Binding binding(String name) {
    for (var scope : scopes) {
      var binding = scope.get(name);
      if (binding != null) {
        if (!binding.live()) fail("Binding has been released " + name);
        return binding;
      }
    }
    throw new GirException("Unknown binding " + name);
  }

  private Shape shape(String name) {
    var shape = shapes.get(name);
    if (shape == null) fail("Unknown shape " + name);
    return shape;
  }

  private void root(String name) {
    if (!roots.contains(name)) fail("Unknown root " + name);
  }

  private static void name(String name) {
    if (!isName(name)) fail("Invalid name " + name);
  }

  private static void fail(String message) {
    throw new GirException(message);
  }
}
