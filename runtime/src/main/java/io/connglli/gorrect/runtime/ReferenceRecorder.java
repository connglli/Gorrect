package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;

/** Bounded scalar storage for reference identities; it never retains a wrapper or referent. */
final class ReferenceRecorder {
  private final int[] sequences,
      kinds,
      strengths,
      workers,
      sites,
      slots,
      targetWorkers,
      targetSites;
  private final long[] occurrences, targetOccurrences;
  private final int worker;
  private int count;

  ReferenceRecorder(int worker, int capacity) {
    this.worker = worker;
    sequences = new int[capacity];
    kinds = new int[capacity];
    strengths = new int[capacity];
    workers = new int[capacity];
    sites = new int[capacity];
    slots = new int[capacity];
    targetWorkers = new int[capacity];
    targetSites = new int[capacity];
    occurrences = new long[capacity];
    targetOccurrences = new long[capacity];
  }

  void record(
      int sequence,
      int kind,
      int strength,
      int wrapperWorker,
      int wrapperSite,
      long wrapperOccurrence,
      int wrapperSlot,
      int targetWorker,
      int targetSite,
      long targetOccurrence) {
    if (count == sequences.length)
      throw new IllegalStateException("Reference history capacity exhausted");
    sequences[count] = sequence;
    kinds[count] = kind;
    strengths[count] = strength;
    workers[count] = wrapperWorker;
    sites[count] = wrapperSite;
    occurrences[count] = wrapperOccurrence;
    slots[count] = wrapperSlot;
    targetWorkers[count] = targetWorker;
    targetSites[count] = targetSite;
    targetOccurrences[count++] = targetOccurrence;
  }

  void appendTo(List<ReferenceHistory.Event> events) {
    var types = ReferenceHistory.Kind.values();
    for (int index = 0; index < count; index++) {
      var wrapper =
          strengths[index] == 0
              ? null
              : new ReferenceHistory.Identity(
                  workers[index], sites[index], occurrences[index], slots[index]);
      var target =
          targetOccurrences[index] == 0
              ? null
              : new ReferenceHistory.Target(
                  targetWorkers[index], targetSites[index], targetOccurrences[index]);
      events.add(
          new ReferenceHistory.Event(
              worker, sequences[index], types[kinds[index]], strengths[index], wrapper, target));
    }
  }
}
