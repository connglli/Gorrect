package io.connglli.gorrect.engine.gir;

/** A scoped binding name, a signed 64-bit literal, or an explicit null reference. */
public sealed interface Operand {
  record Name(String name) implements Operand {}

  record Literal(long value) implements Operand {}

  /** Keeps a null reference distinct from a missing AST operand. */
  enum Null implements Operand {
    INSTANCE
  }
}
