package io.connglli.gorrect.engine.oracle;

/**
 * Nonnegative bounds on model work and retained graph descriptions.
 *
 * @param steps maximum instructions and control markers, including loop iterations
 * @param objects maximum initial/new objects and reference wrappers, including unreachable ones
 * @param slots maximum reference and data slots across allocated objects
 * @param snapshotCells cumulative cells: one per snapshot plus its objects, roots, handles, and
 *     value slots; includes the final graph
 */
public record OracleLimits(long steps, long objects, long slots, long snapshotCells) {
  public static final OracleLimits DEFAULT = new OracleLimits(100_000, 10_000, 100_000, 1_000_000);

  public OracleLimits {
    if (steps < 0 || objects < 0 || slots < 0 || snapshotCells < 0) {
      throw new IllegalArgumentException("Negative oracle budget");
    }
  }
}
