package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectCloneModelTest {
  @Test
  void independentGraphChecksShallowSelfCyclesAliasesAndNormalizedPayloads() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 3 [int, long]; shape A refs 1;
            object 1 N [1, 2, 2] [4294967295, 9223372036854775807];
            object 2 A [1] []; root original = 1; root saved = null;
            program { scope {
              read-root n N original; clone c n; write-root saved c;
              write-ref n 0 null; write-data c 0 2147483648;
              read-ref array A c 1; write-ref array 0 c;
              same-object distinct n c;
            } }
            """);
    var n =
        new Shape("N", Shape.Kind.OBJECT, 3, 2, List.of(Shape.DataKind.INT, Shape.DataKind.LONG));
    var a = new Shape("A", Shape.Kind.REFERENCE_ARRAY, 1, 0);
    var source = ObjectId.initial(1);
    var array = ObjectId.initial(2);
    var clone = new ObjectId(0, 3, 1);
    var expected =
        new Graph(
            Map.of(
                source,
                    new HeapObject(
                        n,
                        List.of(
                            Value.Reference.NULL,
                            Value.Reference.strong(array),
                            Value.Reference.strong(array)),
                        List.of(-1L, Long.MAX_VALUE)),
                array, new HeapObject(a, List.of(Value.Reference.strong(clone)), List.of()),
                clone,
                    new HeapObject(
                        n,
                        List.of(
                            Value.Reference.strong(source),
                            Value.Reference.strong(array),
                            Value.Reference.strong(array)),
                        List.of((long) Integer.MIN_VALUE, Long.MAX_VALUE))),
            Map.of(
                "original", Value.Reference.strong(source), "saved", Value.Reference.strong(clone)),
            Map.of());
    assertEquals(expected, Oracle.interpret(program).finalGraph());
  }

  @Test
  void emptyObjectsAndRepeatedSitesHaveFreshOccurrencesAfterRetirement() {
    var program =
        GirParser.parse(
            "gir 1; shape E object 0 0; object 1 E [] []; root r = 1;"
                + " root saved = null; program { read-root e E r; repeat 3 { clone c e;"
                + " write-root saved c; checkpoint; } }");
    var result = Oracle.interpret(program);
    for (int index = 0; index < 3; index++) {
      var graph = result.checkpoints().get(index).graph();
      assertEquals(
          Value.Reference.strong(new ObjectId(0, 3, index + 1)), graph.roots().get("saved"));
      assertNotEquals(graph.roots().get("r"), graph.roots().get("saved"));
      assertEquals(2, graph.objects().size());
    }
    assertThrows(
        OracleLimitException.class,
        () -> Oracle.interpret(program, new OracleLimits(100, 3, 100, 1000)));
  }

  @Test
  void rejectedOperationsPreserveHeapBindingsStepsCountersAndOccurrences() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 2; object 1 N [] [4, 5]; root r = 1; root nil = null; program"
                + " { read-root n N r; read-root empty N nil; }");
    for (var limits :
        List.of(
            new OracleLimits(2, 10, 100, 1000),
            new OracleLimits(100, 1, 100, 1000),
            new OracleLimits(100, 10, 3, 1000))) {
      var machine = new GraphMachine(program, limits);
      for (var instruction : program.body()) machine.apply((Instruction.Operation) instruction);
      rejected(machine, new Instruction.Clone(3, "c", "n"), OracleLimitException.class);
    }
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    for (var instruction : program.body()) machine.apply((Instruction.Operation) instruction);
    rejected(machine, new Instruction.Clone(3, "n", "n"), GirException.class);
    rejected(machine, new Instruction.Clone(3, "c", "empty"), GirException.class);
    rejected(machine, new Instruction.Clone(3, "c", "missing"), GirException.class);
    machine.apply(new Instruction.Clone(3, "c", "n"));
    assertEquals(Value.Reference.strong(new ObjectId(0, 3, 1)), machine.bound("c"));
  }

  @Test
  void conditionalPlannerChargesBothArmsAndSnapshotBudgetsStayCumulative() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 2; object 1 N [] [4, 5];"
                + " root r = 1; program { read-root n N r; if-nonzero 1 { clone a n; checkpoint; }"
                + " else { clone b n; checkpoint; } }");
    assertThrows(
        OracleLimitException.class,
        () -> HistoryPlan.plan(program, new OracleLimits(100, 2, 100, 1000)));
    assertThrows(
        OracleLimitException.class,
        () -> HistoryPlan.plan(program, new OracleLimits(100, 100, 5, 1000)));
    assertThrows(
        OracleLimitException.class,
        () -> Oracle.interpret(program, new OracleLimits(100, 100, 100, 1)));
    assertEquals(2, HistoryPlan.plan(program, OracleLimits.DEFAULT).checkpoints().size());
  }

  private static <T extends Throwable> void rejected(
      GraphMachine machine, Instruction.Clone operation, Class<T> type) {
    Object key = machine.key();
    var graph = machine.graph();
    var events = machine.events();
    assertThrows(type, () -> machine.apply(operation));
    assertEquals(key, machine.key());
    assertEquals(graph, machine.graph());
    assertEquals(events, machine.events());
  }
}
