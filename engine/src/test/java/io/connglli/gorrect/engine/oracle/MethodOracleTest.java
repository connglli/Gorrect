package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MethodOracleTest {
  private static final String HEADER = "gir 1; shape N object 1 1; root saved=null; ";

  @Test
  void nestedDiamondRepeatedCallsPreserveAliasesNullAndAllocationOccurrences() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method leaf(ref N node, long value) {
                  allocate local N; write-data local 0 value; collect;
                  if-present node { write-ref node 0 local; }
                }
                method left(ref N node) { call leaf(node, 7); }
                method right(ref N node) { call leaf(node, 42); }
                method diamond(ref N node) { call left(node); call right(node); }
                program { allocate callerOnly N; allocate node N;
                  call leaf(null, -1); repeat 2 { call diamond(node); }
                  write-ref callerOnly 0 node; write-root saved callerOnly; checkpoint;
                }
                """);
    var result = Oracle.interpret(program);
    var graph = result.finalGraph();
    var caller = graph.roots().get("saved").target();
    var node = graph.objects().get(caller).references().getFirst().target();
    var published = graph.objects().get(node).references().getFirst().target();
    assertEquals(new ObjectId(0, 1, 5), published);
    assertEquals(42L, graph.objects().get(published).data().getFirst());
    assertEquals(3, graph.objects().size());
    assertEquals(
        11,
        result.events().stream().filter(e -> e.kind() == ExecutionTrace.Kind.CALL_ENTER).count());
    assertEquals(
        11,
        result.events().stream().filter(e -> e.kind() == ExecutionTrace.Kind.CALL_EXIT).count());
    assertEquals(2, result.checkpoints().getFirst().graph().handles().size());
  }

  @Test
  void suspendedCallersRemainRootsWhileCalleeLookupStopsAtItsParametersAndLocals() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method f(ref N passed) { allocate local N; }
                program { allocate privateObject N; allocate passed N; call f(passed); }
                """);
    var machine = new GraphMachine(program, OracleLimits.DEFAULT, true);
    machine.apply((Instruction.Allocate) program.body().get(0));
    machine.apply((Instruction.Allocate) program.body().get(1));
    var method = machine.enterCall((Instruction.Call) program.body().get(2));
    machine.apply((Instruction.Allocate) method.body().getFirst());
    assertEquals(4, machine.graph().handles().size());
    assertEquals(3, machine.graph().required().objects().size());
    assertThrows(GirException.class, () -> machine.bound("privateObject"));
    machine.leaveCall();
    assertEquals(2, machine.graph().handles().size());
    assertEquals(2, machine.graph().required().objects().size());
  }

  @Test
  void entryRejectsAllBadArgumentsBeforeAnyMarkerCounterOrFrameEffect() {
    var program =
        GirParser.parse(
            HEADER
                + """
                shape M object 1 1; method f(ref N node, long n) {}
                program { allocate n N; allocate wrong M; call f(n, 7); }
                """);
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    Oracle.execute(program.body().subList(0, 2), machine);
    for (var arguments :
        List.of(
            List.<Operand>of(new Operand.Name("wrong"), new Operand.Literal(7)),
            List.<Operand>of(new Operand.Name("n"), Operand.Null.INSTANCE),
            List.<Operand>of(new Operand.Name("missing"), new Operand.Literal(7)),
            List.<Operand>of(new Operand.Name("n")))) {
      var key = machine.key();
      var events = machine.events();
      assertThrows(
          GirException.class, () -> machine.enterCall(new Instruction.Call(3, "f", arguments)));
      assertEquals(key, machine.key());
      assertEquals(events, machine.events());
    }
  }

  @Test
  void bodyAndExitBudgetFailuresKeepPrefixAndEffectsButAlwaysRestoreCaller() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method f(ref N node) { write-data node 0 7; }
                program { allocate node N; call f(node); }
                """);
    for (int allowance : List.of(1, 2, 3)) {
      var machine = new GraphMachine(program, new OracleLimits(allowance, 10, 10, 100));
      machine.apply((Instruction.Allocate) program.body().getFirst());
      assertThrows(
          OracleLimitException.class, () -> Oracle.execute(program.body().subList(1, 2), machine));
      assertEquals(1, machine.graph().handles().size());
      assertEquals(allowance, machine.steps());
      assertEquals(
          allowance == 3 ? 7L : 0L,
          machine.graph().objects().values().iterator().next().data().getFirst());
      assertTrue(
          machine.events().stream()
              .noneMatch(event -> event.kind() == ExecutionTrace.Kind.CALL_EXIT));
      assertTrue(machine.bound("node") != null);
    }
  }

  @Test
  void invalidBodyPreservesPriorWritesAndCleansNestedFrames() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method leaf(ref N node) { write-data node 0 7; read-ref missing N node 0; write-data missing 0 9; }
                method outer(ref N node) { call leaf(node); }
                program { allocate node N; call outer(node); }
                """);
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    assertThrows(GirException.class, () -> Oracle.execute(program.body(), machine));
    assertEquals(1, machine.graph().handles().size());
    assertEquals(7L, machine.graph().objects().values().iterator().next().data().getFirst());
    assertTrue(
        machine.events().stream()
            .noneMatch(event -> event.kind() == ExecutionTrace.Kind.CALL_EXIT));
  }

  @Test
  void completeExactTraceRejectsMissingEmptyCallsBranchesAndAdditionalMarkers() {
    var program =
        GirParser.parse(
            HEADER + "method f() {} program {if-nonzero 0 {call f();} else {call f();}}");
    var result = Oracle.interpret(program);
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        ExactTraceVerifier.verify(new ExecutionTrace(17, true, result.events()), result));
    for (int missing = 0; missing < result.events().size(); missing++) {
      var events = new ArrayList<>(result.events());
      events.remove(missing);
      assertEquals(
          ExactVerifier.Verdict.VIOLATED,
          ExactTraceVerifier.verify(new ExecutionTrace(17, true, events), result));
    }
    var extra = new ArrayList<>(result.events());
    extra.add(new ExecutionTrace.Event(3, ExecutionTrace.Kind.CALL_EXIT, 0));
    assertEquals(
        ExactVerifier.Verdict.VIOLATED,
        ExactTraceVerifier.verify(new ExecutionTrace(17, true, extra), result));
    assertEquals(
        ExactVerifier.Verdict.INCONCLUSIVE,
        ExactTraceVerifier.verify(
            new ExecutionTrace(17, false, result.events().subList(0, 2)), result));
    assertEquals(5, HistoryPlan.plan(program, OracleLimits.DEFAULT).events().size());
  }
}
