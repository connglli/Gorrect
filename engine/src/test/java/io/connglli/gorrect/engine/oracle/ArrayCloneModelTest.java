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
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArrayCloneModelTest {
  @Test
  void shallowCopiesReferencesAndDataWithoutAliasingArrayStorage() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; shape R refs 2; shape D data 2;
            object 1 N [null] [7]; object 2 R [1, null] [];
            object 3 D [] [-9, 9223372036854775807];
            root refs = 2; root data = 3; root copiedRefs = null; root copiedData = null;
            program {
              read-root rs R refs; clone rc rs;
              read-root ds D data; clone dc ds;
              write-root copiedRefs rc; write-root copiedData dc;
              write-ref rs 0 null; write-data dc 0 99;
              read-ref shared N rc 0; write-data shared 0 11;
              same-object distinct rs rc; read-data original ds 0;
            }
            """);
    var result = Oracle.interpret(program);
    var n = new Shape("N", Shape.Kind.OBJECT, 1, 1);
    var r = new Shape("R", Shape.Kind.REFERENCE_ARRAY, 2, 0);
    var d = new Shape("D", Shape.Kind.DATA_ARRAY, 0, 2);
    var node = ObjectId.initial(1);
    var refs = ObjectId.initial(2);
    var data = ObjectId.initial(3);
    var cloneRefs = new ObjectId(0, 2, 1);
    var cloneData = new ObjectId(0, 4, 1);
    var expected =
        new Graph(
            Map.of(
                node, new HeapObject(n, List.of(Value.Reference.NULL), List.of(11L)),
                refs,
                    new HeapObject(
                        r, List.of(Value.Reference.NULL, Value.Reference.NULL), List.of()),
                data, new HeapObject(d, List.of(), List.of(-9L, Long.MAX_VALUE)),
                cloneRefs,
                    new HeapObject(
                        r, List.of(Value.Reference.strong(node), Value.Reference.NULL), List.of()),
                cloneData, new HeapObject(d, List.of(), List.of(99L, Long.MAX_VALUE))),
            Map.of(
                "refs",
                Value.Reference.strong(refs),
                "data",
                Value.Reference.strong(data),
                "copiedRefs",
                Value.Reference.strong(cloneRefs),
                "copiedData",
                Value.Reference.strong(cloneData)),
            Map.of());
    assertEquals(expected, result.finalGraph());
    assertEquals(
        List.of(0L, -9L),
        result.events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.SCALAR_READ)
            .map(ExecutionTrace.Event::value)
            .toList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"refs", "data"})
  void emptyArraysAndRepeatedCloneSitesKeepDistinctIdentities(String kind) {
    var result =
        Oracle.interpret(
            GirParser.parse(
                "gir 1; shape E "
                    + kind
                    + " 0; object 1 E [] []; root source = 1; root saved = null; program {"
                    + " read-root original E source; repeat 3 { clone copy original; write-root"
                    + " saved copy; checkpoint; } }"));
    assertEquals(3, result.checkpoints().size());
    for (int index = 0; index < 3; index++) {
      var graph = result.checkpoints().get(index).graph();
      assertEquals(
          Value.Reference.strong(new ObjectId(0, 3, index + 1)), graph.roots().get("saved"));
      assertNotEquals(graph.roots().get("source"), graph.roots().get("saved"));
      assertEquals(2, graph.objects().size());
    }
    assertEquals(2, result.finalGraph().objects().size());
  }

  @Test
  void invalidOwnersAndDuplicateNamesRejectWithoutEffectsOrSkippedIdentity() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 2; shape N object 0 0; object 1 A [] [4, 5]; object 2 N [] [];"
                + " root arr = 1; root node = 2; root empty = null; program { read-root original A"
                + " arr; }");
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    machine.apply((Instruction.Operation) program.body().getFirst());
    rejected(machine, new Instruction.Clone(2, "original", "original"), GirException.class);
    rejected(machine, new Instruction.Clone(2, "copy", "missing"), GirException.class);
    machine.apply(new Instruction.ReadRoot(3, "nil", "A", "empty"));
    rejected(machine, new Instruction.Clone(2, "copy", "nil"), GirException.class);
    machine.apply(new Instruction.ReadRoot(4, "ordinary", "N", "node"));
    machine.apply(new Instruction.Release(5, "ordinary"));
    rejected(machine, new Instruction.Clone(2, "copy", "ordinary"), GirException.class);
    machine.apply(new Instruction.Clone(2, "copy", "original"));
    assertEquals(Value.Reference.strong(new ObjectId(0, 2, 1)), machine.bound("copy"));
  }

  @Test
  void eachAllocationBudgetRejectsBeforeCloneEffects() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 2; object 1 A [] [4, 5]; root arr = 1;"
                + " program { read-root original A arr; }");
    for (var limits :
        List.of(
            new OracleLimits(1, 10, 100, 1000),
            new OracleLimits(100, 1, 100, 1000),
            new OracleLimits(100, 10, 2, 1000))) {
      var machine = new GraphMachine(program, limits);
      machine.apply((Instruction.Operation) program.body().getFirst());
      rejected(machine, new Instruction.Clone(2, "copy", "original"), OracleLimitException.class);
    }
  }

  @Test
  void capacityPlanningIncludesBothConditionalCloneArmsAndTheirBindings() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 2; object 1 A [] [4, 5]; root arr = 1; program { read-root"
                + " original A arr; if-nonzero 1 { clone a original; checkpoint; } else { clone b"
                + " original; checkpoint; } }");
    var plan = HistoryPlan.plan(program, OracleLimits.DEFAULT);
    assertEquals(2, plan.checkpoints().size());
    assertEquals(2, plan.checkpoints().get(0).graph().handles().size());
    assertEquals(2, plan.checkpoints().get(1).graph().handles().size());
    assertThrows(
        OracleLimitException.class,
        () -> HistoryPlan.plan(program, new OracleLimits(100, 2, 100, 1000)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"object 2 N [weak(2)] [];", ""})
  void unsupportedSourceCannotBypassDirectHistoryChecksWithMissingEvidence(String references) {
    String parallel = references.isEmpty() ? " parallel { worker a {} worker b {} }" : "";
    var program =
        GirParser.parse(
            "gir 1; shape A data 1; shape N object 1 0; object 1 A [] [4]; "
                + references
                + " root arr = 1; program { read-root original A arr; clone copy original;"
                + parallel
                + " }");
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
    assertThrows(
        UnsupportedHistoryException.class,
        () ->
            HistoryOracle.check(
                program,
                new ExecutionTrace(1, false, List.of()),
                new ReferenceHistory(1, false, List.of()),
                List.of(),
                HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () ->
            ConcurrentHistory.check(
                program,
                new ExecutionTrace(1, false, List.of()),
                new ReferenceHistory(1, false, List.of()),
                List.of(),
                HistoryLimits.DEFAULT));
  }

  @Test
  void genericSequentialHistoryPathChecksStrongCloneIdentities() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 1; object 1 A [] [4]; root arr = 1; root saved = null; program {"
                + " read-root original A arr; clone copy original; write-root saved copy; }");
    var shape = new Shape("A", Shape.Kind.DATA_ARRAY, 0, 1);
    var initial = ObjectId.initial(1);
    var copy = new ObjectId(0, 2, 1);
    var expected =
        new Graph(
            Map.of(
                initial,
                new HeapObject(shape, List.of(), List.of(4L)),
                copy,
                new HeapObject(shape, List.of(), List.of(4L))),
            Map.of("arr", Value.Reference.strong(initial), "saved", Value.Reference.strong(copy)),
            Map.of());
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(3, ExecutionTrace.Kind.OPERATION, 0)));
    var history = new ReferenceHistory(1, true, List.of());
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        HistoryOracle.check(
                program,
                trace,
                history,
                List.of(new HistoryOracle.Observation(Optional.of(expected), List.of())),
                HistoryLimits.DEFAULT)
            .verdict());
    var wrong =
        new Graph(
            Map.of(
                initial,
                expected.objects().get(initial),
                copy,
                new HeapObject(shape, List.of(), List.of(99L))),
            expected.roots(),
            Map.of());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        HistoryOracle.check(
                program,
                trace,
                history,
                List.of(new HistoryOracle.Observation(Optional.of(wrong), List.of())),
                HistoryLimits.DEFAULT)
            .verdict());
  }

  @Test
  void concurrentBackendRejectsEvenSequentialCloneSourceBeforeEvidenceExit() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 0; object 1 A [] []; root arr = 1;"
                + " program { read-root original A arr; clone copy original; }");
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
    assertThrows(
        UnsupportedHistoryException.class,
        () ->
            ConcurrentHistory.check(
                program,
                new ExecutionTrace(1, false, List.of()),
                new ReferenceHistory(1, false, List.of()),
                List.of(),
                HistoryLimits.DEFAULT));
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
