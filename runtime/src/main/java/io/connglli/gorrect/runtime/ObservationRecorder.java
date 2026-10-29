package io.connglli.gorrect.runtime;

/** A single writer's primitive observations; arguments never carry workload references. */
public interface ObservationRecorder {
  /**
   * Appends a scalar event using an {@link io.connglli.gorrect.protocol.ExecutionTrace.Kind}
   * ordinal.
   */
  void record(int operation, int kind, long value);

  /**
   * Appends linked scalar/identity evidence using a {@link
   * io.connglli.gorrect.protocol.ReferenceHistory.Kind} ordinal. The recorder supplies the
   * observing worker; wrapperWorker identifies the wrapper's creator.
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
