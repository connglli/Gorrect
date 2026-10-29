package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

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
  private int count;

  ReferenceRecorder(int capacity) {
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
      int worker,
      int site,
      long occurrence,
      int slot,
      int targetWorker,
      int targetSite,
      long targetOccurrence) {
    if (count == sequences.length)
      throw new IllegalStateException("Reference history capacity exhausted");
    sequences[count] = sequence;
    kinds[count] = kind;
    strengths[count] = strength;
    workers[count] = worker;
    sites[count] = site;
    occurrences[count] = occurrence;
    slots[count] = slot;
    targetWorkers[count] = targetWorker;
    targetSites[count] = targetSite;
    targetOccurrences[count++] = targetOccurrence;
  }

  void write(Path path, long run, boolean complete) throws IOException {
    var events = new ArrayList<ReferenceHistory.Event>(count);
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
              sequences[index], types[kinds[index]], strengths[index], wrapper, target));
    }
    new ReferenceHistory(run, complete, events).write(path);
  }
}
