package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OracleTest {
  private static final String HEADER =
      """
      gir 1;
      shape N object 1 1;
      object 1 N [null] [7];
      root r = 1;
      """;

  @Test
  void checksAliasesCyclesRetargetingPayloadAndSnapshotImmutability() throws IOException {
    String source;
    try (var input = getClass().getResourceAsStream("/cases/core.gir")) {
      source =
          new String(
              java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
    }
    var result = Oracle.interpret(GirParser.parse(source));
    var node = new Shape("Node", Shape.Kind.OBJECT, 2, 1);
    var one = ObjectId.initial(1);
    var two = ObjectId.initial(2);
    var created = new ObjectId(0, 5, 1);
    var before =
        new Graph(
            Map.of(
                one, new HeapObject(node, List.of(ref(created), ref(two)), List.of(11L)),
                two, new HeapObject(node, List.of(ref(one), Value.Reference.NULL), List.of(22L)),
                created,
                    new HeapObject(node, List.of(Value.Reference.NULL, ref(two)), List.of(22L))),
            Map.of("main", ref(one), "saved", ref(created)),
            Map.of(2, ref(one), 3, ref(two), 5, ref(created)));
    var after =
        new Graph(
            Map.of(
                one,
                    new HeapObject(node, List.of(ref(created), Value.Reference.NULL), List.of(11L)),
                two, new HeapObject(node, List.of(ref(one), Value.Reference.NULL), List.of(22L)),
                created,
                    new HeapObject(node, List.of(Value.Reference.NULL, ref(two)), List.of(99L))),
            Map.of("main", ref(one), "saved", ref(created)),
            Map.of());
    assertEquals(
        List.of(10, 14, 17),
        result.checkpoints().stream().map(OracleResult.Checkpoint::operation).toList());
    assertEquals(before, result.checkpoints().get(0).graph());
    assertEquals(after, result.checkpoints().get(1).graph());
    assertTrue(result.checkpoints().get(2).graph().objects().isEmpty());
    assertEquals(
        Map.of("main", Value.Reference.NULL, "saved", Value.Reference.NULL),
        result.finalGraph().roots());
    assertTrue(result.finalGraph().objects().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> before.objects().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> before.objects().get(one).references().clear());
  }

  @Test
  void repeatUsesFreshHandlesAndDistinctAllocationIdentities() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                """
                gir 1;
                shape N object 1 0;
                root head = null;
                program {
                  repeat 3 {
                    allocate n N;
                    read-root old N head;
                    write-ref n 0 old;
                    write-root head n;
                    checkpoint;
                  }
                }
                """));
    assertEquals(19, result.steps());
    assertEquals(
        List.of(1L, 2L, 3L),
        result.checkpoints().stream().map(OracleResult.Checkpoint::occurrence).toList());
    assertEquals(ref(new ObjectId(0, 2, 3)), result.finalGraph().roots().get("head"));
    assertEquals(
        List.of(ref(new ObjectId(0, 2, 2))),
        result.finalGraph().objects().get(new ObjectId(0, 2, 3)).references());
    assertEquals(
        List.of(ref(new ObjectId(0, 2, 1))),
        result.finalGraph().objects().get(new ObjectId(0, 2, 2)).references());
    assertEquals(
        List.of(Value.Reference.NULL),
        result.finalGraph().objects().get(new ObjectId(0, 2, 1)).references());
    assertTrue(result.finalGraph().handles().isEmpty());
    assertEquals(
        result,
        Oracle.interpret(
            GirParser.parse(
                """
                gir 1; shape N object 1 0; root head = null;
                program { repeat 3 { allocate n N; read-root old N head;
                  write-ref n 0 old; write-root head n; checkpoint; } }
                """)));
  }

  @Test
  void localHandlesProtectObjectsUntilReleasedOrTheirScopeEnds() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + """
                    program {
                      read-root held N r;
                      write-root r null;
                      checkpoint;
                      release held;
                      checkpoint;
                    }
                    """));
    assertEquals(
        Map.of(1, ref(ObjectId.initial(1))), result.checkpoints().get(0).graph().handles());
    assertEquals(1, result.checkpoints().get(0).graph().objects().size());
    assertTrue(result.checkpoints().get(1).graph().objects().isEmpty());
    var implicit =
        Oracle.interpret(
            GirParser.parse("gir 1; shape N object 0 0; program { allocate a N; checkpoint; }"));
    assertEquals(1, implicit.checkpoints().getFirst().graph().objects().size());
    assertTrue(implicit.finalGraph().objects().isEmpty());
  }

  @Test
  void readsAndWritesThroughAliasesUseTheCurrentObjectContents() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                HEADER
                    + """
                    program {
                      read-root a N r;
                      read-root b N r;
                      write-data a 0 99;
                      read-data value b 0;
                      write-ref a 0 b;
                      read-ref c N b 0;
                      write-data c 0 value;
                    }
                    """));
    var object = result.finalGraph().objects().get(ObjectId.initial(1));
    assertEquals(List.of(99L), object.data());
    assertEquals(List.of(ref(ObjectId.initial(1))), object.references());
  }

  @Test
  void rootLifetimesAreIndependentEvenWhenTheirTargetsAlias() {
    var result =
        Oracle.interpret(
            GirParser.parse(HEADER + "root second = 1; program { write-root r null; }"));
    assertEquals(Value.Reference.NULL, result.finalGraph().roots().get("r"));
    assertEquals(ref(ObjectId.initial(1)), result.finalGraph().roots().get("second"));
    assertEquals(1, result.finalGraph().objects().size());
  }

  @Test
  void handlesReferenceAndLongArrays() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                """
                gir 1;
                shape Links refs 2;
                shape Payload data 2;
                object 1 Links [2, 2] [];
                object 2 Payload [] [-9223372036854775808, 9223372036854775807];
                root r = 1;
                program {
                  read-root array Links r;
                  read-ref payload Payload array 0;
                  read-data value payload 0;
                  write-data payload 1 value;
                  write-ref array 0 null;
                }
                """));
    assertEquals(
        List.of(Value.Reference.NULL, ref(ObjectId.initial(2))),
        result.finalGraph().objects().get(ObjectId.initial(1)).references());
    assertEquals(
        List.of(Long.MIN_VALUE, Long.MIN_VALUE),
        result.finalGraph().objects().get(ObjectId.initial(2)).data());
  }

  @Test
  void validatesExecutedNullDereferencesAndShapeAssertions() {
    assertThrows(
        GirException.class,
        () ->
            Oracle.interpret(
                GirParser.parse(
                    """
                    gir 1; shape N object 1 0; root r = null;
                    program { read-root a N r; write-ref a 0 null; }
                    """)));
    assertThrows(
        GirException.class,
        () ->
            Oracle.interpret(
                GirParser.parse(
                    HEADER
                        + """
                        shape Other object 1 1;
                        program { read-root a Other r; }
                        """)));
  }

  @Test
  void rejectedMutationsLeaveGraphAndStepBudgetUnchanged() {
    var machine =
        new GraphMachine(
            GirParser.parse(HEADER + "shape Other object 1 1; program {}"), OracleLimits.DEFAULT);
    machine.apply(new Instruction.ReadRoot(1, "a", "N", "r"));
    var before = machine.graph();
    assertThrows(
        GirException.class,
        () -> machine.apply(new Instruction.WriteReference(2, "a", 9, Operand.Null.INSTANCE)));
    assertEquals(before, machine.graph());
    assertThrows(
        GirException.class,
        () -> machine.apply(new Instruction.WriteData(2, "a", 0, new Operand.Name("a"))));
    assertEquals(before, machine.graph());
    assertThrows(
        GirException.class, () -> machine.apply(new Instruction.ReadRoot(2, "a", "N", "r")));
    assertEquals(before, machine.graph());
    assertThrows(
        GirException.class,
        () -> machine.apply(new Instruction.ReadRoot(2, "wrong", "Other", "r")));
    assertEquals(before, machine.graph());
    assertEquals(1, machine.steps());
    machine.apply(new Instruction.WriteData(2, "a", 0, new Operand.Literal(99)));
    assertNotEquals(before, machine.graph());
    assertEquals(2, machine.steps());
  }

  @Test
  void rejectedAllocationDoesNotConsumeAnIdentityOrBudget() {
    var program = GirParser.parse(HEADER + "shape Big refs 10; shape Empty object 0 0; program {}");
    var machine = new GraphMachine(program, new OracleLimits(10, 2, 2, 100));
    var before = machine.graph();
    assertThrows(
        OracleLimitException.class, () -> machine.apply(new Instruction.Allocate(5, "n", "Big")));
    assertEquals(before, machine.graph());
    assertEquals(0, machine.steps());
    machine.apply(new Instruction.Allocate(5, "n", "Empty"));
    assertEquals(ref(new ObjectId(0, 5, 1)), machine.graph().handles().get(5));
    assertEquals(1, machine.steps());
  }

  @Test
  void rejectedCheckpointDoesNotAdvanceItsOccurrenceOrBudget() {
    var machine =
        new GraphMachine(GirParser.parse(HEADER + "program {}"), new OracleLimits(10, 2, 2, 2));
    machine.apply(new Instruction.ReadRoot(1, "a", "N", "r"));
    assertThrows(OracleLimitException.class, () -> machine.apply(new Instruction.Checkpoint(5)));
    assertEquals(1, machine.steps());
    assertTrue(machine.checkpoints().isEmpty());
    machine.apply(new Instruction.WriteRoot(2, "r", Operand.Null.INSTANCE));
    machine.apply(new Instruction.Release(3, "a"));
    machine.apply(new Instruction.Checkpoint(5));
    assertEquals(1, machine.checkpoints().getFirst().occurrence());
    assertEquals(4, machine.steps());
  }

  @Test
  void budgetsBoundEmptyLoopsInitialGraphsAndFinalSnapshots() {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          var program = GirParser.parse("gir 1; program { repeat 9223372036854775807 {} }");
          assertThrows(
              OracleLimitException.class,
              () -> Oracle.interpret(program, new OracleLimits(100, 0, 0, 1)));
        });
    var initial = GirParser.parse(HEADER + "program {}");
    assertThrows(
        OracleLimitException.class,
        () -> Oracle.interpret(initial, new OracleLimits(10, 0, 100, 100)));
    assertThrows(
        OracleLimitException.class,
        () -> Oracle.interpret(initial, new OracleLimits(10, 1, 0, 100)));
    assertThrows(
        OracleLimitException.class, () -> Oracle.interpret(initial, new OracleLimits(10, 1, 2, 0)));
    var huge =
        GirParser.parse(
            "gir 1; shape Huge object 2147483647 2147483647; program { allocate a Huge; }");
    assertThrows(OracleLimitException.class, () -> Oracle.interpret(huge));
    var empty =
        Oracle.interpret(GirParser.parse("gir 1; program {}"), new OracleLimits(0, 0, 0, 1));
    assertEquals(0, empty.steps());
    assertTrue(empty.finalGraph().objects().isEmpty());
  }

  private static Value.Reference ref(ObjectId id) {
    return Value.Reference.strong(id);
  }
}
