package io.connglli.gorrect.runtime;

/**
 * One mutator's bounded primitive observations. The session supplies worker zero; each worker
 * context supplies its own worker/visit/phase. Only the owning mutator writes; joining that thread
 * publishes its records for serialization. Arguments never carry workload references.
 */
public interface ObservationRecorder {
  /**
   * Appends one event for the internal GIR instruction ID, using an {@link
   * io.connglli.gorrect.protocol.ExecutionTrace.Kind} ordinal and that kind's scalar result. Calls
   * retain per-writer order without imposing an order on other workers. Exhausting the reserved
   * buffer aborts execution; unused capacity is never serialized as predicted events.
   */
  void record(int operation, int kind, long value);

  /**
   * Appends linked scalar and identity evidence at one worker-local trace position. Kind is an
   * {@link io.connglli.gorrect.protocol.ReferenceHistory.Kind} ordinal; value is its observed
   * scalar result. Strength is zero for a strong read or empty queue result, one for soft, and two
   * for weak.
   *
   * <p>The wrapper tuple follows {@link io.connglli.gorrect.protocol.ReferenceHistory.Identity};
   * wrapperWorker identifies its creator, independently of the observing worker. The target tuple
   * follows {@link io.connglli.gorrect.protocol.ReferenceHistory.Target}. An absent wrapper or
   * target uses all-zero fields for that tuple. Exhaustion aborts the case instead of dropping an
   * observation.
   */
  void reference(
      int operation,
      int kind,
      long value,
      int strength,
      int wrapperWorker,
      int wrapperSite,
      long wrapperOccurrence,
      int wrapperSlot,
      int targetWorker,
      int targetSite,
      long targetOccurrence);
}
