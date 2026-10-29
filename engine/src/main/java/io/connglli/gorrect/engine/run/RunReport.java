package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.oracle.ExactVerifier;
import java.util.List;

/** Execution, graph validity, and capability coverage are independent result dimensions. */
public record RunReport(
    Outcome execution,
    ExactVerifier.Verdict verdict,
    List<String> unmetRequirements,
    List<String> problems) {
  public enum Outcome {
    COMPLETED,
    INVALID_WORKLOAD,
    CHECKING_LIMIT,
    UNSUPPORTED,
    COMPILATION_FAILURE,
    STARTUP_FAILURE,
    RUNTIME_ERROR,
    CRASH,
    TIMEOUT,
    CAMPAIGN_LIMIT,
    PROTOCOL_ERROR,
    EXTRACTION_ERROR,
    INTERRUPTED
  }

  public RunReport {
    unmetRequirements = List.copyOf(unmetRequirements);
    problems = List.copyOf(problems);
  }

  public int exitStatus() {
    if (verdict == ExactVerifier.Verdict.VIOLATED) return 1;
    if (execution == Outcome.INVALID_WORKLOAD) return 2;
    if (execution == Outcome.UNSUPPORTED || !unmetRequirements.isEmpty()) return 3;
    if (execution == Outcome.COMPLETED && verdict == ExactVerifier.Verdict.SATISFIED) return 0;
    if (execution == Outcome.CAMPAIGN_LIMIT
        || execution == Outcome.EXTRACTION_ERROR
        || execution == Outcome.CHECKING_LIMIT
        || execution == Outcome.COMPLETED) return 5;
    return execution == Outcome.INTERRUPTED ? 130 : 4;
  }

  public static RunReport failure(Outcome outcome, String problem) {
    return new RunReport(outcome, ExactVerifier.Verdict.INCONCLUSIVE, List.of(), List.of(problem));
  }

  /** A confirmed violation remains visible when other repetitions lack coverage. */
  public static int combineExitStatus(int earlier, int later) {
    if (earlier == 1 || later == 1) return 1;
    return earlier == 0 ? later : earlier;
  }
}
