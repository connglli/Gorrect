package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Independent traces separate capture storage bounds, branch legality, and graph coverage. */
class ConditionalCheckpointEvidenceTest {
  @Test
  void nestedConditionalBoundsDoNotSupplyRecordedVisits() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0; object 1 N [weak(null)] []; root r = 1;
            program { scope { read-root owner N r;
              repeat 2 { acquire held N owner 0 {
                repeat 3 { if-present held { inside: checkpoint; } }
              } }
              repeat 0 { never: checkpoint; }
            } }
            """);
    var points =
        program
            .instructions()
            .filter(instruction -> instruction instanceof Instruction.Checkpoint)
            .toList();
    int site = points.getFirst().id();
    var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
    assertEquals(7, plan.visits().size());
    assertTrue(plan.permits(site, 6));
    assertFalse(plan.permits(site, 7));
    assertFalse(plan.permits(points.getLast().id(), 1));
    assertEquals(Set.of(2, 4), plan.declarations(site));

    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                event(site, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(site, ExecutionTrace.Kind.OPERATION, 0, 1, 99, 1, 0),
                event(site, ExecutionTrace.Kind.OPERATION, 0)));
    assertEquals(
        List.of(
            new CheckpointPlan.Visit(site, 1),
            new CheckpointPlan.Visit(site, 2),
            new CheckpointPlan.Visit(0, 1)),
        CheckpointPlan.recorded(program, trace));
  }

  @Test
  void aBoundedMarkerCannotLegalizeASkippedAcquisitionBody() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0; object 1 N [weak(null)] []; root r = 1;
            program { scope { read-root owner N r; acquire held N owner 0 { checkpoint; } } }
            """);
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                event(1, ExecutionTrace.Kind.OPERATION, 0),
                event(2, ExecutionTrace.Kind.OPERATION, 0),
                event(3, ExecutionTrace.Kind.ACQUIRE, 0),
                event(4, ExecutionTrace.Kind.OPERATION, 0)));
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    3,
                    ReferenceHistory.Kind.ACQUIRE,
                    2,
                    new ReferenceHistory.Identity(0, 0, 1, 0),
                    null)));
    assertTrue(new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT)).permits(4, 1));
    var result =
        HistoryOracle.check(
            program,
            trace,
            history,
            List.of(missing("invented capture"), missing("final capture")),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
    assertFalse(result.invalidWorkload());
  }

  @Test
  void aBoundedMarkerCannotLegalizeASkippedIfPresentBody() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 0; root empty = null;
            program { scope { read-volatile-root held N empty; if-present held { checkpoint; } } }
            """);
    var events =
        List.of(
            event(1, ExecutionTrace.Kind.OPERATION, 0),
            event(2, ExecutionTrace.Kind.REFERENCE_READ, 0),
            event(3, ExecutionTrace.Kind.BRANCH, 0));
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(2, ReferenceHistory.Kind.STRONG_READ, 0, null, null)));
    var finalCapture =
        new HistoryOracle.Observation(
            Optional.of(new Graph(Map.of(), Map.of("empty", Value.Reference.NULL), Map.of())),
            List.of());
    var skipped =
        ConcurrentHistory.check(
            program,
            new ExecutionTrace(1, true, events),
            history,
            List.of(finalCapture),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.SATISFIED, skipped.verdict(), skipped.toString());

    var invented = new ArrayList<>(events);
    invented.set(2, event(3, ExecutionTrace.Kind.BRANCH, 1));
    invented.add(event(4, ExecutionTrace.Kind.OPERATION, 0));
    assertTrue(new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT)).permits(4, 1));
    var result =
        ConcurrentHistory.check(
            program,
            new ExecutionTrace(1, true, invented),
            history,
            List.of(missing("invented capture"), finalCapture),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
  }

  @Test
  void concurrentMatchingRetainsAnImpossibleReadWhenTheFinalObservationIsMissing() {
    var fixture = conditionalRead();
    var valid =
        ConcurrentHistory.check(
            fixture.program(),
            fixture.trace(),
            fixture.history(),
            fixture.observations(),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.SATISFIED, valid.verdict(), valid.toString());

    var events = new ArrayList<>(fixture.trace().events());
    events.set(events.size() - 1, event(5, ExecutionTrace.Kind.SCALAR_READ, 8));
    var result =
        ConcurrentHistory.check(
            fixture.program(),
            new ExecutionTrace(1, true, events),
            fixture.history(),
            fixture.observations().subList(0, 1),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
  }

  @Test
  void missingOrExtraObservationsCannotProduceACompletePass() {
    var fixture = conditionalRead();
    var extra = new ArrayList<>(fixture.observations());
    extra.add(fixture.observations().getLast());
    for (var observations :
        List.of(
            fixture.observations().subList(0, 1),
            extra,
            List.of(missing("conditional capture gap"), fixture.observations().getLast()))) {
      var result =
          ConcurrentHistory.check(
              fixture.program(),
              fixture.trace(),
              fixture.history(),
              observations,
              HistoryLimits.DEFAULT);
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    }
  }

  @Test
  void failureDiagnosisRequiresEveryPriorCaptureAndConsumesTheObservationPrefix() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; object 1 N [weak(1)] [0]; root r = 1; root empty = null;
            program { scope { read-root owner N r; acquire held N owner 0 {
              checkpoint; read-root absent N empty; write-data absent 0 7;
            } } }
            """);
    var trace =
        new ExecutionTrace(
            1,
            false,
            List.of(
                event(1, ExecutionTrace.Kind.OPERATION, 0),
                event(2, ExecutionTrace.Kind.OPERATION, 0),
                event(3, ExecutionTrace.Kind.ACQUIRE, 1),
                event(4, ExecutionTrace.Kind.OPERATION, 0),
                event(5, ExecutionTrace.Kind.OPERATION, 0)));
    var history =
        new ReferenceHistory(
            1,
            false,
            List.of(
                new ReferenceHistory.Event(
                    3,
                    ReferenceHistory.Kind.ACQUIRE,
                    2,
                    new ReferenceHistory.Identity(0, 0, 1, 0),
                    new ReferenceHistory.Target(0, 0, 1))));
    var id = ObjectId.initial(1);
    var reference = Value.Reference.strong(id);
    var graph =
        new Graph(
            Map.of(
                id,
                new HeapObject(
                    program.shapes().getFirst(),
                    List.of(
                        new Value.Reference(
                            Value.ReferenceKind.WEAK, id, new WrapperId(0, 0, 1, 0))),
                    List.of(0L))),
            Map.of("r", reference, "empty", Value.Reference.NULL),
            Map.of(2, reference, 3, reference));
    var complete = new HistoryOracle.Observation(Optional.of(graph), List.of());
    var valid =
        HistoryOracle.checkFailure(
            program, trace, history, List.of(complete), HistoryLimits.DEFAULT);
    assertTrue(valid.invalidWorkload(), valid.toString());

    var checks = new ArrayList<Executable>();
    for (var observations :
        List.of(
            List.<HistoryOracle.Observation>of(),
            List.of(missing("incomplete bindings")),
            List.of(complete, complete))) {
      checks.add(
          () -> {
            var result =
                HistoryOracle.checkFailure(
                    program, trace, history, observations, HistoryLimits.DEFAULT);
            assertFalse(result.invalidWorkload(), result.toString());
            assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
          });
    }
    assertAll(checks);
  }

  private static Fixture conditionalRead() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [7]; root r = 1;
            program { scope { read-volatile-root held N r;
              if-present held { checkpoint; read-data observed held 0; }
            } }
            """);
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                event(1, ExecutionTrace.Kind.OPERATION, 0),
                event(2, ExecutionTrace.Kind.REFERENCE_READ, 1),
                event(3, ExecutionTrace.Kind.BRANCH, 1),
                event(4, ExecutionTrace.Kind.OPERATION, 0),
                event(5, ExecutionTrace.Kind.SCALAR_READ, 7)));
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    2,
                    ReferenceHistory.Kind.STRONG_READ,
                    0,
                    null,
                    new ReferenceHistory.Target(0, 0, 1))));
    var id = ObjectId.initial(1);
    var reference = Value.Reference.strong(id);
    var objects = Map.of(id, new HeapObject(program.shapes().getFirst(), List.of(), List.of(7L)));
    return new Fixture(
        program,
        trace,
        history,
        List.of(
            new HistoryOracle.Observation(
                Optional.of(new Graph(objects, Map.of("r", reference), Map.of(2, reference))),
                List.of()),
            new HistoryOracle.Observation(
                Optional.of(new Graph(objects, Map.of("r", reference), Map.of())), List.of())));
  }

  private record Fixture(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<HistoryOracle.Observation> observations) {}

  private static HistoryOracle.Observation missing(String problem) {
    return new HistoryOracle.Observation(Optional.empty(), List.of(problem));
  }

  private static ExecutionTrace.Event event(int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value);
  }
}
