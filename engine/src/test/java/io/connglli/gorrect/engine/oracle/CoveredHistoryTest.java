package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoveredHistoryTest {
  @ParameterizedTest
  @ValueSource(longs = {7, 8})
  void sequentialReferenceHistoryComparesCoveredDataWithoutClaimingCompleteness(long payload) {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1;
            object 1 N [weak(2)] [7]; object 2 N [null] [0]; root r = 1;
            program { read-root holder N r; checkpoint; }
            """);
    var shape = program.shapes().getFirst();
    var wrapper = new WrapperId(0, 0, 1, 0);
    var graph =
        new Graph(
            Map.of(
                ObjectId.initial(1),
                new HeapObject(
                    shape,
                    List.of(new Value.Reference(Value.ReferenceKind.WEAK, null, wrapper)),
                    List.of(payload)),
                ObjectId.initial(2),
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of(0L))),
            Map.of("r", Value.Reference.strong(ObjectId.initial(1))),
            Map.of());
    var finalObjects =
        Map.of(
            ObjectId.initial(1),
            new HeapObject(
                shape,
                List.of(new Value.Reference(Value.ReferenceKind.WEAK, null, wrapper)),
                List.of(7L)),
            ObjectId.initial(2),
            new HeapObject(shape, List.of(Value.Reference.NULL), List.of(0L)));
    var finalGraph = new Graph(finalObjects, graph.roots(), Map.of());
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0)));
    var result =
        HistoryOracle.check(
            program,
            trace,
            new ReferenceHistory(1, true, List.of()),
            List.of(
                new HistoryOracle.Observation(
                    Optional.of(graph), List.of("missing holder origin"), false),
                new HistoryOracle.Observation(Optional.of(finalGraph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(
        payload == 7 ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.VIOLATED,
        result.verdict(),
        result.problems().toString());
  }

  @ParameterizedTest
  @ValueSource(longs = {7, 8})
  void concurrentHistoryAlsoKeepsCoveredContradictionsAndIncompleteness(long payload) {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [7]; root r = 1;
            program { read-volatile-root held N r; checkpoint; }
            """);
    var id = ObjectId.initial(1);
    var shape = program.shapes().getFirst();
    var roots = Map.of("r", Value.Reference.strong(id));
    var observed =
        new Graph(Map.of(id, new HeapObject(shape, List.of(), List.of(payload))), roots, Map.of());
    var finalGraph =
        new Graph(Map.of(id, new HeapObject(shape, List.of(), List.of(7L))), roots, Map.of());
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.REFERENCE_READ, 1),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0)));
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    1,
                    ReferenceHistory.Kind.STRONG_READ,
                    0,
                    null,
                    new ReferenceHistory.Target(0, 0, 1))));
    var result =
        ConcurrentHistory.check(
            program,
            trace,
            history,
            List.of(
                new HistoryOracle.Observation(
                    Optional.of(observed), List.of("missing held origin"), false),
                new HistoryOracle.Observation(Optional.of(finalGraph), List.of())),
            HistoryLimits.DEFAULT);
    assertEquals(
        payload == 7 ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.VIOLATED,
        result.verdict(),
        result.problems().toString());
  }

  @Test
  void hiddenStrongHandleProtectsAnObservedWeakReferentDuringPartialComparison() {
    var owner = ObjectId.initial(1);
    var target = ObjectId.initial(2);
    var shape = new Shape("N", Shape.Kind.OBJECT, 1, 0);
    var weak = new Value.Reference(Value.ReferenceKind.WEAK, target, new WrapperId(0, 0, 1, 0));
    var graph =
        new Graph(
            Map.of(
                owner,
                new HeapObject(shape, List.of(weak), List.of()),
                target,
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of())),
            Map.of("r", Value.Reference.strong(owner)),
            Map.of(7, Value.Reference.strong(target)));
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    frontier.register(weak);
    frontier.expand(graph);
    var covered = new Graph(graph.objects(), graph.roots(), Map.of());
    assertTrue(frontier.matchCovered(graph, covered).isEmpty());
    assertTrue(frontier.acquire(weak.wrapper(), target));
    assertEquals(1, graph.handles().size());

    var impossible = new ReferenceFrontier(HistoryLimits.DEFAULT);
    impossible.register(weak);
    impossible.expand(graph);
    var cleared =
        new Graph(
            Map.of(
                owner,
                new HeapObject(
                    shape,
                    List.of(new Value.Reference(Value.ReferenceKind.WEAK, null, weak.wrapper())),
                    List.of()),
                target,
                graph.objects().get(target)),
            graph.roots(),
            Map.of());
    assertFalse(impossible.matchCovered(graph, cleared).isEmpty());
    assertEquals(0, impossible.size());
  }

  @Test
  void unknownCoveredObligationCannotDisappearDuringFiltering() {
    var id = ObjectId.initial(1);
    var shape = new Shape("N", Shape.Kind.OBJECT, 0, 0);
    var graph =
        new Graph(
            Map.of(id, new HeapObject(shape, List.of(), List.of())),
            Map.of("r", Value.Reference.strong(id)),
            Map.of(7, Value.Reference.strong(id)));
    for (var observed :
        List.of(
            new Graph(graph.objects(), Map.of("unknown", Value.Reference.strong(id)), Map.of()),
            new Graph(graph.objects(), graph.roots(), Map.of(999, Value.Reference.strong(id))))) {
      var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
      frontier.expand(graph);
      assertFalse(frontier.matchCovered(graph, observed).isEmpty());
    }
  }
}
