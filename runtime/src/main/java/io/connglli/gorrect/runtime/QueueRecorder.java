package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;

/** Main-thread primitive storage; no queue entry, wrapper or referent is retained here. */
final class QueueRecorder {
  private final int[] sequences, operations, kinds, queues, results, workers, sites, slots;
  private final long[] occurrences;
  private int count;

  QueueRecorder(int capacity) {
    sequences = new int[capacity];
    operations = new int[capacity];
    kinds = new int[capacity];
    queues = new int[capacity];
    results = new int[capacity];
    workers = new int[capacity];
    sites = new int[capacity];
    slots = new int[capacity];
    occurrences = new long[capacity];
  }

  void record(
      int sequence,
      int operation,
      int kind,
      int queue,
      int result,
      int worker,
      int site,
      long occurrence,
      int slot) {
    if (count == sequences.length)
      throw new IllegalStateException("Queue evidence capacity exhausted");
    sequences[count] = sequence;
    operations[count] = operation;
    kinds[count] = kind;
    queues[count] = queue;
    results[count] = result;
    workers[count] = worker;
    sites[count] = site;
    occurrences[count] = occurrence;
    slots[count++] = slot;
  }

  QueueHistory history(long run, boolean complete) {
    var events = new ArrayList<QueueHistory.Event>();
    for (int i = 0; i < count; i++) {
      var id =
          occurrences[i] == 0
              ? null
              : new ReferenceHistory.Identity(workers[i], sites[i], occurrences[i], slots[i]);
      events.add(
          new QueueHistory.Event(
              0,
              sequences[i],
              operations[i],
              QueueHistory.Kind.values()[kinds[i]],
              queues[i],
              results[i],
              id));
    }
    return new QueueHistory(run, complete, events);
  }
}
