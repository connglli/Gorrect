package io.connglli.gorrect.protocol;

import java.time.Instant;
import java.util.List;

/** Decoded JFR request spans and VM cycles; completion does not establish object reclamation. */
public record CollectionEvidence(List<Request> requests, List<Cycle> cycles) {
  public static final int VERSION = 1;
  public static final String REQUEST_EVENT = "gorrect.CollectionRequest";

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

  public record Cycle(long id, String name, String cause, Instant begin, Instant end) {}

  public CollectionEvidence {
    requests = List.copyOf(requests);
    cycles = List.copyOf(cycles);
  }
}
