package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhaseReuseTest {
  @Test
  void repeatedPublicationFitsTheUnchangedDefaultWorkBound() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; root left = null; root right = null;
            program { repeat 3 { parallel {
              worker a { allocate held N; write-root left held; checkpoint;
                write-root left null; release held; checkpoint; }
              worker b { allocate held N; write-root right held; checkpoint;
                write-root right null; release held; checkpoint; }
            } } }
            """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
    assertThrows(
        HistoryLimitException.class,
        () -> ConcurrentHistory.audit(program, new HistoryLimits(4096, 100)));
    assertThrows(
        HistoryLimitException.class,
        () -> ConcurrentHistory.audit(program, new HistoryLimits(2, 1_000_000)));
  }

  @Test
  void aSingleWorkerKeepsItsExistingSmallStateBound() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 0; program { parallel { worker only { checkpoint; checkpoint;"
                + " } } }");
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, new HistoryLimits(2, 1_000_000)));
  }

  @Test
  void modelSnapshotsIgnoreActiveSelectionAndFreezeEveryContext() {
    var program = scalarProgram(1);
    var machine = prepared(program);
    var caller = machine.context();
    var worker = machine.workerContext(1, 9, 2);
    machine.activate(worker, 3);
    machine.apply(new Instruction.ReadRoot(10, "local", "N", "r"));
    var contexts = List.of(caller, worker);
    Object shared = machine.key(contexts);
    Object frozen = GraphMachine.contextKey(caller, 0);
    var keys = Set.of(frozen);

    machine.activate(caller, 0);
    assertEquals(shared, machine.key(contexts));
    assertEquals(frozen, GraphMachine.contextKey(prepared(program).context(), 0));
    machine.apply(new Instruction.Release(11, "owner"));
    assertNotEquals(frozen, GraphMachine.contextKey(caller, 0));
    assertEquals(Set.of(GraphMachine.contextKey(prepared(program).context(), 0)), keys);
    assertNotEquals(GraphMachine.contextKey(caller, 0), GraphMachine.contextKey(caller, 1));

    var reserved = new GraphMachine(program, OracleLimits.DEFAULT);
    reserved.apply(new Instruction.ReadRoot(1, "owner", "N", "r"));
    reserved.apply(new Instruction.Release(2, "owner"));
    var empty = new GraphMachine(program, OracleLimits.DEFAULT).context();
    assertNotEquals(
        GraphMachine.contextKey(reserved.context(), 0), GraphMachine.contextKey(empty, 0));
  }

  @Test
  void equalHeapsDoNotEraseDifferentCopiedScalarsOrResourceState() {
    var zero = prepared(scalarProgram(0));
    var one = prepared(scalarProgram(1));
    assertEquals(zero.graph(), one.graph());
    assertEquals(zero.key(List.of(zero.context())), one.key(List.of(one.context())));
    assertNotEquals(
        GraphMachine.contextKey(zero.context(), 0), GraphMachine.contextKey(one.context(), 0));

    Object before = zero.key(List.of(zero.context()));
    zero.chargeSnapshot(zero.graph().accessible());
    assertNotEquals(before, zero.key(List.of(zero.context())));
    Object afterSnapshot = zero.key(List.of(zero.context()));
    zero.step(12, false);
    assertNotEquals(afterSnapshot, zero.key(List.of(zero.context())));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void everyScalarOutcomeStillChecksItsFuturePreconditions(boolean invalidNonzero) {
    var outcomes = new HashSet<Long>();
    scalarSchedules(0, 0, 0, -1, outcomes);
    assertEquals(Set.of(0L, 7L), outcomes);
    String invalid = "read-root bad N empty; read-data impossible bad 0;";
    String branch =
        invalidNonzero
            ? "if-nonzero value { " + invalid + " }"
            : "if-nonzero value {} else { " + invalid + " }";
    var program =
        GirParser.parse(
            HEADER
                + "program { parallel { worker reader { read-root owner N r;"
                + " read-volatile-data value owner 0; barrier; "
                + branch
                + " } worker writer { read-root owner N r;"
                + " write-volatile-data owner 0 7; write-volatile-data owner 0 0; barrier; } } }");
    assertThrows(GirException.class, () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  @Test
  void agreesWithIndependentSmallScalarSchedules() {
    for (int initial = 0; initial <= 1; initial++)
      for (int first = 0; first <= 1; first++)
        for (int second = 0; second <= 1; second++) {
          var outcomes = new HashSet<Long>();
          scalarSchedules(0, 0, initial, -1, first, second, outcomes);
          for (int invalid = -1; invalid <= 1; invalid++) {
            String bad = "read-root bad N empty; read-data impossible bad 0;";
            String good = "write-data owner 0 9;";
            String body =
                "if-nonzero value { "
                    + (invalid == 1 ? bad : good)
                    + " } else { "
                    + (invalid == 0 ? bad : good)
                    + " }";
            var program =
                GirParser.parse(
                    HEADER.replace("[] [0]", "[] [" + initial + "]")
                        + "program { parallel { worker reader { read-root owner N r;"
                        + " read-volatile-data value owner 0; barrier; "
                        + body
                        + " } worker writer { read-root owner N r; write-volatile-data owner 0 "
                        + first
                        + "; write-volatile-data owner 0 "
                        + second
                        + "; barrier; } } }");
            if (invalid >= 0 && outcomes.contains((long) invalid))
              assertThrows(
                  GirException.class,
                  () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
            else assertDoesNotThrow(() -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
          }
        }
  }

  @Test
  void copiedNullHandlesCannotBeRecoveredFromTheEqualFinalRoot() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { parallel {
                  worker reader { read-volatile-root held N r; barrier; read-data value held 0; }
                  worker writer { read-root original N r; write-volatile-root r null;
                    write-volatile-root r original; barrier; }
                } }
                """);
    assertThrows(GirException.class, () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void casWinnersKeepDistinctPostBoundaryBranches(boolean unsafeLeft) {
    String bad = "read-root missing N empty; write-data missing 0 1;";
    String good = "write-data owner 0 7;";
    String left = unsafeLeft ? bad : good;
    String right = unsafeLeft ? good : bad;
    var program =
        GirParser.parse(
            HEADER
                + "root claimed = null; program { parallel {"
                + " worker a { read-root owner N r; compare-set-root won claimed null owner;"
                + " barrier; if-nonzero won { "
                + left
                + " } }"
                + " worker b { read-root owner N r; compare-set-root won claimed null owner;"
                + " barrier; if-nonzero won { "
                + right
                + " } } } }");
    // Either worker can win; a safe first winner cannot certify the other's future branch.
    assertThrows(GirException.class, () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  @Test
  void safeAtomicOwnershipAndOrderedReadsRemainAccepted() {
    var program =
        GirParser.parse(
            HEADER
                + """
                root claimed = null; program { repeat 2 {
                  write-root claimed null;
                  parallel {
                    worker a { read-root owner N r; compare-set-root won claimed null owner;
                      barrier; if-nonzero won { write-data owner 0 7; } checkpoint; }
                    worker b { read-root owner N r; compare-set-root won claimed null owner;
                      barrier; if-nonzero won { write-data owner 0 7; } checkpoint; }
                  }
                } }
                """);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryConflictsFailOnEitherSideOfABoundary(boolean afterBoundary) {
    String first =
        afterBoundary ? "barrier; write-data owner 0 7;" : "write-data owner 0 7; barrier;";
    var program =
        GirParser.parse(
            HEADER
                + "root alias = 1; program { parallel { worker a { read-root owner N r; "
                + first
                + " } worker b { read-root owner N alias; "
                + first
                + " } } }");
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
  }

  @Test
  void referenceProcessingRetainsItsExistingStateAccounting() {
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

  private static final String HEADER =
      "gir 1; shape N object 0 1; object 1 N [] [0]; root r = 1; root empty = null; ";

  private static GirProgram scalarProgram(long initial) {
    return GirParser.parse(
        "gir 1; shape N object 0 1; object 1 N [] ["
            + initial
            + "]; root r = 1;"
            + " program { read-root owner N r; read-data value owner 0; write-data owner 0 0; }");
  }

  private static GraphMachine prepared(GirProgram program) {
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    for (var instruction : program.body()) machine.apply((Instruction.Operation) instruction);
    return machine;
  }

  /** Independent two-process schedule model: root loads are inert, data accesses are atomic. */
  private static void scalarSchedules(
      int reader, int writer, long cell, long observed, Set<Long> outcomes) {
    scalarSchedules(reader, writer, cell, observed, 7, 0, outcomes);
  }

  private static void scalarSchedules(
      int reader,
      int writer,
      long cell,
      long observed,
      long first,
      long second,
      Set<Long> outcomes) {
    if (reader == 2 && writer == 3) {
      outcomes.add(observed);
      return;
    }
    if (reader < 2)
      scalarSchedules(
          reader + 1, writer, cell, reader == 1 ? cell : observed, first, second, outcomes);
    if (writer < 3)
      scalarSchedules(
          reader,
          writer + 1,
          writer == 1 ? first : writer == 2 ? second : cell,
          observed,
          first,
          second,
          outcomes);
  }
}
