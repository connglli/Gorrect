package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.Value;
import java.util.Map;
import java.util.stream.Collectors;

/** Canonical GIR serialization with source labels and instruction order preserved. */
public final class GirFormatter {
  private final StringBuilder output = new StringBuilder();
  private final Map<Integer, String> labels;

  private GirFormatter(Map<Integer, String> labels) {
    this.labels = labels;
  }

  /**
   * Validates before rendering so reparsing cannot silently change instruction identities.
   *
   * @throws GirException if the program is invalid, including noncanonical internal IDs
   */
  public static String format(GirProgram program) {
    GirValidator.validate(program);
    var formatter = new GirFormatter(program.labels());
    formatter.program(program);
    return formatter.output.toString();
  }

  private void program(GirProgram program) {
    line(0, "gir " + program.version() + ";");
    program.requirements().stream()
        .sorted()
        .forEach(requirement -> line(0, "require " + requirement + ";"));
    for (var shape : program.shapes()) {
      String dimensions =
          switch (shape.kind()) {
            case OBJECT -> "object " + shape.references() + " " + shape.data();
            case REFERENCE_ARRAY -> "refs " + shape.references();
            case DATA_ARRAY -> "data " + shape.data();
          };
      line(0, "shape " + shape.name() + " " + dimensions + ";");
    }
    for (var object : program.objects()) {
      String references =
          object.references().stream()
              .map(GirFormatter::reference)
              .collect(Collectors.joining(", "));
      String data = object.data().stream().map(Object::toString).collect(Collectors.joining(", "));
      line(
          0,
          "object " + object.id() + " " + object.shape() + " [" + references + "] [" + data + "];");
    }
    for (var root : program.roots()) {
      line(0, "root " + root.name() + " = " + reference(root.value()) + ";");
    }
    line(0, "");
    line(0, "program {");
    program.body().forEach(instruction -> instruction(instruction, 1));
    line(0, "}");
  }

  private void instruction(Instruction instruction, int indent) {
    String label = labels.get(instruction.id());
    String prefix = label == null ? "" : label + ": ";
    switch (instruction) {
      case Instruction.IfPresent present -> {
        line(indent, prefix + "if-present " + present.name() + " {");
        present.body().forEach(child -> instruction(child, indent + 1));
        line(indent, "}");
      }
      case Instruction.Acquire acquire -> {
        line(
            indent,
            prefix
                + "acquire "
                + acquire.name()
                + " "
                + acquire.shape()
                + " "
                + acquire.owner()
                + " "
                + acquire.index()
                + " {");
        acquire.body().forEach(child -> instruction(child, indent + 1));
        line(indent, "}");
      }
      case Instruction.Parallel parallel -> {
        line(indent, prefix + "parallel {");
        for (var worker : parallel.workers()) {
          line(indent + 1, "worker " + worker.name() + " {");
          worker.body().forEach(child -> instruction(child, indent + 2));
          line(indent + 1, "}");
        }
        line(indent, "}");
      }
      case Instruction.Scope scope -> {
        line(indent, prefix + "scope {");
        scope.body().forEach(child -> instruction(child, indent + 1));
        line(indent, "}");
      }
      case Instruction.Repeat repeat -> {
        line(indent, prefix + "repeat " + repeat.count() + " {");
        repeat.body().forEach(child -> instruction(child, indent + 1));
        line(indent, "}");
      }
      case Instruction.Operation operation -> line(indent, prefix + operation(operation) + ";");
    }
  }

  private static String operation(Instruction.Operation operation) {
    return switch (operation) {
      case Instruction.Allocate op -> "allocate " + op.name() + " " + op.shape();
      case Instruction.ReadRoot op ->
          (op.mode() == MemoryMode.VOLATILE ? "read-volatile-root " : "read-root ")
              + op.name()
              + " "
              + op.shape()
              + " "
              + op.root();
      case Instruction.WriteRoot op ->
          (op.mode() == MemoryMode.VOLATILE ? "write-volatile-root " : "write-root ")
              + op.root()
              + " "
              + operand(op.value());
      case Instruction.ReadReference op ->
          (op.mode() == MemoryMode.VOLATILE ? "read-volatile-ref " : "read-ref ")
              + op.name()
              + " "
              + op.shape()
              + " "
              + op.owner()
              + " "
              + op.index();
      case Instruction.WriteReference op ->
          (op.kind() == Value.ReferenceKind.STRONG
                  ? (op.mode() == MemoryMode.VOLATILE ? "write-volatile-ref " : "write-ref ")
                  : op.kind() == Value.ReferenceKind.SOFT ? "write-soft " : "write-weak ")
              + op.owner()
              + " "
              + op.index()
              + " "
              + operand(op.value());
      case Instruction.ClearReference op -> "clear-ref " + op.owner() + " " + op.index();
      case Instruction.ReferenceState op ->
          "is-cleared " + op.name() + " " + op.owner() + " " + op.index();
      case Instruction.PollQueue ignored -> "poll-queue";
      case Instruction.ReadData op ->
          (op.mode() == MemoryMode.VOLATILE ? "read-volatile-data " : "read-data ")
              + op.name()
              + " "
              + op.owner()
              + " "
              + op.index();
      case Instruction.WriteData op ->
          (op.mode() == MemoryMode.VOLATILE ? "write-volatile-data " : "write-data ")
              + op.owner()
              + " "
              + op.index()
              + " "
              + operand(op.value());
      case Instruction.CompareSet op ->
          "compare-set-"
              + locationKind(op.location())
              + " "
              + op.name()
              + " "
              + location(op.location())
              + " "
              + operand(op.expected())
              + " "
              + operand(op.update());
      case Instruction.Exchange op ->
          "exchange-"
              + locationKind(op.location())
              + " "
              + op.name()
              + " "
              + (op.shape() == null ? "" : op.shape() + " ")
              + location(op.location())
              + " "
              + operand(op.update());
      case Instruction.Release op -> "release " + op.name();
      case Instruction.Checkpoint ignored -> "checkpoint";
      case Instruction.Collect ignored -> "collect";
      case Instruction.Barrier ignored -> "barrier";
    };
  }

  private static String locationKind(Location location) {
    return switch (location.kind()) {
      case ROOT -> "root";
      case REFERENCE -> "ref";
      case DATA -> "data";
    };
  }

  private static String location(Location location) {
    return location.owner() + (location.kind() == Location.Kind.ROOT ? "" : " " + location.index());
  }

  private static String operand(Operand operand) {
    return switch (operand) {
      case Operand.Name name -> name.name();
      case Operand.Literal literal -> Long.toString(literal.value());
      case Operand.Null ignored -> "null";
    };
  }

  private static String reference(Value.Reference reference) {
    String target =
        reference.target() == null ? "null" : Long.toString(reference.target().occurrence());
    return reference.kind() == Value.ReferenceKind.STRONG
        ? target
        : (reference.kind() == Value.ReferenceKind.SOFT ? "soft(" : "weak(") + target + ")";
  }

  private void line(int indent, String value) {
    output.append("  ".repeat(indent)).append(value).append('\n');
  }
}
