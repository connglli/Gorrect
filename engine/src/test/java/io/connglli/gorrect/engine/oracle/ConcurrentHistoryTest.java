package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrentHistoryTest {
  @Test
  void storedJoinAndPendingStatesIncludeTheirClearingPossibilities() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0;
            object 1 N [weak(2)] []; object 2 N [null] []; root r = 1;
            program { parallel { worker only {} } }
            """);
    assertThrows(
        HistoryLimitException.class,
        () -> ConcurrentHistory.audit(program, new HistoryLimits(3, 1_000_000)));
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(4, 1_000_000)));
  }

  @Test
  void strongReadPresenceCannotBeInferredFromAnAllZeroIdentity() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 0; root r = null; program { read-volatile-root n N r; release"
                + " n; checkpoint; }");
    var graph =
        new io.connglli.gorrect.engine.model.Graph(
            java.util.Map.of(),
            java.util.Map.of("r", io.connglli.gorrect.engine.model.Value.Reference.NULL),
            java.util.Map.of());
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(1, ReferenceHistory.Kind.STRONG_READ, 0, null, null)));
    var observations =
        List.of(
            new HistoryOracle.Observation(java.util.Optional.of(graph), List.of()),
            new HistoryOracle.Observation(java.util.Optional.of(graph), List.of()));
    for (int presence = 0; presence <= 1; presence++) {
      var trace =
          new ExecutionTrace(
              1,
              true,
              List.of(
                  new ExecutionTrace.Event(1, ExecutionTrace.Kind.REFERENCE_READ, presence),
                  new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0),
                  new ExecutionTrace.Event(3, ExecutionTrace.Kind.OPERATION, 0)));
      assertEquals(
          presence == 0 ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
          ConcurrentHistory.check(program, trace, history, observations, HistoryLimits.DEFAULT)
              .verdict());
    }
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ConcurrentHistory.check(
                program,
                new ExecutionTrace(1, true, List.of()),
                history,
                List.of(),
                HistoryLimits.DEFAULT)
            .verdict());
  }

  @Test
  void mergesEquivalentJoinedStatesAcrossModeledWarmupVisits() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { repeat 3 {
                  write-root winner null;
                  parallel {
                    worker a { read-volatile-root n N left; compare-set-root won winner null n; write-data n 0 won; }
                    worker b { read-volatile-root n N right; compare-set-root won winner null n; write-data n 0 won; }
                  }
                } }
                """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(4096, 2_000_000)));
  }

  private static final String HEADER =
      "gir 1; shape N object 1 1; object 1 N [null] [0]; object 2 N [null] [0]; root left = 1; root"
          + " right = 2; root winner = null; ";

  @Test
  void provesAtomicCompetitionAndRejectsOrdinaryRaces() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { parallel {
                  worker a { read-volatile-root n N left; compare-set-root won winner null n; write-data n 0 won; }
                  worker b { read-volatile-root n N right; compare-set-root won winner null n; write-data n 0 won; }
                } checkpoint; }
                """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(4096, 1_000_000)));
    var raced =
        GirParser.parse(
            HEADER
                + """
                program { parallel {
                  worker a { read-volatile-root n N left; write-data n 0 1; }
                  worker b { read-volatile-root n N left; read-data value n 0; }
                } }
                """);
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(raced, new HistoryLimits(4096, 1_000_000)));
  }

  @Test
  void provesPublicationAndChecksEverySchedule() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { parallel {
                  worker producer { allocate n N; write-data n 0 7; write-volatile-root winner n; }
                  worker consumer { read-volatile-root n N winner; if-present n { read-data value n 0; } }
                } }
                """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(4096, 1_000_000)));
    var plain =
        GirParser.parse(
            HEADER
                + """
                program { parallel {
                  worker producer { allocate n N; write-data n 0 7; write-root winner n; }
                  worker consumer { read-volatile-root n N winner; if-present n { read-data value n 0; } }
                } }
                """);
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(plain, new HistoryLimits(4096, 1_000_000)));
  }

  @Test
  void validatesReferenceScopesAndBudgets() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; object 1 N [weak(2)] [0]; object 2 N [null] [7]; root r = 1;
            program { scope { read-root owner N r; acquire held N owner 0 {
              parallel { worker a { collect; } worker b { read-root owner N r; is-cleared cleared owner 0; } }
            } } checkpoint; }
            """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(4096, 1_000_000)));
    assertThrows(
        HistoryLimitException.class,
        () -> ConcurrentHistory.audit(program, new HistoryLimits(1, 1)));
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ConcurrentHistory.check(
                program,
                new ExecutionTrace(1, false, List.of()),
                new ReferenceHistory(1, false, List.of()),
                List.of(),
                HistoryLimits.DEFAULT)
            .verdict());
  }
}
