package io.connglli.gorrect.engine.oracle;

/** A history search ran out of evidence-checking resources; it has established no violation. */
public final class HistoryLimitException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public HistoryLimitException(String message) {
    super(message);
  }
}
