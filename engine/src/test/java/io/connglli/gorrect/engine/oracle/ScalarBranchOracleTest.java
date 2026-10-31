package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.engine.observe.NaturalLiveness;
import io.connglli.gorrect.engine.observe.ObservationCapabilities;
import io.connglli.gorrect.engine.observe.ObservationProfile;
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

/**
 * Hand-specified graphs and traces check numeric choices independently of production evaluation.
 */
class ScalarBranchOracleTest {
  @ParameterizedTest
  @ValueSource(longs = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE})
  void selectsExactlyOneArmFromTheCopiedLongValue(long condition) {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 1; object 1 N [] ["
                + condition
                + "]; root main = 1; program { read-root owner N main; read-data value owner 0;"
                + " if-nonzero value { write-data owner 0 11; } else { write-data owner 0 22; }"
                + " release owner; checkpoint; }");
    var result = Oracle.interpret(program);
    var expected =
        new Graph(
            Map.of(
                ObjectId.initial(1),
                new HeapObject(
                    program.shapes().getFirst(), List.of(), List.of(condition == 0 ? 22L : 11L))),
            Map.of("main", ref(1)),
            Map.of());
    assertEquals(expected, result.finalGraph());
    assertEquals(expected, result.checkpoints().getFirst().graph());
    assertEquals(
        List.of(1, 2, 3, condition == 0 ? 5 : 4, 6, 7),
        result.events().stream().map(ExecutionTrace.Event::operation).toList());
    assertEquals(
        event(3, ExecutionTrace.Kind.BRANCH, condition == 0 ? 0 : 1), result.events().get(2));
  }

  @Test
  void plansBothScopesWithoutTurningTheirPlaceholdersIntoExecutedVisits() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 0; program {
              repeat 2 { if-nonzero 0 {
                allocate held N; left: checkpoint;
              } else {
                allocate held N; right: checkpoint;
              } }
            }
            """);
    var execution = Oracle.interpret(program);
    var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
    assertEquals(2, execution.checkpoints().size());
    assertTrue(execution.checkpoints().stream().allMatch(point -> point.operation() == 6));
    assertEquals(5, plan.visits().size());
    assertTrue(plan.permits(4, 2));
    assertTrue(plan.permits(6, 2));
    assertFalse(plan.permits(6, 3));
    assertEquals(Set.of(3), plan.declarations(4));
    assertEquals(Set.of(5), plan.declarations(6));
    assertEquals(
        List.of(
            new CheckpointPlan.Visit(6, 1),
            new CheckpointPlan.Visit(6, 2),
            new CheckpointPlan.Visit(0, 1)),
        CheckpointPlan.recorded(program, new ExecutionTrace(1, true, execution.events())));
    var limited = new OracleLimits(100, 3, 100, 100);
    assertDoesNotThrow(() -> Oracle.interpret(program, limited));
    assertThrows(OracleLimitException.class, () -> HistoryPlan.plan(program, limited));
  }

  @Test
  void conditionRejectionAndBranchBudgetFailurePreserveTheModel() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 1; object 1 N [] [7]; root r = 1;"
                + " program { read-root owner N r; read-data value owner 0; }");
    var machine = new GraphMachine(program, new OracleLimits(2, 10, 10, 100));
    machine.apply((Instruction.Operation) program.body().get(0));
    machine.apply((Instruction.Operation) program.body().get(1));
    Object before = machine.key();
    assertThrows(GirException.class, () -> machine.nonzero(new Operand.Name("owner")));
    assertEquals(before, machine.key());
    assertTrue(machine.nonzero(new Operand.Name("value")));
    assertThrows(
        OracleLimitException.class, () -> machine.marker(3, ExecutionTrace.Kind.BRANCH, 1));
    assertEquals(before, machine.key());
  }

  @Test
  void checksBranchesOnReferenceStateAndKeepsRefutationsBesideMissingCaptures() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; object 1 N [weak(2)] [0]; object 2 N [null] [7];
            root owner = 1; root guard = 2;
            program { read-root owner N owner; is-cleared before owner 0;
              if-nonzero before { write-data owner 0 99; } else { write-data owner 0 22; }
              clear-ref owner 0; is-cleared after owner 0;
              if-nonzero after { write-data owner 0 11; } else { write-data owner 0 88; }
              release owner; checkpoint;
            }
            """);
    var events =
        new ArrayList<>(
            List.of(
                event(1, ExecutionTrace.Kind.OPERATION, 0),
                event(2, ExecutionTrace.Kind.REFERENCE_STATE, 0),
                event(3, ExecutionTrace.Kind.BRANCH, 0),
                event(5, ExecutionTrace.Kind.OPERATION, 0),
                event(6, ExecutionTrace.Kind.OPERATION, 0),
                event(7, ExecutionTrace.Kind.REFERENCE_STATE, 1),
                event(8, ExecutionTrace.Kind.BRANCH, 1),
                event(9, ExecutionTrace.Kind.OPERATION, 0),
                event(11, ExecutionTrace.Kind.OPERATION, 0),
                event(12, ExecutionTrace.Kind.OPERATION, 0)));
    var wrapper = new ReferenceHistory.Identity(0, 0, 1, 0);
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(2, ReferenceHistory.Kind.STATE, 2, wrapper, null),
                new ReferenceHistory.Event(5, ReferenceHistory.Kind.CLEAR, 2, wrapper, null),
                new ReferenceHistory.Event(6, ReferenceHistory.Kind.STATE, 2, wrapper, null)));
    var shape = program.shapes().getFirst();
    var graph =
        new Graph(
            Map.of(
                ObjectId.initial(1),
                new HeapObject(
                    shape,
                    List.of(
                        new Value.Reference(
                            Value.ReferenceKind.WEAK, null, new WrapperId(0, 0, 1, 0))),
                    List.of(11L)),
                ObjectId.initial(2),
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of(7L))),
            Map.of("owner", ref(1), "guard", ref(2)),
            Map.of());
    var observations = List.of(observed(graph), observed(graph));
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        HistoryOracle.check(
                program,
                new ExecutionTrace(1, true, events),
                history,
                observations,
                HistoryLimits.DEFAULT)
            .verdict());
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        HistoryOracle.check(
                program,
                new ExecutionTrace(1, true, events),
                history,
                List.of(),
                HistoryLimits.DEFAULT)
            .verdict());
    events.set(6, event(8, ExecutionTrace.Kind.BRANCH, 0));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        HistoryOracle.check(
                program,
                new ExecutionTrace(1, true, events),
                history,
                List.of(),
                HistoryLimits.DEFAULT)
            .verdict());
  }

  @Test
  void agreesWithIndependentCasOwnershipAndBranchOutcomes() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [0]; object 2 N [] [0];
            root left = 1; root right = 2; root winner = null;
            program { parallel {
              worker a { read-volatile-root n N left; compare-set-root won winner null n;
                if-nonzero won { write-data n 0 11; } else { write-data n 0 22; } }
              worker b { read-volatile-root n N right; compare-set-root won winner null n;
                if-nonzero won { write-data n 0 11; } else { write-data n 0 22; } }
            } }
            """);
    var shape = program.shapes().getFirst();
    var identities =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    1,
                    2,
                    ReferenceHistory.Kind.STRONG_READ,
                    0,
                    null,
                    new ReferenceHistory.Target(0, 0, 1)),
                new ReferenceHistory.Event(
                    2,
                    2,
                    ReferenceHistory.Kind.STRONG_READ,
                    0,
                    null,
                    new ReferenceHistory.Target(0, 0, 2))));
    for (int left = 0; left <= 1; left++)
      for (int right = 0; right <= 1; right++)
        for (int leftBranch = 0; leftBranch <= 1; leftBranch++)
          for (int rightBranch = 0; rightBranch <= 1; rightBranch++)
            for (int winner = 0; winner <= 2; winner++) {
              var events =
                  new ArrayList<>(
                      List.of(
                          event(1, ExecutionTrace.Kind.OPERATION, 0),
                          event(1, ExecutionTrace.Kind.JOIN, 1),
                          event(1, ExecutionTrace.Kind.JOIN, 2)));
              for (int worker = 1; worker <= 2; worker++) {
                int read = worker == 1 ? 2 : 7;
                int won = worker == 1 ? left : right;
                int branch = worker == 1 ? leftBranch : rightBranch;
                events.add(workerEvent(worker, 1, ExecutionTrace.Kind.WORKER_START, 0));
                events.add(workerEvent(worker, read, ExecutionTrace.Kind.REFERENCE_READ, 1));
                events.add(workerEvent(worker, read + 1, ExecutionTrace.Kind.COMPARE_SET, won));
                events.add(workerEvent(worker, read + 2, ExecutionTrace.Kind.BRANCH, branch));
                events.add(
                    workerEvent(
                        worker, read + (branch == 1 ? 3 : 4), ExecutionTrace.Kind.OPERATION, 0));
                events.add(workerEvent(worker, 1, ExecutionTrace.Kind.WORKER_END, 0));
              }
              var graph =
                  new Graph(
                      Map.of(
                          ObjectId.initial(1),
                              new HeapObject(
                                  shape, List.of(), List.of(leftBranch == 1 ? 11L : 22L)),
                          ObjectId.initial(2),
                              new HeapObject(
                                  shape, List.of(), List.of(rightBranch == 1 ? 11L : 22L))),
                      Map.of("left", ref(1), "right", ref(2), "winner", ref(winner)),
                      Map.of());
              boolean allowed =
                  leftBranch == left
                      && rightBranch == right
                      && ((left == 1 && right == 0 && winner == 1)
                          || (left == 0 && right == 1 && winner == 2));
              var result =
                  ConcurrentHistory.check(
                      program,
                      new ExecutionTrace(1, true, events),
                      identities,
                      List.of(observed(graph)),
                      new HistoryLimits(4096, 10_000_000));
              assertEquals(
                  allowed ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
                  result.verdict(),
                  result.toString());
            }
  }

  @Test
  void auditsInvalidAndRacyPathsEvenWhenAnotherCasOutcomeAvoidsThem() {
    String header =
        "gir 1; shape N object 0 1; object 1 N [] [0]; root source = 1; root token = null; root"
            + " empty = null; ";
    var invalid =
        GirParser.parse(
            header
                + """
                program { parallel {
                  worker a { read-root n N source; compare-set-root won token null n;
                    if-nonzero won { read-root absent N empty; write-data absent 0 7; } }
                  worker b { read-root n N source; compare-set-root won token null n; }
                } }
                """);
    assertThrows(GirException.class, () -> ConcurrentHistory.audit(invalid, HistoryLimits.DEFAULT));
    var raced =
        GirParser.parse(
            header
                + """
                program { parallel {
                  worker a { read-root n N source; compare-set-root won token null n;
                    if-nonzero won { write-data n 0 7; } }
                  worker b { read-root n N source; compare-set-root won token null n; read-data observed n 0; }
                } }
                """);
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(raced, HistoryLimits.DEFAULT));
    assertThrows(
        HistoryLimitException.class, () -> ConcurrentHistory.audit(raced, new HistoryLimits(1, 1)));
  }

  @Test
  void requiresAnExactRouteBeforeAnalyzingNaturalConditions() {
    var program = GirParser.parse("gir 1; program { if-nonzero 0 {} }");
    assertTrue(
        ObservationCapabilities.unsupported(program, ObservationProfile.NATURAL_ROOTS).isEmpty());
    assertThrows(UnsupportedHistoryException.class, () -> NaturalLiveness.analyze(program));
    assertEquals(Set.of(), NaturalLiveness.analyze(program, Oracle.interpret(program)).at(0, 1));
  }

  private static HistoryOracle.Observation observed(Graph graph) {
    return new HistoryOracle.Observation(Optional.of(graph), List.of());
  }

  private static Value.Reference ref(int id) {
    return id == 0 ? Value.Reference.NULL : Value.Reference.strong(ObjectId.initial(id));
  }

  private static ExecutionTrace.Event event(int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value);
  }

  private static ExecutionTrace.Event workerEvent(
      int worker, int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value, worker, 1, 1, 0);
  }
}
