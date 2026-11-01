package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonitorModelTest {
  private static final String HEADER =
      "gir 1; shape N object 0 1; object 1 N [] [7]; root main=1; root saved=null; root nil=null; ";
  private static final String NESTED =
      HEADER
          + """
          program { read-root owner N main;
            outer: monitor owner {
              allocate child N; write-root saved child;
              inner: monitor owner { allocate leaf N; write-data owner 0 99; }
            }
          }
          """;

  @Test
  void nestedReentrantDistinctAndAliasedBlocksHaveExactBoundaryOrderAndCosts() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { read-root owner N main; allocate other N; read-root alias N main;
                  outer: monitor owner {
                    nested: monitor alias { distinct: monitor other {} }
                    write-root main null;
                    read-root local N saved; release local;
                  }
                  repeat 2 { monitor owner {} }
                  release alias; release owner; release other;
                }
                """);
    var result = Oracle.interpret(program);
    assertEquals(
        List.of("4:0", "5:0", "6:0", "6:1", "5:1", "4:1", "11:0", "11:1", "11:0", "11:1"),
        result.events().stream()
            .filter(e -> List.of(4, 5, 6, 11).contains(e.operation()))
            .map(e -> e.operation() + ":" + e.value())
            .toList());
    assertEquals(HistoryPlan.plan(program, OracleLimits.DEFAULT).steps(), result.steps());
    assertEquals(22, result.steps());
    assertEquals(0, result.finalGraph().objects().size());
  }

  @Test
  void nullAndUnavailableEntryRejectBeforeAnyModelEffectEvenWithoutCapacity() {
    var machine =
        new GraphMachine(GirParser.parse(HEADER + "program {}"), new OracleLimits(1, 10, 10, 100));
    machine.apply(new Instruction.ReadRoot(1, "empty", "N", "nil"));
    for (String owner : List.of("empty", "missing")) {
      var key = machine.key();
      var events = machine.events();
      assertThrows(
          GirException.class,
          () -> machine.enterMonitor(new Instruction.Monitor(2, owner, List.of())));
      assertEquals(key, machine.key());
      assertEquals(events, machine.events());
    }
    assertThrows(
        GirException.class,
        () ->
            Oracle.interpret(
                GirParser.parse(HEADER + "program { read-root empty N nil; monitor empty {} }")));
    // A statically valid but unexecuted null receiver is legal.
    Oracle.interpret(
        GirParser.parse(
            HEADER + "program { read-root empty N nil; repeat 0 { monitor empty {} } }"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 5, 7, 8})
  void exhaustedEntryBodyAndExitKeepTheSuccessfulPrefixAndUnwindEveryChild(int budget) {
    var program = GirParser.parse(NESTED);
    var machine = new GraphMachine(program, new OracleLimits(budget, 10, 10, 100));
    assertThrows(OracleLimitException.class, () -> Oracle.execute(program.body(), machine));
    var full = Oracle.interpret(program);
    assertEquals(full.events().subList(0, budget), machine.events());
    assertEquals(budget, machine.steps());
    assertEquals(List.of(1), machine.graph().handles().keySet().stream().toList());
    assertEquals(
        budget >= 7 ? List.of(99L) : List.of(7L),
        machine.graph().objects().get(ObjectId.initial(1)).data());
    // The original scope remains current after recursive cleanup, including failed exits.
    machine.leaveScope();
    assertEquals(0, machine.graph().handles().size());
  }

  @Test
  void rejectedEntryCapacityDoesNotOpenAScopeOrAdvanceAnyCounter() {
    var program = GirParser.parse(HEADER + "program { read-root owner N main; }");
    var machine = new GraphMachine(program, new OracleLimits(1, 10, 10, 100));
    machine.apply((Instruction.Operation) program.body().getFirst());
    var key = machine.key();
    var events = machine.events();
    assertThrows(
        OracleLimitException.class,
        () -> machine.enterMonitor(new Instruction.Monitor(2, "owner", List.of())));
    assertEquals(key, machine.key());
    assertEquals(events, machine.events());
  }

  @Test
  void abruptBodyFailurePreservesWritesAndOmitsBothNormalExits() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { read-root owner N main; monitor owner {
                  allocate child N; write-root saved child;
                  monitor owner { read-root empty N nil; write-data owner 0 42; write-data empty 0 9; }
                } }
                """);
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    assertThrows(GirException.class, () -> Oracle.execute(program.body(), machine));
    assertEquals(7, machine.steps());
    assertTrue(
        machine.events().stream()
            .noneMatch(e -> (e.operation() == 2 || e.operation() == 5) && e.value() == 1));
    assertEquals(List.of(42L), machine.graph().objects().get(ObjectId.initial(1)).data());
    assertEquals(1, machine.graph().handles().size());
    assertEquals(new ObjectId(0, 3, 1), machine.graph().roots().get("saved").target());
  }

  @Test
  void missingReorderedAndMalformedBoundaryEvidenceCannotSatisfy() {
    var result = Oracle.interpret(GirParser.parse(NESTED));
    var valid = new ExecutionTrace(1, true, result.events());
    assertEquals(ExactVerifier.Verdict.SATISFIED, ExactTraceVerifier.verify(valid, result));
    var events = new ArrayList<>(valid.events());
    events.removeLast();
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ExactTraceVerifier.verify(new ExecutionTrace(1, false, events), result));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, true, events), result));
    events = new ArrayList<>(valid.events());
    Collections.swap(events, 1, 7);
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, false, events), result));
    events = new ArrayList<>(valid.events());
    events.set(1, new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 2));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, true, events), result));
  }

  @Test
  void directHistoryChecksAndAuditsRejectBeforeMissingEvidence() {
    var program = GirParser.parse(NESTED);
    var trace = new ExecutionTrace(1, false, List.of());
    var refs = new ReferenceHistory(1, false, List.of());
    assertThrows(
        UnsupportedHistoryException.class,
        () -> HistoryOracle.check(program, trace, refs, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> HistoryOracle.checkFailure(program, trace, refs, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.check(program, trace, refs, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }
}
