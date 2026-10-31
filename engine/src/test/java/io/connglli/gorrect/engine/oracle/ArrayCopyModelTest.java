package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ArrayCopyModelTest {
  private static final String DATA =
      "gir 1; shape D data 6; object 1 D [] [1,2,3,4,5,6];"
          + " object 2 D [] [9,9,9,9,9,9]; root a = 1; root b = 2;";
  private static final String MIXED =
      "gir 1; shape D data 4; shape R refs 4; shape N object 0 0;"
          + " object 1 D [] [1,2,3,4]; object 2 D [] [9,8,7,6];"
          + " object 3 R [null,null,null,null] []; object 4 N [] [];"
          + " root one=1; root two=2; root refs=3; root node=4; root nil=null;";

  @ParameterizedTest
  @CsvSource({"0,1,5,1:1:2:3:4:5", "1,0,5,2:3:4:5:6:6", "2,2,3,1:2:3:4:5:6", "6,6,0,1:2:3:4:5:6"})
  void overlappingCopiesUseThePreOperationContents(
      int from, int into, int length, String expected) {
    var result =
        Oracle.interpret(
            GirParser.parse(
                DATA
                    + " program { read-root x D a;"
                    + " copy-array x "
                    + from
                    + " x "
                    + into
                    + " "
                    + length
                    + "; }"));
    assertEquals(
        java.util.Arrays.stream(expected.split(":")).map(Long::valueOf).toList(),
        result.finalGraph().objects().get(ObjectId.initial(1)).data());
    assertEquals(2, result.finalGraph().objects().size());
    assertEquals(2, result.steps());
  }

  @ParameterizedTest
  @CsvSource({"0,1,3,1:1:2:0", "1,0,3,2:0:1:1", "4,4,0,1:2:0:1"})
  void referenceSelfCopyUsesTheSameOverlapRule(int from, int into, int length, String expected) {
    var result =
        Oracle.interpret(
            GirParser.parse(
                "gir 1; shape N object 0 0; shape R refs 4;"
                    + " object 1 N [] []; object 2 N [] []; object 3 R [1,2,null,1] []; root r=3;"
                    + " program { read-root a R r; copy-array a "
                    + from
                    + " a "
                    + into
                    + " "
                    + length
                    + "; }"));
    var values =
        java.util.Arrays.stream(expected.split(":"))
            .map(Long::parseLong)
            .map(
                value ->
                    value == 0
                        ? Value.Reference.NULL
                        : Value.Reference.strong(ObjectId.initial(value)))
            .toList();
    assertEquals(values, result.finalGraph().objects().get(ObjectId.initial(3)).references());
  }

  @Test
  void distinctCopyPreservesSourceDestinationIdentitiesAndOutsideValues() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                DATA
                    + " program { read-root x D a;"
                    + " read-root y D b; copy-array x 1 y 2 3; }"));
    assertEquals(
        List.of(1L, 2L, 3L, 4L, 5L, 6L),
        result.finalGraph().objects().get(ObjectId.initial(1)).data());
    assertEquals(
        List.of(9L, 9L, 2L, 3L, 4L, 9L),
        result.finalGraph().objects().get(ObjectId.initial(2)).data());
    assertEquals(Value.Reference.strong(ObjectId.initial(1)), result.finalGraph().roots().get("a"));
    assertEquals(Value.Reference.strong(ObjectId.initial(2)), result.finalGraph().roots().get("b"));
  }

  @Test
  void referenceContentsAreShallowAndArrayStorageRemainsIndependent() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                "gir 1; shape N object 0 1; shape A refs 2;"
                    + " shape B refs 3; object 1 N [] [7]; object 2 N [] [9];"
                    + " object 3 A [1,2] []; object 4 B [null,null,null] []; root a=3; root b=4;"
                    + " program { read-root x A a; read-root y B b; copy-array x 0 y 1 2;"
                    + " read-ref child N y 1; write-data child 0 11; write-ref y 1 null; }"));
    assertEquals(
        List.of(
            Value.Reference.strong(ObjectId.initial(1)),
            Value.Reference.strong(ObjectId.initial(2))),
        result.finalGraph().objects().get(ObjectId.initial(3)).references());
    assertEquals(
        List.of(
            Value.Reference.NULL,
            Value.Reference.NULL,
            Value.Reference.strong(ObjectId.initial(2))),
        result.finalGraph().objects().get(ObjectId.initial(4)).references());
    assertEquals(List.of(11L), result.finalGraph().objects().get(ObjectId.initial(1)).data());
  }

  @Test
  void invalidOwnersKindsAndRangesLeaveAllModelBookkeepingUnchanged() {
    var machine = mixed(new OracleLimits(100, 4, 12, 1000));
    for (var copy :
        List.of(
            new Instruction.CopyArray(20, "nil", 0, "b", 0, 0),
            new Instruction.CopyArray(20, "a", 0, "nil", 0, 0),
            new Instruction.CopyArray(20, "n", 0, "b", 0, 0),
            new Instruction.CopyArray(20, "a", 0, "r", 0, 0),
            new Instruction.CopyArray(20, "missing", 0, "b", 0, 0),
            new Instruction.CopyArray(20, "a", -1, "b", 0, 0),
            new Instruction.CopyArray(20, "a", 0, "b", -1, 0),
            new Instruction.CopyArray(20, "a", 0, "b", 0, -1),
            new Instruction.CopyArray(20, "a", 4, "b", 0, 1),
            new Instruction.CopyArray(20, "a", 0, "b", 4, 1),
            new Instruction.CopyArray(20, "a", Integer.MAX_VALUE, "b", 0, 1),
            new Instruction.CopyArray(20, "a", 0, "b", 0, Integer.MAX_VALUE))) {
      var before = machine.key();
      var events = machine.events();
      assertThrows(GirException.class, () -> machine.apply(copy));
      assertEquals(before, machine.key());
      assertEquals(events, machine.events());
    }
    machine.apply(new Instruction.Release(21, "a"));
    var before = machine.key();
    assertThrows(
        GirException.class, () -> machine.apply(new Instruction.CopyArray(22, "a", 0, "b", 0, 0)));
    assertEquals(before, machine.key());
  }

  @Test
  void copyConsumesOneStepWithoutAllocationSlotOrOccurrenceCharges() {
    var machine = mixed(new OracleLimits(6, 4, 12, 1000));
    machine.apply(new Instruction.CopyArray(20, "a", 1, "b", 0, 3));
    assertEquals(6, machine.steps());
    assertEquals(4, machine.graph().objects().size());
    assertEquals(
        List.of(2L, 3L, 4L, 6L), machine.graph().objects().get(ObjectId.initial(2)).data());
    assertEquals(1, machine.visit(20));
    var before = machine.key();
    assertThrows(
        OracleLimitException.class,
        () -> machine.apply(new Instruction.CopyArray(21, "a", 0, "b", 0, 0)));
    assertEquals(before, machine.key());
  }

  @Test
  void everyConcurrentAndMixedHistoryEntryRejectsBeforeIncompleteEvidence() {
    var strong = GirParser.parse(DATA + " program { read-root x D a; copy-array x 0 x 0 0; }");
    var parallel =
        GirParser.parse(
            DATA
                + " program { read-root x D a; copy-array x 0 x 0 0;"
                + " parallel { worker first {} worker second {} } }");
    var weak =
        GirParser.parse(
            "gir 1; shape N object 1 0; shape D data 0;"
                + " object 1 N [weak(1)] []; root r=1; root a=null; program {"
                + " read-root x D a; copy-array x 0 x 0 0; }");
    var trace = new ExecutionTrace(1, false, List.of());
    var history = new ReferenceHistory(1, false, List.of());
    for (var program : List.of(strong, parallel, weak)) {
      assertThrows(
          UnsupportedHistoryException.class,
          () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
      assertThrows(
          UnsupportedHistoryException.class,
          () -> ConcurrentHistory.check(program, trace, history, List.of(), HistoryLimits.DEFAULT));
    }
    for (var program : List.of(parallel, weak)) {
      assertThrows(
          UnsupportedHistoryException.class,
          () -> HistoryOracle.check(program, trace, history, List.of(), HistoryLimits.DEFAULT));
      assertThrows(
          UnsupportedHistoryException.class,
          () ->
              HistoryOracle.checkFailure(
                  program, trace, history, List.of(), HistoryLimits.DEFAULT));
    }
    var machine = mixed(OracleLimits.DEFAULT);
    assertThrows(
        UnsupportedHistoryException.class,
        () -> machine.memory(new Instruction.CopyArray(20, "a", 0, "b", 0, 1)));
  }

  private static GraphMachine mixed(OracleLimits limits) {
    var machine = new GraphMachine(GirParser.parse(MIXED + "program {}"), limits);
    machine.apply(new Instruction.ReadRoot(1, "a", "D", "one"));
    machine.apply(new Instruction.ReadRoot(2, "b", "D", "two"));
    machine.apply(new Instruction.ReadRoot(3, "r", "R", "refs"));
    machine.apply(new Instruction.ReadRoot(4, "n", "N", "node"));
    machine.apply(new Instruction.ReadRoot(5, "nil", "D", "nil"));
    return machine;
  }
}
