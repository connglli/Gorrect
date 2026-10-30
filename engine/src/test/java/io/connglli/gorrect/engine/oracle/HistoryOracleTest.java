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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryOracleTest {
  private static final Shape N = new Shape("N", Shape.Kind.OBJECT, 1, 1);
  private static final ObjectId OWNER = ObjectId.initial(1), TARGET = ObjectId.initial(2);
  private static final WrapperId WRAPPER = new WrapperId(0, 0, 1, 0);
  private static final ReferenceHistory.Identity ID = new ReferenceHistory.Identity(0, 0, 1, 0);
  private static final String BODY =
      "program { read-root owner N main; acquire held N owner 0 { write-root saved held; } release"
          + " owner; }";

  @ParameterizedTest
  @ValueSource(strings = {"soft", "weak"})
  void acceptsEitherLegalChoiceAndChecksEveryAccessibleReferentsPayload(String kind) {
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        check(kind, false, true, 7, false, HistoryLimits.DEFAULT).verdict());
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        check(kind, false, false, 7, false, HistoryLimits.DEFAULT).verdict());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        check(kind, false, true, 8, false, HistoryLimits.DEFAULT).verdict());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        check(kind, true, false, 7, false, HistoryLimits.DEFAULT).verdict());
  }

  @Test
  void doesNotLetAnInventedChoiceDefineItsOwnExpectedGraph() {
    var result = check("weak", false, true, 7, true, HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict());
    assertFalse(result.invalidWorkload());
    assertTrue(result.problems().getFirst().contains("Impossible acquisition"));
  }

  @Test
  void stateBudgetAndMissingProjectionAreInconclusive() {
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        check("weak", false, true, 7, false, new HistoryLimits(1, 1000)).verdict());
    var program = GirParser.parse(header("weak", false) + BODY);
    var result =
        HistoryOracle.check(
            program,
            trace(true),
            history("weak", true, false),
            List.of(new HistoryOracle.Observation(Optional.empty(), List.of("capture limit"))),
            HistoryLimits.DEFAULT);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict());
    assertEquals(List.of("capture limit"), result.problems());
  }

  @Test
  void validatesTheEntireHistoryEnvelope() {
    var program = GirParser.parse(header("soft", false) + BODY);
    var incomplete = new ReferenceHistory(1, false, history("soft", true, false).events());
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        HistoryOracle.check(program, trace(true), incomplete, List.of(), HistoryLimits.DEFAULT)
            .verdict());
    var original = history("soft", true, false).events().getFirst();
    var changed =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    3, original.kind(), original.strength(), ID, original.target())));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        HistoryOracle.check(program, trace(true), changed, List.of(), HistoryLimits.DEFAULT)
            .verdict());
  }

  private static HistoryOracle.Result check(
      String kind,
      boolean protectedTarget,
      boolean success,
      long payload,
      boolean invented,
      HistoryLimits limits) {
    var program = GirParser.parse(header(kind, protectedTarget) + BODY);
    var refKind = kind.equals("soft") ? Value.ReferenceKind.SOFT : Value.ReferenceKind.WEAK;
    var objects =
        Map.of(
            OWNER,
            new HeapObject(
                N,
                List.of(new Value.Reference(refKind, success ? TARGET : null, WRAPPER)),
                List.of(0L)),
            TARGET,
            new HeapObject(N, List.of(Value.Reference.NULL), List.of(payload)));
    var roots = new HashMap<String, Value.Reference>();
    roots.put("main", Value.Reference.strong(OWNER));
    roots.put("saved", success ? Value.Reference.strong(TARGET) : Value.Reference.NULL);
    if (protectedTarget) roots.put("protected", Value.Reference.strong(TARGET));
    var graph = new Graph(objects, roots, Map.of());
    return HistoryOracle.check(
        program,
        trace(success),
        history(kind, success, invented),
        List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
        limits);
  }

  private static String header(String kind, boolean protectedTarget) {
    return "gir 1; shape N object 1 1; object 1 N ["
        + kind
        + "(2)] [0]; object 2 N [null] [7]; root main = 1; root saved = null; "
        + (protectedTarget ? "root protected = 2; " : "");
  }

  private static ExecutionTrace trace(boolean success) {
    var events = new ArrayList<ExecutionTrace.Event>();
    events.add(new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0));
    events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.ACQUIRE, success ? 1 : 0));
    if (success) {
      events.add(new ExecutionTrace.Event(3, ExecutionTrace.Kind.OPERATION, 0));
      events.add(new ExecutionTrace.Event(2, ExecutionTrace.Kind.ACQUIRE_END, 0));
    }
    events.add(new ExecutionTrace.Event(4, ExecutionTrace.Kind.OPERATION, 0));
    return new ExecutionTrace(1, true, events);
  }

  private static ReferenceHistory history(String kind, boolean success, boolean invented) {
    return new ReferenceHistory(
        1,
        true,
        List.of(
            new ReferenceHistory.Event(
                2,
                ReferenceHistory.Kind.ACQUIRE,
                kind.equals("soft") ? 1 : 2,
                ID,
                success ? new ReferenceHistory.Target(0, 0, invented ? 99 : 2) : null)));
  }
}
