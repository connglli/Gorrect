package io.connglli.gorrect.engine.gir;

import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Parses GIR into immutable, statically validated syntax trees. */
public final class GirParser {
  private final Lexer lexer;
  private final Map<Integer, String> labels = new HashMap<>();
  private Token current;
  private Token lookahead;
  private int nextOperation = 1;
  private int nextWorker = 1;

  private GirParser(String source) {
    lexer = new Lexer(source);
    current = lexer.next();
    lookahead = lexer.next();
  }

  /**
   * Assigns internal IDs in source preorder and retains user labels. Executed null and target-shape
   * preconditions remain for the interpreter to check.
   *
   * @throws GirException if source syntax or static GIR contracts are invalid
   */
  public static GirProgram parse(String source) {
    var parser = new GirParser(source);
    try {
      var program = parser.program();
      GirValidator.validate(program);
      return program;
    } catch (GirException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw parser.error(exception.getMessage());
    }
  }

  private GirProgram program() {
    expect("gir");
    int version = integer();
    expect(";");
    var shapes = new ArrayList<Shape>();
    var objects = new ArrayList<GirProgram.InitialObject>();
    var roots = new ArrayList<GirProgram.Root>();
    var requirements = new HashSet<String>();
    while (!current.text().equals("program")) {
      switch (next().text()) {
        case "require" -> {
          String requirement = next().text();
          if (!requirements.add(requirement)) throw error("Duplicate requirement " + requirement);
          expect(";");
        }
        case "shape" -> {
          String name = name();
          String kind = next().text();
          shapes.add(
              switch (kind) {
                case "object" -> new Shape(name, Shape.Kind.OBJECT, integer(), integer());
                case "refs" -> new Shape(name, Shape.Kind.REFERENCE_ARRAY, integer(), 0);
                case "data" -> new Shape(name, Shape.Kind.DATA_ARRAY, 0, integer());
                default -> throw error("Unsupported shape kind " + kind);
              });
          expect(";");
        }
        case "object" -> {
          long id = number();
          String shape = name();
          var references = references();
          var data = data();
          expect(";");
          objects.add(new GirProgram.InitialObject(id, shape, references, data));
        }
        case "root" -> {
          String name = name();
          expect("=");
          var value = reference();
          expect(";");
          roots.add(new GirProgram.Root(name, value));
        }
        default -> throw error("Expected a declaration or program");
      }
    }
    expect("program");
    var body = block(0);
    expect("");
    return new GirProgram(version, shapes, objects, roots, body, labels, requirements);
  }

  private List<Instruction> block(int depth) {
    if (depth > GirValidator.MAX_NESTING) throw error("GIR nesting limit exceeded");
    expect("{");
    var instructions = new ArrayList<Instruction>();
    while (!take("}")) {
      int id = nextOperation++;
      // Labels may use instruction keywords, so ':' provides the distinction.
      if (lookahead.text().equals(":")) {
        String label = name();
        expect(":");
        labels.put(id, label);
      }
      String opcode = next().text();
      Instruction instruction =
          switch (opcode) {
            case "allocate" -> new Instruction.Allocate(id, name(), name());
            case "read-volatile-root" ->
                new Instruction.ReadRoot(id, name(), name(), name(), MemoryMode.VOLATILE);
            case "write-volatile-root" ->
                new Instruction.WriteRoot(id, name(), operand(), MemoryMode.VOLATILE);
            case "read-volatile-ref" ->
                new Instruction.ReadReference(
                    id, name(), name(), name(), integer(), MemoryMode.VOLATILE);
            case "write-volatile-ref" ->
                new Instruction.WriteReference(
                    id,
                    name(),
                    integer(),
                    operand(),
                    Value.ReferenceKind.STRONG,
                    MemoryMode.VOLATILE);
            case "read-volatile-data" ->
                new Instruction.ReadData(id, name(), name(), integer(), MemoryMode.VOLATILE);
            case "write-volatile-data" ->
                new Instruction.WriteData(id, name(), integer(), operand(), MemoryMode.VOLATILE);
            case "compare-set-root", "compare-set-ref", "compare-set-data" ->
                new Instruction.CompareSet(id, name(), location(opcode), operand(), operand());
            case "exchange-root", "exchange-ref" ->
                new Instruction.Exchange(id, name(), name(), location(opcode), operand());
            case "exchange-data" ->
                new Instruction.Exchange(id, name(), null, location(opcode), operand());
            case "if-present" -> new Instruction.IfPresent(id, name(), block(depth + 1));
            case "read-root" -> new Instruction.ReadRoot(id, name(), name(), name());
            case "write-root" -> new Instruction.WriteRoot(id, name(), operand());
            case "read-ref" -> new Instruction.ReadReference(id, name(), name(), name(), integer());
            case "write-ref" -> new Instruction.WriteReference(id, name(), integer(), operand());
            case "write-soft" ->
                new Instruction.WriteReference(
                    id, name(), integer(), operand(), Value.ReferenceKind.SOFT);
            case "write-weak" ->
                new Instruction.WriteReference(
                    id, name(), integer(), operand(), Value.ReferenceKind.WEAK);
            case "acquire" ->
                new Instruction.Acquire(id, name(), name(), name(), integer(), block(depth + 1));
            case "clear-ref" -> new Instruction.ClearReference(id, name(), integer());
            case "is-cleared" -> new Instruction.ReferenceState(id, name(), name(), integer());
            case "poll-queue" -> new Instruction.PollQueue(id);
            case "read-data" -> new Instruction.ReadData(id, name(), name(), integer());
            case "write-data" -> new Instruction.WriteData(id, name(), integer(), operand());
            case "release" -> new Instruction.Release(id, name());
            case "checkpoint" -> new Instruction.Checkpoint(id);
            case "collect" -> new Instruction.Collect(id);
            case "barrier" -> new Instruction.Barrier(id);
            case "parallel" -> parallel(id, depth + 1);
            case "scope" -> new Instruction.Scope(id, block(depth + 1));
            case "repeat" -> new Instruction.Repeat(id, number(), block(depth + 1));
            default -> throw error("Unsupported instruction " + opcode);
          };
      if (instruction instanceof Instruction.Operation) expect(";");
      instructions.add(instruction);
    }
    return instructions;
  }

  private Location location(String opcode) {
    var kind =
        opcode.endsWith("root")
            ? Location.Kind.ROOT
            : opcode.endsWith("ref") ? Location.Kind.REFERENCE : Location.Kind.DATA;
    return new Location(kind, name(), kind == Location.Kind.ROOT ? 0 : integer());
  }

  private Instruction.Parallel parallel(int id, int depth) {
    expect("{");
    var workers = new ArrayList<Instruction.Worker>();
    while (!take("}")) {
      expect("worker");
      int worker = nextWorker++;
      String name = name();
      workers.add(new Instruction.Worker(worker, name, block(depth)));
    }
    return new Instruction.Parallel(id, workers);
  }

  private List<Value.Reference> references() {
    expect("[");
    var values = new ArrayList<Value.Reference>();
    if (!take("]")) {
      do {
        values.add(reference());
      } while (take(","));
      expect("]");
    }
    return values;
  }

  private List<Long> data() {
    expect("[");
    var values = new ArrayList<Long>();
    if (!take("]")) {
      do {
        values.add(number());
      } while (take(","));
      expect("]");
    }
    return values;
  }

  private Value.Reference reference() {
    if (current.text().equals("soft") || current.text().equals("weak")) {
      var kind = next().text().equals("soft") ? Value.ReferenceKind.SOFT : Value.ReferenceKind.WEAK;
      expect("(");
      ObjectId target = take("null") ? null : ObjectId.initial(number());
      expect(")");
      return new Value.Reference(kind, target);
    }
    return take("null") ? Value.Reference.NULL : Value.Reference.strong(ObjectId.initial(number()));
  }

  private Operand operand() {
    if (take("null")) return Operand.Null.INSTANCE;
    if (GirValidator.isName(current.text())) return new Operand.Name(name());
    return new Operand.Literal(number());
  }

  private String name() {
    if (!GirValidator.isName(current.text())) throw error("Expected an identifier");
    return next().text();
  }

  private int integer() {
    var token = current;
    long value = number();
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new GirException(
          token.line() + ":" + token.column() + ": Integer outside 32-bit range");
    }
    return (int) value;
  }

  private long number() {
    try {
      long value = Long.parseLong(current.text());
      next();
      return value;
    } catch (NumberFormatException exception) {
      throw error("Expected a signed 64-bit integer");
    }
  }

  private boolean take(String text) {
    if (!current.text().equals(text)) return false;
    next();
    return true;
  }

  private void expect(String text) {
    if (!take(text))
      throw error("Expected " + (text.isEmpty() ? "end of input" : "'" + text + "'"));
  }

  private Token next() {
    var token = current;
    current = lookahead;
    lookahead = lexer.next();
    return token;
  }

  private GirException error(String message) {
    return new GirException(current.line() + ":" + current.column() + ": " + message);
  }

  private record Token(String text, int line, int column) {}

  private static final class Lexer {
    private final String source;
    private int offset;
    private int line = 1;
    private int column = 1;

    private Lexer(String source) {
      this.source = Objects.requireNonNull(source);
    }

    private Token next() {
      while (offset < source.length()) {
        if (Character.isWhitespace(source.charAt(offset))) {
          advance();
        } else if (source.startsWith("//", offset)) {
          while (offset < source.length()
              && source.charAt(offset) != '\n'
              && source.charAt(offset) != '\r') advance();
        } else {
          break;
        }
      }
      int start = offset;
      int startLine = line;
      int startColumn = column;
      if (offset < source.length()) {
        char first = advance();
        if (Character.isLetter(first) || first == '_') {
          while (offset < source.length()) {
            char c = source.charAt(offset);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') break;
            advance();
          }
        } else if (Character.isDigit(first) || first == '-') {
          while (offset < source.length() && Character.isDigit(source.charAt(offset))) advance();
        }
      }
      return new Token(source.substring(start, offset), startLine, startColumn);
    }

    private char advance() {
      char c = source.charAt(offset++);
      if (c == '\r') {
        line++;
        column = 1;
      } else if (c == '\n') {
        if (offset < 2 || source.charAt(offset - 2) != '\r') line++;
        column = 1;
      } else {
        column++;
      }
      return c;
    }
  }
}
