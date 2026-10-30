package io.connglli.gorrect.protocol;

import java.time.Instant;
import java.util.List;

/** Decoded JFR request spans and VM cycles; completion does not establish object reclamation. */
public record CollectionEvidence(List<Request> requests, List<Cycle> cycles) {
  public static final int VERSION = 1;
  public static final String REQUEST_EVENT = "gorrect.CollectionRequest";

  /**
   * One explicit GIR collection call. Operation is its instruction ID; group/round/phase identify
   * the worker visit, or are all zero for the main thread. Ordinal is one-based per main thread or
   * worker visit; thread is the Java thread ID. Begin/end bound the call in the recording's clock.
   * Returned records normal return, independently of whether a qualifying VM cycle occurred.
   */
  public record Request(
      int worker,
      int operation,
      int group,
      long round,
      int phase,
      long ordinal,
      long thread,
      String control,
      boolean returned,
      Instant begin,
      Instant end) {}

  /**
   * VM-reported GC identity, kind, cause, and full interval in the same recording clock as
   * requests.
   */
  public record Cycle(long id, String name, String cause, Instant begin, Instant end) {}

  public CollectionEvidence {
    requests = List.copyOf(requests);
    cycles = List.copyOf(cycles);
  }
}
