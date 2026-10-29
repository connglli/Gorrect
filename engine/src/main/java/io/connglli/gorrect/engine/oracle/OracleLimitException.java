package io.connglli.gorrect.engine.oracle;

/** An exhausted checking budget cannot establish a semantic violation. */
public final class OracleLimitException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OracleLimitException(String message) {
    super(message);
  }
}
