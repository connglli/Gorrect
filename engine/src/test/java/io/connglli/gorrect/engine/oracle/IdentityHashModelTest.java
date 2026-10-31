package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityHashModelTest {
  private static final String HEADER =
      "gir 1; shape N object 0 1; object 1 N [] [7]; root main = 1; ";

  @Test
  void modelSubjectsFollowAliasesAndFreshAllocationOccurrences() {
    var oracle =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + """
                    program {
                      read-root n N main; identity-hash n;
                      read-root alias N main; write-data alias 0 9; collect; identity-hash alias;
                      identity-hash null;
                      repeat 2 { allocate fresh N; identity-hash fresh; }
                    }
                    """));
    var original = HeapId.object(ObjectId.initial(1));
    assertEquals(
        List.of(
            new OracleResult.IdentityHash(1, original),
            new OracleResult.IdentityHash(5, original),
            new OracleResult.IdentityHash(6, null),
            new OracleResult.IdentityHash(10, HeapId.object(new ObjectId(0, 9, 1))),
            new OracleResult.IdentityHash(13, HeapId.object(new ObjectId(0, 9, 2)))),
        oracle.identityHashes());
    assertEquals(List.of(9L), oracle.finalGraph().objects().get(ObjectId.initial(1)).data());
    assertEquals(1, oracle.finalGraph().objects().size());
    assertEquals(
        oracle.steps(),
        HistoryPlan.plan(
                GirParser.parse(
                    HEADER
                        + """
                        program {
                          read-root n N main; identity-hash n;
                          read-root alias N main; write-data alias 0 9; collect; identity-hash alias;
                          identity-hash null;
                          repeat 2 { allocate fresh N; identity-hash fresh; }
                        }
                        """),
                OracleLimits.DEFAULT)
            .steps());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void acceptsOpaqueTokensIncludingZeroAndCollisions(long hash) {
    var oracle =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + "program { read-root n N main;"
                    + " identity-hash n; allocate other N; identity-hash other; identity-hash n;"
                    + " identity-hash null; }"));
    assertEquals(ExactVerifier.Verdict.SATISFIED, verify(oracle, true, hash, hash, hash, 0));
  }

  @Test
  void clonesAndRepeatedSitesDoNotShareTokenConstraints() {
    var oracle =
        Oracle.interpret(
            GirParser.parse(
                "gir 1; shape A data 0; object 1 A [] []; root arr = 1; program { read-root a A"
                    + " arr; identity-hash a; repeat 2 { clone c a; identity-hash c; identity-hash"
                    + " c; } identity-hash a; }"));
    assertEquals(ExactVerifier.Verdict.SATISFIED, verify(oracle, true, 0, 3, 3, -5, -5, 0));
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(oracle, true, 0, 3, 4, -5, -5, 0));
  }

  @ParameterizedTest
  @ValueSource(longs = {2147483648L, -2147483649L, Long.MIN_VALUE, Long.MAX_VALUE})
  void rejectsResultsOutsideTheSignedIntRange(long hash) {
    var oracle = Oracle.interpret(GirParser.parse(HEADER + "program { identity-hash null; }"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(oracle, true, hash));
  }

  @Test
  void nullAndStabilityContradictionsSurviveAnIncompleteSuffix() {
    var oracle =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + "program { read-root n N main;"
                    + " identity-hash n; identity-hash n; identity-hash null; checkpoint; }"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(oracle, false, 0, 7, 0));
    assertEquals(ExactVerifier.Verdict.VIOLATED, verify(oracle, false, 3, 3, -1));
    var events = hashEvents(oracle, 3, 3, 0);
    events.removeLast();
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ExactTraceVerifier.verify(new ExecutionTrace(1, false, events), oracle));
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ExactTraceVerifier.verify(new ExecutionTrace(1, false, List.of()), oracle));
  }

  @Test
  void validatesEventIdentityAndOrdinaryScalarsBeforeAttributingTokens() {
    var oracle =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + "program { read-root n N main; identity-hash n; read-data value n 0;"
                    + " if-nonzero value { identity-hash n; } }"));
    var valid = hashEvents(oracle, 42, 42);
    for (int index = 0; index < valid.size(); index++) {
      var corrupt = new ArrayList<>(valid);
      var event = valid.get(index);
      corrupt.set(
          index, new ExecutionTrace.Event(event.operation() + 1, event.kind(), event.value()));
      assertEquals(
          ExactVerifier.Verdict.VIOLATED,
          ExactTraceVerifier.verify(new ExecutionTrace(1, false, corrupt), oracle));
    }
    for (int index : List.of(2, 3)) {
      var corrupt = new ArrayList<>(valid);
      var event = valid.get(index);
      corrupt.set(index, new ExecutionTrace.Event(event.operation(), event.kind(), 0));
      assertEquals(
          ExactVerifier.Verdict.VIOLATED,
          ExactTraceVerifier.verify(new ExecutionTrace(1, true, corrupt), oracle));
    }
    var event = valid.get(1);
    valid.set(1, new ExecutionTrace.Event(event.operation(), event.kind(), 42, 1, 1, 1, 0));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, true, valid), oracle));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "parallel { worker a {} worker b {} }",
        "read-volatile-root shared N main;",
        "write-weak n 0 null;"
      })
  void unsupportedDirectHistoryBackendsRejectBeforeMissingEvidence(String extra) {
    String header =
        extra.startsWith("write-weak")
            ? "gir 1; shape N object 1 1; object 1 N [null] [7]; root main = 1; "
            : HEADER;
    var program =
        GirParser.parse(header + "program { read-root n N main; identity-hash n; " + extra + " }");
    var trace = new ExecutionTrace(1, false, List.of());
    var history = new ReferenceHistory(1, false, List.of());
    assertThrows(
        UnsupportedHistoryException.class,
        () -> HistoryOracle.check(program, trace, history, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () ->
            HistoryOracle.checkFailure(program, trace, history, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.check(program, trace, history, List.of(), HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
    if (!extra.isEmpty())
      assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
  }

  @Test
  void exhaustedStepBudgetAndInvalidOperandLeaveNoSubjectObligation() {
    var program = GirParser.parse(HEADER + "program { read-root n N main; }");
    var machine = new GraphMachine(program, new OracleLimits(1, 10, 10, 100));
    machine.apply((Instruction.Operation) program.body().getFirst());
    assertThrows(
        OracleLimitException.class,
        () -> machine.apply(new Instruction.IdentityHash(2, new Operand.Name("n"))));
    assertEquals(List.of(), machine.identityHashes());
    assertEquals(1, machine.events().size());
  }

  @Test
  void compatibilityConstructorKeepsOrdinaryLegacyScalarsExact() {
    var oracle =
        Oracle.interpret(
            GirParser.parse(HEADER + "program { read-root n N main;" + " read-data value n 0; }"));
    var legacy =
        new OracleResult(
            oracle.finalGraph(), oracle.checkpoints(), oracle.steps(), oracle.events());
    assertEquals(List.of(), legacy.identityHashes());
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, true, legacy.events()), legacy));
    var corrupt = new ArrayList<>(legacy.events());
    corrupt.set(1, new ExecutionTrace.Event(2, ExecutionTrace.Kind.SCALAR_READ, 0));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(1, false, corrupt), legacy));
  }

  @Test
  void invalidModelOperandRejectsWithoutChangingStateOrHashObligations() {
    var program = GirParser.parse(HEADER + "program { read-root n N main; }");
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    var key = machine.key();
    for (Operand operand : List.of(new Operand.Name("missing"), new Operand.Literal(0))) {
      assertThrows(
          io.connglli.gorrect.engine.gir.GirException.class,
          () -> machine.apply(new Instruction.IdentityHash(2, operand)));
      assertEquals(key, machine.key());
      assertEquals(List.of(), machine.events());
      assertEquals(List.of(), machine.identityHashes());
    }
  }

  private static ExactVerifier.Verdict verify(
      OracleResult oracle, boolean complete, long... hashes) {
    return ExactTraceVerifier.verify(
        new ExecutionTrace(1, complete, hashEvents(oracle, hashes)), oracle);
  }

  private static ArrayList<ExecutionTrace.Event> hashEvents(OracleResult oracle, long... hashes) {
    assertEquals(oracle.identityHashes().size(), hashes.length);
    var events = new ArrayList<>(oracle.events());
    for (int i = 0; i < hashes.length; i++) {
      int index = oracle.identityHashes().get(i).eventIndex();
      var event = events.get(index);
      events.set(
          index,
          new ExecutionTrace.Event(
              event.operation(),
              event.kind(),
              hashes[i],
              event.worker(),
              event.group(),
              event.round(),
              event.phase()));
    }
    return events;
  }
}
