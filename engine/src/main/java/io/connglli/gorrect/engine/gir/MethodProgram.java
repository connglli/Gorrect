package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Whole-source admission and bounded expansion for direct, acyclic void calls. */
public final class MethodProgram {
  public static final int MAX_METHODS = 64;
  private static final long SATURATED = Long.MAX_VALUE;
  // The selected native provider owns this fixed table (native/src/agent.cpp::max_arrays).
  private static final int MAX_ARRAY_IDENTITIES = 4096;
  private static final Quota STEP = new Quota(1, 0, 0);

  private record Quota(long steps, long arrays, long arrayBytes) {
    Quota plus(Quota other) {
      return new Quota(
          add(steps, other.steps), add(arrays, other.arrays), add(arrayBytes, other.arrayBytes));
    }

    Quota times(long count) {
      return new Quota(
          multiply(steps, count), multiply(arrays, count), multiply(arrayBytes, count));
    }
  }

  private MethodProgram() {}

  public static boolean selected(GirProgram program) {
    return !program.methods().isEmpty()
        || program.instructions().anyMatch(Instruction.Call.class::isInstance);
  }

  public static GirProgram.Method method(GirProgram program, String name) {
    return program.methods().stream()
        .filter(method -> method.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new GirException("Unknown method " + name));
  }

  /** This traversal visits syntax only; expanding calls here would double-count definitions. */
  public static Stream<Instruction> instructions(List<Instruction> body) {
    return new GirProgram(1, List.of(), List.of(), List.of(), body).instructions();
  }

  /** Stable generated name, separate from source identifiers and workload binding names. */
  public static String javaName(GirProgram program, GirProgram.Method method) {
    return "helper" + (program.methods().indexOf(method) + 1);
  }

  /** Retained certificates identify helper activations by generated name and hidden arguments. */
  public static boolean helper(String name, String descriptor) {
    return name.matches("helper[1-9][0-9]*")
        && descriptor.startsWith("(Lio/connglli/gorrect/runtime/TargetSession;I")
        && descriptor.endsWith(")V");
  }

  /** Future instructions require an explicit support decision even in unused definitions. */
  public static List<String> unsupported(GirProgram program) {
    if (!selected(program)) return List.of();
    boolean invalid =
        NativeRootProgram.selected(program)
            || CloneProgram.usesObjectClones(program)
            || !program.queues().isEmpty()
            || !program.requirements().isEmpty()
            || program.shapes().stream()
                .anyMatch(
                    shape ->
                        switch (shape.kind()) {
                          case OBJECT, REFERENCE_ARRAY, DATA_ARRAY -> false;
                          default -> true;
                        })
            || program.objects().stream()
                .flatMap(object -> object.references().stream())
                .anyMatch(reference -> reference.kind() != Value.ReferenceKind.STRONG)
            || program.roots().stream()
                .anyMatch(root -> root.value().kind() != Value.ReferenceKind.STRONG)
            || program.instructions().anyMatch(instruction -> !supported(instruction))
            || program.methods().stream()
                .flatMap(method -> instructions(method.body()))
                .anyMatch(Instruction.Checkpoint.class::isInstance);
    return invalid
        ? List.of("Methods require strong sequential plain execution without helper checkpoints")
        : List.of();
  }

  private static boolean supported(Instruction instruction) {
    return switch (instruction) {
      case Instruction.Call ignored -> true;
      case Instruction.Allocate ignored -> true;
      case Instruction.Clone ignored -> true;
      case Instruction.CopyArray ignored -> true;
      case Instruction.IdentityHash ignored -> true;
      case Instruction.SameObject ignored -> true;
      case Instruction.Release ignored -> true;
      case Instruction.Checkpoint ignored -> true;
      case Instruction.Collect ignored -> true;
      case Instruction.Scope ignored -> true;
      case Instruction.Repeat ignored -> true;
      case Instruction.IfPresent ignored -> true;
      case Instruction.IfNonzero ignored -> true;
      case Instruction.ReadRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.ReadReference op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteReference op ->
          op.mode() == MemoryMode.PLAIN && op.kind() == Value.ReferenceKind.STRONG;
      case Instruction.ReadData op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteData op -> op.mode() == MemoryMode.PLAIN;
      default -> false;
    };
  }

  /** Checks call cycles, combined nesting, and conservative JVM/provider storage ceilings. */
  static void validateBounds(GirProgram program, Map<Integer, Shape> cloneShapes) {
    if (!selected(program)) return;
    if (program.methods().size() > MAX_METHODS)
      throw new GirException("Method definition limit exceeded");
    var shapes = new HashMap<String, Shape>();
    program.shapes().forEach(shape -> shapes.put(shape.name(), shape));
    var depths = new HashMap<String, Long>();
    long locals = 0;
    for (var method : program.methods()) {
      long slots = 2;
      for (var parameter : method.parameters()) slots += parameter.shape() == null ? 2 : 1;
      if (slots > 255) throw new GirException("JVM parameter slot limit exceeded");
      depth(program, method, depths, new HashSet<>());
      locals = add(locals, sourceBound(method.body(), method.parameters().size()));
    }
    long mainDepth = bodyDepth(program, program.body(), depths, new HashSet<>());
    if (mainDepth > GirValidator.MAX_NESTING)
      throw new GirException("Combined lexical/call nesting limit exceeded");
    locals = add(locals, sourceBound(program.body(), 0));
    if (locals > 60_000) throw new GirException("Retained local metadata limit exceeded");
    generatedResources(program, shapes, cloneShapes);
  }

  private static void generatedResources(
      GirProgram program, Map<String, Shape> shapes, Map<Integer, Shape> cloneShapes) {
    long initialize =
        add(multiply(program.objects().size(), 128), multiply(program.roots().size(), 32));
    long registrations = multiply(program.shapes().size(), 64);
    long metadata = add(1024, multiply(program.roots().size(), 64));
    long pool =
        add(1024, add(multiply(program.roots().size(), 8), multiply(program.shapes().size(), 16)));
    pool = add(pool, multiply(program.objects().size(), 16));
    for (var method : program.methods())
      pool = add(pool, add(16, multiply(method.parameters().size(), 8)));
    var literals = new HashSet<String>();
    for (var shape : program.shapes()) {
      if (!shape.array() && shape.slots() > 60_000)
        throw new GirException("Conservative JVM shape field limit exceeded");
      if (shape.kind() == Shape.Kind.OBJECT)
        metadata = add(metadata, add(128, multiply(add(shape.slots(), 3), 64)));
    }
    var initial = new Quota(0, 0, 0);
    for (var object : program.objects()) {
      var shape = shapes.get(object.shape());
      initialize = add(initialize, multiply(shape.slots(), 32));
      pool = add(pool, multiply(shape.slots(), 8));
      arrayLiteral(shape, literals);
      initial = initial.plus(arrayQuota(shape));
    }
    // Unused bodies still emit array-shape literals, although their execution quota is zero.
    for (var instruction : program.instructions().toList()) {
      arrayLiteral(arrayShape(instruction, shapes, cloneShapes), literals);
      pool = add(pool, 8);
      if (instruction instanceof Instruction.Call call)
        pool = add(pool, multiply(call.arguments().size(), 4));
    }
    long literalBytes = 0;
    for (var literal : literals) literalBytes = add(literalBytes, add(literal.length(), 16));
    pool = add(pool, multiply(literals.size(), 4));
    if (initialize > 60_000 || registrations > 60_000)
      throw new GirException("Conservative JVM initialization code limit exceeded");
    // Reserve constant-pool entries for each emitted template and its debug names, with separate
    // argument and UTF storage allowances. This deliberately stays below the JVM/provider ceilings.
    if (pool > 60_000) throw new GirException("Conservative JVM constant pool capacity exceeded");
    if (literalBytes > CodeEvidence.MAX_CODE_BYTES / 4)
      throw new GirException("Conservative class constant bytes capacity exceeded");
    var executed =
        initial.plus(quota(program, program.body(), new HashMap<>(), shapes, cloneShapes));
    if (executed.arrays() > MAX_ARRAY_IDENTITIES)
      throw new GirException("Native array identity capacity exceeded");
    // Fixed generated names fit 64 bytes per field and 128 per class. Every registered class and
    // bound array is serialized, even after its objects die. Reserve the rest for records/VM
    // metadata.
    if (add(metadata, executed.arrayBytes()) > SnapshotCodec.MAX_BYTES / 4)
      throw new GirException("Conservative snapshot metadata capacity exceeded");
  }

  private static void arrayLiteral(Shape shape, Set<String> literals) {
    if (shape == null || !shape.array()) return;
    // ASCII names use one modified-UTF byte each. javac accepts 65534 bytes but rejects 65535.
    if (shape.name().length() > 65_534)
      throw new GirException("Array shape literal exceeds compiler UTF capacity");
    literals.add(shape.name());
  }

  private static Shape arrayShape(
      Instruction instruction, Map<String, Shape> shapes, Map<Integer, Shape> cloneShapes) {
    return switch (instruction) {
      case Instruction.Allocate allocate -> shapes.get(allocate.shape());
      case Instruction.Clone clone -> cloneShapes.get(clone.id());
      default -> null;
    };
  }

  private static Quota arrayQuota(Shape shape) {
    return shape != null && shape.array()
        ? new Quota(0, 1, add(64, shape.name().length()))
        : new Quota(0, 0, 0);
  }

  private static long sourceBound(List<Instruction> body, int parameters) {
    long count = instructions(body).count();
    long checkpoints = instructions(body).filter(Instruction.Checkpoint.class::isInstance).count();
    long declarations = add(count, parameters);
    // Every emitted operation has bounded scaffolding; captures also emit one primitive binding
    // record per possible local. Reserve cleanup/handler duplication and compiler temporaries.
    long code = add(multiply(count, 512), multiply(multiply(checkpoints, declarations), 32));
    code = add(code, multiply(parameters, 64));
    long arguments = 0;
    for (var instruction : instructions(body).toList())
      if (instruction instanceof Instruction.Call call)
        arguments = add(arguments, call.arguments().size());
    // Arguments can require wide local loads; source statement count alone does not bound a call.
    code = add(code, multiply(arguments, 8));
    if (code > 60_000) throw new GirException("Conservative JVM method code limit exceeded");
    if (add(2, multiply(declarations, 8)) > 65_535)
      throw new GirException("JVM local slot limit exceeded");
    if (declarations > BindingEvidence.MAX_BINDINGS)
      throw new GirException("Binding provider limit exceeded");
    return multiply(add(declarations, 2), 8);
  }

  private static long depth(
      GirProgram program, GirProgram.Method method, Map<String, Long> depths, Set<String> active) {
    var known = depths.get(method.name());
    if (known != null) return known;
    if (!active.add(method.name())) throw new GirException("Recursive method call graph");
    long value = add(1, bodyDepth(program, method.body(), depths, active));
    if (value > GirValidator.MAX_NESTING)
      throw new GirException("Combined lexical/call nesting limit exceeded");
    active.remove(method.name());
    depths.put(method.name(), value);
    return value;
  }

  private static long bodyDepth(
      GirProgram program, List<Instruction> body, Map<String, Long> depths, Set<String> active) {
    long result = 0;
    for (var instruction : body) {
      long value =
          switch (instruction) {
            case Instruction.Call call ->
                depth(program, method(program, call.method()), depths, active);
            case Instruction.Scope scope ->
                add(1, bodyDepth(program, scope.body(), depths, active));
            case Instruction.Repeat repeat ->
                add(1, bodyDepth(program, repeat.body(), depths, active));
            case Instruction.IfPresent present ->
                add(1, bodyDepth(program, present.body(), depths, active));
            case Instruction.IfNonzero branch ->
                add(
                    1,
                    Math.max(
                        bodyDepth(program, branch.thenBody(), depths, active),
                        bodyDepth(program, branch.elseBody(), depths, active)));
            case Instruction.Acquire acquire ->
                add(1, bodyDepth(program, acquire.body(), depths, active));
            case Instruction.Monitor monitor ->
                add(1, bodyDepth(program, monitor.body(), depths, active));
            case Instruction.Parallel parallel ->
                parallel.workers().stream()
                    .mapToLong(worker -> add(1, bodyDepth(program, worker.body(), depths, active)))
                    .max()
                    .orElse(0);
            default -> 0;
          };
      result = Math.max(result, value);
    }
    return result;
  }

  /**
   * Saturated quota proof precedes expansion and recorder allocation, including both branch arms.
   */
  public static long steps(GirProgram program, OracleLimits limits) {
    var cloneShapes = GirValidator.cloneShapes(program);
    var shapes = new HashMap<String, Shape>();
    program.shapes().forEach(shape -> shapes.put(shape.name(), shape));
    long result = quota(program, program.body(), new HashMap<>(), shapes, cloneShapes).steps();
    if (result > limits.steps() || result > ExecutionTrace.MAX_EVENTS)
      throw new OracleLimitException("Method-expanded control/storage step bound exhausted");
    return result;
  }

  private static Quota quota(
      GirProgram program,
      List<Instruction> body,
      Map<String, Quota> memo,
      Map<String, Shape> shapes,
      Map<Integer, Shape> cloneShapes) {
    var result = new Quota(0, 0, 0);
    for (var instruction : body) {
      var value =
          switch (instruction) {
            case Instruction.Call call -> {
              var cost = memo.get(call.method());
              if (cost == null) {
                cost =
                    quota(
                        program, method(program, call.method()).body(), memo, shapes, cloneShapes);
                memo.put(call.method(), cost);
              }
              yield cost.plus(new Quota(2, 0, 0));
            }
            case Instruction.Scope scope ->
                quota(program, scope.body(), memo, shapes, cloneShapes).plus(STEP);
            case Instruction.Repeat repeat ->
                quota(program, repeat.body(), memo, shapes, cloneShapes)
                    .plus(STEP)
                    .times(repeat.count())
                    .plus(STEP);
            case Instruction.IfPresent present ->
                quota(program, present.body(), memo, shapes, cloneShapes).plus(STEP);
            case Instruction.IfNonzero branch ->
                quota(program, branch.thenBody(), memo, shapes, cloneShapes)
                    .plus(quota(program, branch.elseBody(), memo, shapes, cloneShapes))
                    .plus(STEP);
            default -> STEP.plus(arrayQuota(arrayShape(instruction, shapes, cloneShapes)));
          };
      result = result.plus(value);
    }
    return result;
  }

  private static long add(long left, long right) {
    return left > SATURATED - right ? SATURATED : left + right;
  }

  private static long multiply(long left, long right) {
    return right != 0 && left > SATURATED / right ? SATURATED : left * right;
  }
}
