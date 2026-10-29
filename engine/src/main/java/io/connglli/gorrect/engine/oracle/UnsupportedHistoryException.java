package io.connglli.gorrect.engine.oracle;

/** A workload requires a memory-model profile the checker does not implement. */
public final class UnsupportedHistoryException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public UnsupportedHistoryException(String message) {
    super(message);
  }
}
