package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;

/** A single writer's fixed storage. Joining its thread publishes the appended scalars. */
final class TraceRecorder {
  private static final ReferenceHistory.Kind[] REFERENCE_KINDS = ReferenceHistory.Kind.values();
  private final int worker;
  private final ReferenceRecorder references;
  private final int[] operations, groups, phases;
  private final byte[] kinds;
  private final long[] values, rounds;
  private int count;

  int size() {
    return count;
  }

  TraceRecorder(int worker, int capacity) {
    this(worker, capacity, false);
  }

  TraceRecorder(int worker, int capacity, boolean identities) {
    references = identities ? new ReferenceRecorder(worker, capacity) : null;
    this.worker = worker;
    operations = new int[capacity];
    groups = new int[capacity];
    phases = new int[capacity];
    kinds = new byte[capacity];
    values = new long[capacity];
    rounds = new long[capacity];
  }

  void record(int operation, int kind, long value, int group, long round, int phase) {
    if (count == operations.length)
      throw new IllegalStateException("Trace capacity exhausted for worker " + worker);
    operations[count] = operation;
    kinds[count] = (byte) kind;
    values[count] = value;
    groups[count] = group;
    rounds[count] = round;
    phases[count++] = phase;
  }

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
      long targetOccurrence,
      int group,
      long round,
      int phase) {
    if (references == null)
      throw new IllegalStateException("Reference recording was not configured");
    var type = REFERENCE_KINDS[kind];
    var traceKind =
        switch (type) {
          case INSTALL, CLEAR -> ExecutionTrace.Kind.OPERATION;
          case ACQUIRE -> ExecutionTrace.Kind.ACQUIRE;
          case STATE -> ExecutionTrace.Kind.REFERENCE_STATE;
          case QUEUE -> ExecutionTrace.Kind.QUEUE_POLL;
          case STRONG_READ -> ExecutionTrace.Kind.REFERENCE_READ;
        };
    record(operation, traceKind.ordinal(), value, group, round, phase);
    // count is now the just-appended scalar event's one-based position in this worker's trace.
    references.record(
        count,
        kind,
        strength,
        wrapperWorker,
        wrapperSite,
        wrapperOccurrence,
        wrapperSlot,
        targetWorker,
        targetSite,
        targetOccurrence);
  }

  void appendReferences(List<ReferenceHistory.Event> events) {
    if (references != null) references.appendTo(events);
  }

  void appendTo(List<ExecutionTrace.Event> events) {
    var types = ExecutionTrace.Kind.values();
    // Only appended records become evidence; an unused capacity is never filled with predictions.
    for (int index = 0; index < count; index++)
      events.add(
          new ExecutionTrace.Event(
              operations[index],
              types[kinds[index]],
              values[index],
              worker,
              groups[index],
              rounds[index],
              phases[index]));
  }
}
