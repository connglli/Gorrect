package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerCheckpointTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "parallel { worker a { checkpoint; } worker b {} }",
        "parallel { worker a { checkpoint; barrier; } worker b { barrier; checkpoint; } }",
        "parallel { worker a { repeat 0 { checkpoint; } } }",
        "parallel { worker a { scope { checkpoint; } } }",
        "read-root n N r; if-present n { parallel { worker a { checkpoint; } } }",
        "read-root n N r; acquire held N n 0 { parallel { worker a { checkpoint; } } }"
      })
  void rejectsUnmatchedAndNestedCuts(String body) {
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse("gir 1; shape N object 1 0; root r = null; program { " + body + " }"));
  }

  @Test
  void mergesAllParticipantsAndCountsGlobalSiteVisitsInTheBoundedPlan() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 0;
            program { outer: allocate caller N; repeat 2 { group: parallel {
              worker a { left: allocate local N; barrier; first: checkpoint; release local; checkpoint; }
              worker b { right: allocate local N; barrier; peer: checkpoint; release local; checkpoint; }
            } } }
            """);
    var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
    int first = id(program.labels(), "first");
    assertTrue(HistoryProgram.usesConcurrentHistory(program));
    assertEquals(
        Set.of(
            id(program.labels(), "outer"),
            id(program.labels(), "left"),
            id(program.labels(), "right")),
        plan.declarations(first));
    assertEquals(
        List.of(1L, 1L, 2L, 2L, 1L),
        plan.visits().stream().map(CheckpointPlan.Visit::occurrence).toList());
  }

  @Test
  void capturesEverySuspendedContextOnlyOnceBeforeAnyWorkerResumes() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 0;
            program { allocate caller N; parallel {
              worker a { allocate local N; checkpoint; release local; }
              worker b { allocate local N; checkpoint; release local; }
            } release caller; }
            """);
    // These events and graph obligations are authored independently of the planner/emitter.
    var events = new ArrayList<ExecutionTrace.Event>();
    events.add(new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0));
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0));
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.JOIN, 1));
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.JOIN, 2));
    events.add(new ExecutionTrace.Event(9, ExecutionTrace.Kind.OPERATION, 0));
    worker(events, 1, 3, 4, 5);
    worker(events, 2, 6, 7, 8);
    var trace = new ExecutionTrace(1, true, events);
    var shape = new Shape("N", Shape.Kind.OBJECT, 0, 0);
    var caller = new ObjectId(0, 1, 1);
    var left = new ObjectId(1, 3, 1);
    var right = new ObjectId(2, 6, 1);
    var object = new HeapObject(shape, List.of(), List.of());
    var atCut =
        new Graph(
            Map.of(caller, object, left, object, right, object),
            Map.of(),
            Map.of(
                1,
                Value.Reference.strong(caller),
                3,
                Value.Reference.strong(left),
                6,
                Value.Reference.strong(right)));
    var empty = new Graph(Map.of(), Map.of(), Map.of());
    var result = check(program, trace, atCut, empty);
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict(), result.problems().toString());
    assertEquals(1, result.execution().checkpoints().size());
    assertEquals(
        Set.of(1, 3, 6), result.execution().checkpoints().getFirst().graph().handles().keySet());
    assertEquals(
        List.of(new CheckpointPlan.Visit(4, 1), new CheckpointPlan.Visit(0, 1)),
        CheckpointPlan.recorded(program, trace));
    assertEquals(
        Map.of(
            0,
            new CheckpointPlan.Visit(2, 1),
            1,
            new CheckpointPlan.Visit(4, 1),
            2,
            new CheckpointPlan.Visit(7, 1)),
        CheckpointPlan.recordedCuts(program, trace)
            .get(new CheckpointPlan.Visit(4, 1))
            .participants());
    var wrong =
        new Graph(
            atCut.objects(),
            atCut.roots(),
            Map.of(
                1,
                Value.Reference.strong(caller),
                3,
                Value.Reference.strong(right),
                6,
                Value.Reference.strong(left)));
    assertEquals(ExactVerifier.Verdict.VIOLATED, check(program, trace, wrong, empty).verdict());
    var damaged =
        events.stream()
            .filter(event -> event.worker() != 2 || event.kind() != ExecutionTrace.Kind.PHASE_START)
            .toList();
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        check(program, new ExecutionTrace(1, true, damaged), atCut, empty).verdict());
  }

  @Test
  void ordersAccessesAcrossTheCutWithoutHidingSamePhaseRaces() {
    String prefix =
        "gir 1; shape N object 0 1; object 1 N [] [0]; root r = 1; program { parallel { ";
    String writer = "worker a { read-root n N r; write-data n 0 7; checkpoint; } ";
    var ordered =
        GirParser.parse(
            prefix + writer + "worker b { checkpoint; read-root n N r; read-data value n 0; } } }");
    assertDoesNotThrow(() -> ConcurrentHistory.audit(ordered, HistoryLimits.DEFAULT));
    var raced =
        GirParser.parse(
            prefix + writer + "worker b { read-root n N r; read-data value n 0; checkpoint; } } }");
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(raced, HistoryLimits.DEFAULT));
  }

  @Test
  void auditsDynamicValidityOnEveryScheduleAfterTheCut() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [0]; root r = 1;
            program { parallel {
              worker producer { checkpoint; write-volatile-root r null; }
              worker consumer { checkpoint; read-volatile-root n N r; read-data value n 0; }
            } }
            """);
    assertThrows(GirException.class, () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  private static HistoryOracle.Result check(
      GirProgram program, ExecutionTrace trace, Graph cut, Graph end) {
    return ConcurrentHistory.check(
        program,
        trace,
        new ReferenceHistory(1, true, List.of()),
        List.of(
            new HistoryOracle.Observation(Optional.of(cut), List.of()),
            new HistoryOracle.Observation(Optional.of(end), List.of())),
        HistoryLimits.DEFAULT);
  }

  private static void worker(
      List<ExecutionTrace.Event> events, int worker, int allocation, int point, int release) {
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.WORKER_START, 0, worker, 2, 1, 0));
    events.add(
        new ExecutionTrace.Event(allocation, ExecutionTrace.Kind.OPERATION, 0, worker, 2, 1, 0));
    events.add(new ExecutionTrace.Event(point, ExecutionTrace.Kind.OPERATION, 0, worker, 2, 1, 0));
    events.add(
        new ExecutionTrace.Event(point, ExecutionTrace.Kind.PHASE_START, 0, worker, 2, 1, 1));
    events.add(
        new ExecutionTrace.Event(release, ExecutionTrace.Kind.OPERATION, 0, worker, 2, 1, 1));
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.WORKER_END, 0, worker, 2, 1, 1));
  }

  private static int id(Map<Integer, String> labels, String name) {
    return labels.entrySet().stream()
        .filter(entry -> entry.getValue().equals(name))
        .findFirst()
        .orElseThrow()
        .getKey();
  }
}
