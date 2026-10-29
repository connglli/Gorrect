package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;

/** A single writer's fixed storage. Joining its thread publishes the appended scalars. */
final class TraceRecorder {
  private final int worker;
  private final int[] operations, groups, phases;
  private final byte[] kinds;
  private final long[] values, rounds;
  private int count;

  TraceRecorder(int worker, int capacity) {
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
