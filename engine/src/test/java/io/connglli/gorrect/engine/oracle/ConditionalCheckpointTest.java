package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent small traces distinguish actual visits from all-taken storage bounds. */
class ConditionalCheckpointTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void checksTakenAndSkippedAcquisitionsAndMissingCoverage(boolean present) {
    var program =
        GirParser.parse(
            "gir 1; shape N object 1 0; object 1 N [weak("
                + (present ? "1" : "null")
                + ")] []; root r = 1; program { scope { read-root owner N r; acquire held N owner 0"
                + " { checkpoint; } } }");
    var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
    assertEquals(
        List.of(new CheckpointPlan.Visit(4, 1), new CheckpointPlan.Visit(0, 1)), plan.visits());
    assertEquals(java.util.Set.of(2, 3), plan.declarations(4));
    var events = new ArrayList<ExecutionTrace.Event>();
    events.add(event(1, ExecutionTrace.Kind.OPERATION, 0));
    events.add(event(2, ExecutionTrace.Kind.OPERATION, 0));
    events.add(event(3, ExecutionTrace.Kind.ACQUIRE, present ? 1 : 0));
    if (present) {
      events.add(event(4, ExecutionTrace.Kind.OPERATION, 0));
      events.add(event(3, ExecutionTrace.Kind.ACQUIRE_END, 0));
    }
    var trace = new ExecutionTrace(1, true, events);
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
                    present ? new ReferenceHistory.Target(0, 0, 1) : null)));
    var id = ObjectId.initial(1);
    var reference = Value.Reference.strong(id);
    var wrapper =
        new Value.Reference(
            Value.ReferenceKind.WEAK, present ? id : null, new WrapperId(0, 0, 1, 0));
    var objects =
        Map.of(id, new HeapObject(program.shapes().getFirst(), List.of(wrapper), List.of()));
    var observations = new ArrayList<HistoryOracle.Observation>();
    if (present)
      observations.add(
          new HistoryOracle.Observation(
              Optional.of(
                  new Graph(objects, Map.of("r", reference), Map.of(2, reference, 3, reference))),
              List.of()));
    observations.add(
        new HistoryOracle.Observation(
            Optional.of(new Graph(objects, Map.of("r", reference), Map.of())), List.of()));
    assertEquals(present ? 2 : 1, CheckpointPlan.recorded(program, trace).size());
    var result = HistoryOracle.check(program, trace, history, observations, HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict(), result.toString());
    assertEquals(present ? 1 : 0, result.execution().checkpoints().size());
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        HistoryOracle.check(program, trace, history, List.of(), HistoryLimits.DEFAULT).verdict());
    if (present) {
      events.remove(3);
      var omitted = new ExecutionTrace(1, true, events);
      assertEquals(
          ExactVerifier.Verdict.VIOLATED,
          HistoryOracle.check(
                  program, omitted, history, observations.subList(1, 2), HistoryLimits.DEFAULT)
              .verdict());
    }
  }

  @Test
  void boundsPerSiteVisitsAndExcludesZeroIterationSites() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0; object 1 N [weak(null)] []; root r = 1;
            program { scope { read-root owner N r;
              repeat 3 { acquire held N owner 0 { checkpoint; } }
              repeat 0 { checkpoint; }
            } }
            """);
    var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
    int site = plan.visits().getFirst().operation();
    assertTrue(plan.permits(site, 1));
    assertTrue(plan.permits(site, 3));
    assertFalse(plan.permits(site, 4));
    assertFalse(plan.permits(site, 0));
    assertFalse(plan.permits(99, 1));
    assertTrue(plan.permits(0, 1));
    assertFalse(plan.permits(0, 2));
  }

  private static ExecutionTrace.Event event(int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value);
  }
}
