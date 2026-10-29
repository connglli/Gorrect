package io.connglli.gorrect.engine.gir;

/** A source-level atomic operand. Owner names a root or a handle; root indices are zero. */
public record Location(Kind kind, String owner, int index) {
  public enum Kind {
    ROOT,
    REFERENCE,
    DATA
  }

  public boolean reference() {
    return kind != Kind.DATA;
  }
}
