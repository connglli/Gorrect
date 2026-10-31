package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.HashMap;
import java.util.List;

/** Matches exact execution events, with explicit opaque values only at model-derived hash sites. */
public final class ExactTraceVerifier {
  private ExactTraceVerifier() {}

  /**
   * Checks every present event before deriving a verdict. Incomplete prefixes cannot satisfy, but
   * present contradictions still refute. Hash subjects come solely from the independent oracle;
   * zero and collisions never create or merge logical identities. Expected events must come from
   * {@link Oracle}, not syntax-only capacity planning.
   *
   * @throws IllegalArgumentException if a hash obligation repeats or does not select a main-thread
   *     scalar event
   */
  public static ExactVerifier.Verdict verify(ExecutionTrace actual, OracleResult expected) {
    if (expected.identityHashes().isEmpty()) {
      var wanted = new ExecutionTrace(actual.run(), true, expected.events()).byWorker();
      if (actual.complete())
        return actual.byWorker().equals(wanted)
            ? ExactVerifier.Verdict.SATISFIED
            : ExactVerifier.Verdict.VIOLATED;
      for (var worker : actual.byWorker().entrySet()) {
        var events = wanted.getOrDefault(worker.getKey(), List.of());
        if (worker.getValue().size() > events.size()
            || !worker.getValue().equals(events.subList(0, worker.getValue().size())))
          return ExactVerifier.Verdict.VIOLATED;
      }
      return ExactVerifier.Verdict.INCONCLUSIVE;
    }

    var subjects = new HashMap<Integer, OracleResult.IdentityHash>();
    for (var observation : expected.identityHashes()) {
      int index = observation.eventIndex();
      if (index < 0
          || index >= expected.events().size()
          || expected.events().get(index).kind() != ExecutionTrace.Kind.SCALAR_READ
          || expected.events().get(index).worker() != 0
          || subjects.put(index, observation) != null)
        throw new IllegalArgumentException("Invalid model identity-hash obligation");
    }
    var hashes = new HashMap<HeapId, Long>();
    int common = Math.min(actual.events().size(), expected.events().size());
    for (int index = 0; index < common; index++) {
      var event = actual.events().get(index);
      var wanted = expected.events().get(index);
      var observation = subjects.get(index);
      if (observation == null) {
        if (!event.equals(wanted)) return ExactVerifier.Verdict.VIOLATED;
        continue;
      }

      // Match the execution position before attributing this opaque value to a model subject.
      var normalized =
          new ExecutionTrace.Event(
              event.operation(),
              event.kind(),
              wanted.value(),
              event.worker(),
              event.group(),
              event.round(),
              event.phase());
      if (!normalized.equals(wanted)
          || event.value() < Integer.MIN_VALUE
          || event.value() > Integer.MAX_VALUE) return ExactVerifier.Verdict.VIOLATED;
      if (observation.subject() == null) {
        if (event.value() != 0) return ExactVerifier.Verdict.VIOLATED;
      } else {
        var previous = hashes.putIfAbsent(observation.subject(), event.value());
        if (previous != null && previous.longValue() != event.value())
          return ExactVerifier.Verdict.VIOLATED;
      }
    }
    if (actual.events().size() > expected.events().size()
        || actual.complete() && actual.events().size() != expected.events().size())
      return ExactVerifier.Verdict.VIOLATED;
    return actual.complete() ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.INCONCLUSIVE;
  }
}
