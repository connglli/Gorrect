package io.connglli.gorrect.engine.gir;

/** Per-access ordering, preserved by emission independently of a location's other accesses. */
public enum MemoryMode {
  /** Ordinary access; unordered conflicting accesses are outside the supported profile. */
  PLAIN,
  /**
   * Volatile read/write effects, with a common synchronization order across volatile operations.
   */
  VOLATILE
}
