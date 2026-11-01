package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticQueueHistoryTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "valid",
        "constructor-target",
        "constructor-queue",
        "scalar-linkage",
        "queue-result"
      })
  void automaticAndApiTransitionsUseActualImmutableConstructionAndPositionedResults(String damage) {
    var program =
        GirParser.parse(
            """
            gir 1; shape W weak; queue q W; root permanent = null;
            program { allocate-ref target W null; allocate-ref r W target q;
              write-root permanent r; release target; collect; enqueue-ref inserted r;
              release r; poll-queue delivered q; release delivered; }
            """);
    var target = new ReferenceHistory.Identity(0, 1, 1, -1);
    var wrapper = new ReferenceHistory.Identity(0, 2, 1, -1);
    var trace = new ArrayList<ExecutionTrace.Event>();
    for (int operation = 1; operation <= 9; operation++)
      trace.add(
          new ExecutionTrace.Event(
              operation,
              operation == 6
                  ? ExecutionTrace.Kind.SCALAR_READ
                  : operation == 8 ? ExecutionTrace.Kind.QUEUE_POLL : ExecutionTrace.Kind.OPERATION,
              operation == 8 ? 1 : 0));
    var references =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(1, ReferenceHistory.Kind.CREATE, 2, target, null),
                new ReferenceHistory.Event(
                    2,
                    ReferenceHistory.Kind.CREATE,
                    2,
                    wrapper,
                    damage.equals("constructor-target")
                        ? null
                        : new ReferenceHistory.Target(0, 1, 1, 1, -1))));
    var queues =
        new QueueHistory(
            1,
            true,
            List.of(
                new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.CREATE, 0, 0, target),
                new QueueHistory.Event(
                    0,
                    2,
                    2,
                    QueueHistory.Kind.CREATE,
                    damage.equals("constructor-queue") ? 0 : 1,
                    0,
                    wrapper),
                new QueueHistory.Event(
                    0,
                    damage.equals("scalar-linkage") ? 5 : 6,
                    6,
                    QueueHistory.Kind.ENQUEUE,
                    1,
                    damage.equals("queue-result") ? 1 : 0,
                    wrapper),
                new QueueHistory.Event(0, 8, 8, QueueHistory.Kind.POLL, 1, 1, wrapper)));
    var id = new WrapperId(0, 2, 1, -1);
    var graph =
        new Graph(
            Map.of(),
            Map.of("permanent", Value.Reference.toWrapper(id)),
            Map.of(),
            Map.of(id, new HeapReference(program.shapes().getFirst(), null)),
            Map.of("q", new HeapQueue(1, Set.of())));
    var result =
        HistoryOracle.check(
            program,
            new ExecutionTrace(1, true, trace),
            references,
            queues,
            List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(
        damage.equals("valid") ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
        result.verdict(),
        result.problems().toString());
  }
}
