package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NamedQueueHistoryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pollMatchesMembershipRatherThanLegacyDelay(boolean pretendEmpty) {
    var program =
        GirParser.parse(
            """
            gir 1; shape W weak; queue a W;
            program { allocate-ref r W null a; enqueue-ref added r;
              poll-queue held a; release held; release r; }
            """);
    var id = new ReferenceHistory.Identity(0, 1, 1, -1);
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.SCALAR_READ, 1),
                new ExecutionTrace.Event(3, ExecutionTrace.Kind.QUEUE_POLL, pretendEmpty ? 0 : 1),
                new ExecutionTrace.Event(4, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(5, ExecutionTrace.Kind.OPERATION, 0)));
    var refs =
        new ReferenceHistory(
            1,
            true,
            List.of(new ReferenceHistory.Event(1, ReferenceHistory.Kind.CREATE, 2, id, null)));
    var queues =
        new QueueHistory(
            1,
            true,
            List.of(
                new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.CREATE, 1, 0, id),
                new QueueHistory.Event(0, 2, 2, QueueHistory.Kind.ENQUEUE, 1, 1, id),
                new QueueHistory.Event(
                    0,
                    3,
                    3,
                    QueueHistory.Kind.POLL,
                    1,
                    pretendEmpty ? 0 : 1,
                    pretendEmpty ? null : id)));
    var graph =
        new Graph(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("a", new HeapQueue(1, Set.of())));
    var result =
        HistoryOracle.check(
            program,
            trace,
            refs,
            queues,
            List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(
        pretendEmpty ? ExactVerifier.Verdict.VIOLATED : ExactVerifier.Verdict.SATISFIED,
        result.verdict(),
        result.problems().toString());
  }

  @Test
  void falseNoQueueEnqueueStillClearsAStronglyProtectedTarget() {
    var program =
        GirParser.parse(
            """
            gir 1; shape W weak;
            program { allocate-ref inner W null; allocate-ref outer W inner;
              enqueue-ref failed outer; is-cleared state outer; }
            """);
    var inner = new ReferenceHistory.Identity(0, 1, 1, -1);
    var outer = new ReferenceHistory.Identity(0, 2, 1, -1);
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(3, ExecutionTrace.Kind.SCALAR_READ, 0),
                new ExecutionTrace.Event(4, ExecutionTrace.Kind.REFERENCE_STATE, 1)));
    var refs =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(1, ReferenceHistory.Kind.CREATE, 2, inner, null),
                new ReferenceHistory.Event(
                    2,
                    ReferenceHistory.Kind.CREATE,
                    2,
                    outer,
                    new ReferenceHistory.Target(0, 1, 1, 1, -1)),
                new ReferenceHistory.Event(4, ReferenceHistory.Kind.STATE, 2, outer, null)));
    var queues =
        new QueueHistory(
            1,
            true,
            List.of(
                new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.CREATE, 0, 0, inner),
                new QueueHistory.Event(0, 2, 2, QueueHistory.Kind.CREATE, 0, 0, outer),
                new QueueHistory.Event(0, 3, 3, QueueHistory.Kind.ENQUEUE, 0, 0, outer)));
    var result =
        HistoryOracle.check(
            program,
            trace,
            refs,
            queues,
            List.of(
                new HistoryOracle.Observation(
                    Optional.of(new Graph(Map.of(), Map.of(), Map.of())), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict(), result.problems().toString());
  }

  @ParameterizedTest
  @CsvSource({"false,false,false", "false,true,true", "true,false,false", "true,true,true"})
  void failurePrefixDistinguishesMissingIncompleteQueueEvidenceFromAProvedNullResult(
      boolean queueComplete, boolean present, boolean invalid) {
    var program =
        GirParser.parse(
            "gir 1; shape W weak; queue q W; program {"
                + " poll-queue delivered q; enqueue-ref failed delivered; }");
    var trace =
        new ExecutionTrace(
            1, false, List.of(new ExecutionTrace.Event(1, ExecutionTrace.Kind.QUEUE_POLL, 0)));
    var queue =
        new QueueHistory(
            1,
            queueComplete,
            present
                ? List.of(new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.POLL, 1, 0, null))
                : List.of());
    var result =
        HistoryOracle.checkFailure(
            program,
            trace,
            new ReferenceHistory(1, false, List.of()),
            queue,
            List.of(),
            HistoryLimits.DEFAULT);
    assertEquals(
        queueComplete && !present
            ? ExactVerifier.Verdict.VIOLATED
            : ExactVerifier.Verdict.INCONCLUSIVE,
        result.verdict(),
        result.problems().toString());
    assertEquals(invalid, result.invalidWorkload(), result.problems().toString());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void completeCheckingKeepsQueueCompletionAndRecordPresenceSeparate(
      boolean queueComplete, boolean present) {
    var program =
        GirParser.parse("gir 1; shape W weak; queue q W; program { poll-queue delivered q; }");
    var trace =
        new ExecutionTrace(
            1, true, List.of(new ExecutionTrace.Event(1, ExecutionTrace.Kind.QUEUE_POLL, 0)));
    var queue =
        new QueueHistory(
            1,
            queueComplete,
            present
                ? List.of(new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.POLL, 1, 0, null))
                : List.of());
    var graph =
        new Graph(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("q", new HeapQueue(1, Set.of())));
    var result =
        HistoryOracle.check(
            program,
            trace,
            new ReferenceHistory(1, true, List.of()),
            queue,
            List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(
        !queueComplete
            ? ExactVerifier.Verdict.INCONCLUSIVE
            : present ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
        result.verdict(),
        result.problems().toString());
    assertEquals(false, result.invalidWorkload());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void presentContradictoryQueueEvidenceStillRefutesAnIncompleteFailurePrefix(boolean complete) {
    var program =
        GirParser.parse(
            "gir 1; shape W weak; queue q W; program {"
                + " poll-queue delivered q; enqueue-ref failed delivered; }");
    var trace =
        new ExecutionTrace(
            1, false, List.of(new ExecutionTrace.Event(1, ExecutionTrace.Kind.QUEUE_POLL, 0)));
    var queue =
        new QueueHistory(
            1,
            complete,
            List.of(new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.POLL, 9, 0, null)));
    var result =
        HistoryOracle.checkFailure(
            program,
            trace,
            new ReferenceHistory(1, false, List.of()),
            queue,
            List.of(),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.problems().toString());
    assertEquals(false, result.invalidWorkload());
  }

  @Test
  void laterMissingQueuePrefixCannotEraseAnEarlierCoveredGraphRefutation() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; shape W weak; queue q W;
            object 1 N [] [7]; root main = 1;
            program { checkpoint; poll-queue delivered q; enqueue-ref failed delivered; }
            """);
    var trace =
        new ExecutionTrace(
            1,
            false,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.QUEUE_POLL, 0)));
    var id = ObjectId.initial(1);
    var graph =
        new Graph(
            Map.of(id, new HeapObject(program.shapes().getFirst(), List.of(), List.of(8L))),
            Map.of("main", Value.Reference.strong(id)),
            Map.of(),
            Map.of(),
            Map.of("q", new HeapQueue(1, Set.of())));
    var result =
        HistoryOracle.checkFailure(
            program,
            trace,
            new ReferenceHistory(1, false, List.of()),
            new QueueHistory(1, false, List.of()),
            List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.problems().toString());
    assertEquals(false, result.invalidWorkload());
  }
}
