package io.connglli.gorrect.engine.gir;

/** Invalid syntax, static contract, or executed operation precondition. */
public final class GirException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public GirException(String message) {
    super(message);
  }
}
