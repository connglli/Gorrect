package io.connglli.gorrect.engine.oracle;

/** Shared by search forks so cloning cannot reset cumulative work limits. */
final class HistoryBudget {
  private final HistoryLimits limits;
  private long work;

  HistoryBudget(HistoryLimits limits) {
    this.limits = limits;
  }

  void spend(long units) {
    if (units < 0 || units > limits.transitions() - work)
      throw new HistoryLimitException("History-search work budget exhausted");
    work += units;
  }

  void states(long count) {
    if (count > limits.states())
      throw new HistoryLimitException("History-search state budget exhausted");
  }
}
