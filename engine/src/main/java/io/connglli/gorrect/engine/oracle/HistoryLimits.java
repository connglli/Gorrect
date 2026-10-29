package io.connglli.gorrect.engine.oracle;

/** Bounds both simultaneous possibilities and cumulative clearing/search work. */
public record HistoryLimits(int states, long transitions) {
  public static final HistoryLimits DEFAULT = new HistoryLimits(4096, 1_000_000);

  public HistoryLimits {
    if (states < 1 || transitions < 1)
      throw new IllegalArgumentException("History bounds must be positive");
  }
}
