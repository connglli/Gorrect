package io.connglli.gorrect.engine.gir;

/** Portable ordering guarantees; stronger effects from generated storage are not assumed. */
public enum MemoryMode {
  /** Ordinary access; unordered conflicting accesses are outside the supported profile. */
  PLAIN,
  /**
   * Volatile read/write effects, with a common synchronization order across volatile operations.
   */
  VOLATILE
}
