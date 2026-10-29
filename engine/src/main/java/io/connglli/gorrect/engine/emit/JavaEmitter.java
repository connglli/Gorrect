package io.connglli.gorrect.engine.emit;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Emits direct operations from GIR. Layout metadata describes fields, never expected values. */
public final class JavaEmitter {
  public record Emission(String source, ObservationLayout layout) {}

  private record Binding(String name, Shape shape) {}

  private final GirProgram program;
  private final StringBuilder source = new StringBuilder();
  private final Map<String, Shape> shapes = new HashMap<>();
  private final Map<String, String> types = new HashMap<>();
  private final Map<String, String> roots = new LinkedHashMap<>();
  private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
  private final List<Instruction.Worker> workers = new ArrayList<>();
  private int worker;
  private final boolean references;

  private JavaEmitter(GirProgram program) {
    this.program = program;
    references = ReferenceProgram.usesReferences(program);
  }

  public static Emission emit(GirProgram program) {
    GirValidator.validate(program);
    if (ReferenceProgram.usesReferences(program)
        && (ReferenceProgram.hasParallel(program)
            || program.shapes().stream().anyMatch(shape -> shape.kind() != Shape.Kind.OBJECT)))
      throw new io.connglli.gorrect.engine.gir.GirException(
          "Reference emission requires a sequential ordinary-object program");
    return new JavaEmitter(program).emit();
  }

  private Emission emit() {
    line("import io.connglli.gorrect.runtime.TargetSession;");
    line("import io.connglli.gorrect.runtime.WorkerContext;");
    line("import io.connglli.gorrect.runtime.observe.NativeObserver;");
    line("import io.connglli.gorrect.protocol.HeapSnapshot;");
    line("import io.connglli.gorrect.protocol.ReferenceHistory;");
    line("public final class Case {");
    var layouts = new ArrayList<ObservationLayout.ObjectType>();
    var registrations = new ArrayList<String>();
    var referenceLayouts = new ArrayList<ObservationLayout.ReferenceType>();
    if (references) {
      referenceClasses();
      for (var kind : List.of(Value.ReferenceKind.SOFT, Value.ReferenceKind.WEAK)) {
        String type = kind == Value.ReferenceKind.SOFT ? "SoftSlot" : "WeakSlot";
        registrations.add(
            "new NativeObserver.Registration("
                + type
                + ".class, HeapSnapshot.ClassKind."
                + (kind == Value.ReferenceKind.SOFT ? "SOFT_REFERENCE" : "WEAK_REFERENCE")
                + ")");
        referenceLayouts.add(
            new ObservationLayout.ReferenceType(
                "LCase$" + type + ";",
                kind,
                "idWorker",
                "idSite",
                "idOccurrence",
                "idSlot",
                "referent"));
      }
    }
    int index = 0;
    for (var shape : program.shapes()) {
      shapes.put(shape.name(), shape);
      String type =
          switch (shape.kind()) {
            case OBJECT -> "S" + index;
            case REFERENCE_ARRAY -> "Object[]";
            case DATA_ARRAY -> "long[]";
          };
      types.put(shape.name(), type);
      index++;
      if (shape.kind() != Shape.Kind.OBJECT) continue;
      line("public static final class " + type + " {");
      line("public final int idWorker, idSite;");
      line("public final long idOccurrence;");
      for (String field : names("f", shape.references())) line("public Object " + field + ";");
      for (String field : names("d", shape.data())) line("public long " + field + ";");
      line(
          "public "
              + type
              + "(int worker, int site, long occurrence) { idWorker = worker; idSite = site;"
              + " idOccurrence = occurrence; }");
      line("}");
      layouts.add(
          new ObservationLayout.ObjectType(
              "LCase$" + type + ";",
              shape,
              names("f", shape.references()),
              names("d", shape.data()),
              "idWorker",
              "idSite",
              "idOccurrence"));
      registrations.add(
          "new NativeObserver.Registration(" + type + ".class, HeapSnapshot.ClassKind.OBJECT)");
    }
    registrations.add(
        "new NativeObserver.Registration(Object[].class, HeapSnapshot.ClassKind.REFERENCE_ARRAY)");
    registrations.add(
        "new NativeObserver.Registration(long[].class, HeapSnapshot.ClassKind.DATA_ARRAY)");
    registrations.add("new NativeObserver.Registration(Roots.class, HeapSnapshot.ClassKind.ROOTS)");
    line("public static final class Roots {");
    for (var root : program.roots()) {
      String field = "r" + roots.size();
      roots.put(root.name(), field);
      line("public static volatile Object " + field + ";");
    }
    line("}");
    allocationCounters(program.body());
    line("public static void main(String[] args) throws Exception {");
    line("try (TargetSession session = TargetSession.open(args)) {");
    line("try {");
    line("NativeObserver.configure(java.util.List.of(" + String.join(",", registrations) + "));");
    line("initialize(); execute(session); session.checkpoint(0); session.complete();");
    line(
        "} catch (Throwable failure) { failure.printStackTrace(); session.fail(failure); throw"
            + " failure; }");
    line("}");
    line("}");
    initialize();
    line("private static void execute(TargetSession session) throws Exception {");
    block(program.body());
    line("}");
    for (var declaration : workers) {
      worker = declaration.id();
      line(
          "private static void "
              + ExecutionEvidence.workerMethod(worker)
              + "(TargetSession session, WorkerContext worker) throws Exception {");
      line("worker.begin();");
      block(declaration.body());
      line("worker.end();");
      line("}");
    }
    worker = 0;
    line("}");
    var rootFields = new LinkedHashMap<String, String>();
    roots.forEach((name, field) -> rootFields.put(field, name));
    return new Emission(
        source.toString(),
        new ObservationLayout(
            layouts,
            program.shapes().stream().filter(shape -> shape.kind() != Shape.Kind.OBJECT).toList(),
            new ObservationLayout.Roots("LCase$Roots;", rootFields),
            referenceLayouts));
  }

  private void referenceClasses() {
    line(
        "private static final java.lang.ref.ReferenceQueue<Object> referenceQueue = new"
            + " java.lang.ref.ReferenceQueue<>();");
    for (String kind : List.of("Soft", "Weak")) {
      line(
          "public static final class "
              + kind
              + "Slot extends java.lang.ref."
              + kind
              + "Reference<Object> {");
      line("public final int idWorker, idSite, idSlot; public final long idOccurrence;");
      line(
          "public "
              + kind
              + "Slot(int worker, int site, long occurrence, int slot, Object target) {"
              + " super(target, referenceQueue); idWorker=worker; idSite=site;"
              + " idOccurrence=occurrence; idSlot=slot; }");
      line("}");
    }
    line(
        "private static void reference(TargetSession session, int operation, int kind, long value,"
            + " java.lang.ref.Reference<?> object, int tw, int ts, long occurrence) {");
    line(
        "if (object == null) session.reference(operation, kind, value, 0, 0, 0, 0L, 0, tw, ts,"
            + " occurrence);");
    line(
        "else if (object instanceof SoftSlot r) session.reference(operation, kind, value, 1,"
            + " r.idWorker, r.idSite, r.idOccurrence, r.idSlot, tw, ts, occurrence);");
    line(
        "else if (object instanceof WeakSlot r) session.reference(operation, kind, value, 2,"
            + " r.idWorker, r.idSite, r.idOccurrence, r.idSlot, tw, ts, occurrence);");
    line("else throw new IllegalStateException(\"Unregistered reference object\");");
    line("}");
  }

  private void allocationCounters(List<Instruction> instructions) {
    for (var instruction : instructions)
      switch (instruction) {
        case Instruction.Allocate op -> line("private static long a" + op.id() + ";");
        case Instruction.WriteReference op -> {
          if (op.kind() != Value.ReferenceKind.STRONG)
            line("private static long w" + op.id() + ";");
        }
        case Instruction.Acquire acquire -> allocationCounters(acquire.body());
        case Instruction.Scope scope -> allocationCounters(scope.body());
        case Instruction.Repeat repeat -> allocationCounters(repeat.body());
        case Instruction.Parallel parallel -> {
          workers.addAll(parallel.workers());
          parallel.workers().forEach(declaration -> allocationCounters(declaration.body()));
        }
        default -> {}
      }
  }

  private void initialize() {
    line("private static void initialize() {");
    var objects = new HashMap<Long, String>();
    for (var object : program.objects()) {
      var shape = shapes.get(object.shape());
      String name = "o" + objects.size();
      objects.put(object.id(), name);
      line(
          types.get(shape.name())
              + " "
              + name
              + " = "
              + allocation(shape, 0, object.id() + "L")
              + ";");
    }
    if (references)
      line(
          "Object[] initialization = new Object[]{"
              + String.join(
                  ",", program.objects().stream().map(object -> objects.get(object.id())).toList())
              + "};");
    for (var object : program.objects()) {
      var shape = shapes.get(object.shape());
      String name = objects.get(object.id());
      for (int i = 0; i < object.references().size(); i++) {
        var value = object.references().get(i);
        String target = initial(value, objects);
        if (value.kind() != Value.ReferenceKind.STRONG)
          target =
              "new "
                  + (value.kind() == Value.ReferenceKind.SOFT ? "SoftSlot" : "WeakSlot")
                  + "(0, 0, "
                  + object.id()
                  + "L, "
                  + i
                  + ", "
                  + target
                  + ")";
        line(slot(name, shape, i, true) + " = " + target + ";");
      }
      for (int i = 0; i < object.data().size(); i++)
        line(slot(name, shape, i, false) + " = " + object.data().get(i) + "L;");
    }
    for (var root : program.roots())
      line("Roots." + roots.get(root.name()) + " = " + initial(root.value(), objects) + ";");
    // No registry survives construction; the graph's named roots establish its lifetime.
    for (var object : program.objects())
      bindArray(objects.get(object.id()), shapes.get(object.shape()), 0, object.id() + "L");
    if (references) line("java.lang.ref.Reference.reachabilityFence(initialization);");
    line("}");
  }

  private void block(List<Instruction> instructions) {
    block(instructions, null, null);
  }

  private void block(List<Instruction> instructions, String acquired, Binding acquiredBinding) {
    scopes.push(new LinkedHashMap<>());
    if (acquired != null) scopes.peek().put(acquired, acquiredBinding);
    for (var instruction : instructions) {
      if (instruction instanceof Instruction.ReadData op) {
        var owner = binding(op.owner());
        String name = declare(op.name(), null, op.id());
        line("long " + name + " = " + slot(owner.name(), owner.shape(), op.index(), false) + ";");
        line(recorder() + ".record(" + op.id() + ", 2, " + name + ");");
        continue;
      }
      switch (instruction) {
        case Instruction.Acquire op -> {
          var owner = binding(op.owner());
          String ref = "reference" + op.id(), result = "b" + op.id();
          line("{");
          line(
              "java.lang.ref.Reference<?> "
                  + ref
                  + " = (java.lang.ref.Reference<?>) "
                  + slot(owner.name(), owner.shape(), op.index(), true)
                  + ";");
          line(
              types.get(op.shape())
                  + " "
                  + result
                  + " = ("
                  + types.get(op.shape())
                  + ") "
                  + ref
                  + ".get();");
          reference(op.id(), "ACQUIRE", result + " == null ? 0L : 1L", ref, result);
          line("if (" + result + " != null) {");
          block(op.body(), op.name(), new Binding(result, shapes.get(op.shape())));
          line(
              "session.record("
                  + op.id()
                  + ", io.connglli.gorrect.protocol.ExecutionTrace.Kind.ACQUIRE_END.ordinal(),"
                  + " 0L);");
          line("}");
          line(result + " = null; " + ref + " = null;");
          line("}");
          continue;
        }
        case Instruction.ClearReference op -> {
          var owner = binding(op.owner());
          String ref = "reference" + op.id();
          line(
              "java.lang.ref.Reference<?> "
                  + ref
                  + " = (java.lang.ref.Reference<?>) "
                  + slot(owner.name(), owner.shape(), op.index(), true)
                  + ";");
          line(ref + ".clear();");
          reference(op.id(), "CLEAR", "0L", ref, null);
          line(ref + " = null;");
          continue;
        }
        case Instruction.ReferenceState op -> {
          var owner = binding(op.owner());
          String ref = "reference" + op.id();
          line(
              "java.lang.ref.Reference<?> "
                  + ref
                  + " = (java.lang.ref.Reference<?>) "
                  + slot(owner.name(), owner.shape(), op.index(), true)
                  + ";");
          String name = declare(op.name(), null, op.id());
          line("long " + name + " = " + ref + ".refersTo(null) ? 1L : 0L;");
          reference(op.id(), "STATE", name, ref, null);
          line(ref + " = null;");
          continue;
        }
        case Instruction.PollQueue op -> {
          String ref = "reference" + op.id();
          line("java.lang.ref.Reference<?> " + ref + " = referenceQueue.poll();");
          reference(op.id(), "QUEUE", ref + " == null ? 0L : 1L", ref, null);
          line(ref + " = null;");
          continue;
        }
        case Instruction.Parallel parallel -> {
          record(parallel.id(), false);
          String ids =
              parallel.workers().stream()
                  .map(declaration -> Integer.toString(declaration.id()))
                  .collect(Collectors.joining(","));
          String tasks =
              parallel.workers().stream()
                  .map(
                      declaration ->
                          "context -> "
                              + ExecutionEvidence.workerMethod(declaration.id())
                              + "(session, context)")
                  .collect(Collectors.joining(","));
          line(
              "session.parallel("
                  + parallel.id()
                  + ", new int[]{"
                  + ids
                  + "}, new TargetSession.WorkerTask[]{"
                  + tasks
                  + "}, Case.class);");
          continue;
        }
        case Instruction.Barrier op -> {
          line("worker.barrier(" + op.id() + ");");
          continue;
        }
        case Instruction.Collect ignored ->
            line((worker == 0 ? "session" : "worker") + ".collect();");
        case Instruction.Allocate op -> {
          var shape = shapes.get(op.shape());
          String name = declare(op.name(), shape, op.id());
          // Arrays have no identity constructor; advance the counter independently of layout.
          line("++a" + op.id() + ";");
          line(
              types.get(op.shape())
                  + " "
                  + name
                  + " = "
                  + allocation(shape, op.id(), "a" + op.id())
                  + ";");
          bindArray(name, shape, op.id(), "a" + op.id());
        }
        case Instruction.ReadRoot op ->
            line(
                types.get(op.shape())
                    + " "
                    + declare(op.name(), shapes.get(op.shape()), op.id())
                    + " = ("
                    + types.get(op.shape())
                    + ") Roots."
                    + roots.get(op.root())
                    + ";");
        case Instruction.WriteRoot op ->
            line("Roots." + roots.get(op.root()) + " = " + operand(op.value()) + ";");
        case Instruction.ReadReference op -> {
          var owner = binding(op.owner());
          line(
              types.get(op.shape())
                  + " "
                  + declare(op.name(), shapes.get(op.shape()), op.id())
                  + " = ("
                  + types.get(op.shape())
                  + ") "
                  + slot(owner.name(), owner.shape(), op.index(), true)
                  + ";");
        }
        case Instruction.WriteReference op -> {
          var owner = binding(op.owner());
          if (op.kind() != Value.ReferenceKind.STRONG) {
            String ref = "reference" + op.id();
            line(
                (op.kind() == Value.ReferenceKind.SOFT ? "SoftSlot " : "WeakSlot ")
                    + ref
                    + " = new "
                    + (op.kind() == Value.ReferenceKind.SOFT ? "SoftSlot" : "WeakSlot")
                    + "("
                    + worker
                    + ", "
                    + op.id()
                    + ", ++w"
                    + op.id()
                    + ", -1, "
                    + operand(op.value())
                    + ");");
            line(slot(owner.name(), owner.shape(), op.index(), true) + " = " + ref + ";");
            String value =
                op.value() instanceof Operand.Name name ? binding(name.name()).name() : null;
            reference(op.id(), "INSTALL", "0L", ref, value);
            line(ref + " = null;");
            continue;
          }
          line(
              slot(owner.name(), owner.shape(), op.index(), true)
                  + " = "
                  + operand(op.value())
                  + ";");
        }
        case Instruction.WriteData op -> {
          var owner = binding(op.owner());
          line(
              slot(owner.name(), owner.shape(), op.index(), false)
                  + " = "
                  + operand(op.value())
                  + ";");
        }
        case Instruction.Release op -> {
          var binding = binding(op.name());
          if (binding.shape() != null) release(binding.name());
        }
        case Instruction.Scope scope -> {
          record(scope.id(), false);
          line("{");
          block(scope.body());
          line("}");
          continue;
        }
        case Instruction.Repeat repeat -> {
          record(repeat.id(), false);
          line(
              "for (long i"
                  + repeat.id()
                  + " = 0; i"
                  + repeat.id()
                  + " < "
                  + repeat.count()
                  + "L; i"
                  + repeat.id()
                  + "++) {");
          record(repeat.id(), true);
          block(repeat.body());
          line("}");
          continue;
        }
        case Instruction.Checkpoint op -> {
          record(op.id(), false);
          line("session.checkpoint(" + op.id() + ");");
          continue;
        }
        case Instruction.ReadData ignored -> throw new AssertionError();
      }
      record(instruction.id(), false);
    }
    for (var binding : scopes.pop().values()) if (binding.shape() != null) release(binding.name());
  }

  // A Java local's last use can precede GIR release; the fence preserves the modeled lifetime.
  private void release(String name) {
    line("java.lang.ref.Reference.reachabilityFence(" + name + "); " + name + " = null;");
  }

  private void reference(int operation, String kind, String value, String wrapper, String target) {
    String identity =
        target == null
            ? "0, 0, 0L"
            : "("
                + target
                + " == null ? 0 : "
                + target
                + ".idWorker), ("
                + target
                + " == null ? 0 : "
                + target
                + ".idSite), ("
                + target
                + " == null ? 0L : "
                + target
                + ".idOccurrence)";
    line(
        "reference(session, "
            + operation
            + ", ReferenceHistory.Kind."
            + kind
            + ".ordinal(), "
            + value
            + ", "
            + wrapper
            + ", "
            + identity
            + ");");
  }

  private void record(int operation, boolean iteration) {
    line(recorder() + ".record(" + operation + ", " + (iteration ? 1 : 0) + ", 0);");
  }

  private String recorder() {
    return worker == 0 ? "session" : "worker";
  }

  private String declare(String name, Shape shape, int id) {
    var binding = new Binding("b" + id, shape);
    scopes.peek().put(name, binding);
    return binding.name();
  }

  private Binding binding(String name) {
    for (var scope : scopes) if (scope.containsKey(name)) return scope.get(name);
    throw new IllegalArgumentException("Unknown binding " + name);
  }

  private String operand(Operand value) {
    return switch (value) {
      case Operand.Name name -> binding(name.name()).name();
      case Operand.Literal literal -> literal.value() + "L";
      case Operand.Null ignored -> "null";
    };
  }

  private String allocation(Shape shape, int site, String occurrence) {
    return switch (shape.kind()) {
      case OBJECT ->
          "new " + types.get(shape.name()) + "(" + worker + ", " + site + ", " + occurrence + ")";
      case REFERENCE_ARRAY -> "new Object[" + shape.references() + "]";
      case DATA_ARRAY -> "new long[" + shape.data() + "]";
    };
  }

  private void bindArray(String name, Shape shape, int site, String occurrence) {
    if (shape.kind() != Shape.Kind.OBJECT)
      line(
          "NativeObserver.bindArrayIdentity("
              + name
              + ", "
              + JsonFiles.GSON.toJson(shape.name())
              + ", "
              + worker
              + ", "
              + site
              + ", "
              + occurrence
              + ");");
  }

  private static String initial(Value.Reference value, Map<Long, String> objects) {
    return value.target() == null ? "null" : objects.get(value.target().occurrence());
  }

  private static String slot(String name, Shape shape, int index, boolean reference) {
    return name
        + (shape.kind() == Shape.Kind.OBJECT
            ? "." + (reference ? "f" : "d") + index
            : "[" + index + "]");
  }

  private static List<String> names(String prefix, int count) {
    return IntStream.range(0, count).mapToObj(index -> prefix + index).toList();
  }

  private void line(String text) {
    source.append(text).append('\n');
  }
}
