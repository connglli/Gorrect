package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Independent lifetime/budget checks and differential execution without working-heap pruning. */
class ModelRetirementTest {
  private static final OracleLimits ROOM = new OracleLimits(1000, 100, 1000, 10000);

  @Test
  void retainsOuterAliasesAndTheirClosureButDropsDisconnectedCycles() {
    var program =
        parse(
            """
            shape N object 2 [int, long];
            object 1 N [2, null] [4294967297, 7];
            object 2 N [1, null] [8, 9];
            object 3 N [3, null] [11, 12];
            root live = 1; root saved = null;
            program {}
            """);
    var machine = new GraphMachine(program, ROOM, true);
    machine.apply(new Instruction.ReadRoot(1, "outer", "N", "live"));
    machine.apply(new Instruction.WriteRoot(2, "live", Operand.Null.INSTANCE));
    machine.enterScope();
    machine.apply(new Instruction.Allocate(3, "unused", "N"));
    machine.apply(new Instruction.Release(4, "unused"));
    assertEquals(4, machine.graph().objects().size());

    machine.apply(new Instruction.Checkpoint(5));
    assertEquals(
        Set.of(ObjectId.initial(1), ObjectId.initial(2)), machine.graph().objects().keySet());
    var earlier = machine.checkpoints().getFirst().graph();
    assertEquals(1, earlier.objects().get(ObjectId.initial(1)).data().getFirst());

    machine.leaveScope();
    machine.apply(new Instruction.Release(6, "outer"));
    machine.apply(new Instruction.Checkpoint(7));
    assertTrue(machine.graph().objects().isEmpty());
    assertEquals(2, machine.graph().roots().size());
    assertEquals(2, earlier.objects().size());
    assertEquals(earlier, machine.checkpoints().getFirst().graph());
  }

  @Test
  void failedStepOrSnapshotChargeCannotRetireAnythingOrAdvanceVisits() {
    var program = parse("shape E object 0 0; object 1 E [] []; program {}");
    for (var limits : List.of(new OracleLimits(0, 1, 0, 100), new OracleLimits(100, 1, 0, 0))) {
      var machine = new GraphMachine(program, limits, true);
      var before = machine.key();
      assertThrows(OracleLimitException.class, () -> machine.apply(new Instruction.Checkpoint(1)));
      assertEquals(before, machine.key());
      assertEquals(1, machine.graph().objects().size());
      assertTrue(machine.events().isEmpty());
      assertTrue(machine.checkpoints().isEmpty());
    }
  }

  @Test
  void allocationFailureDoesNotTriggerEmergencyRetirement() {
    var machine =
        new GraphMachine(
            parse("shape E object 0 0; object 1 E [] []; program {}"),
            new OracleLimits(100, 1, 0, 100),
            true);
    var before = machine.key();
    assertThrows(
        OracleLimitException.class, () -> machine.apply(new Instruction.Allocate(1, "a", "E")));
    assertEquals(before, machine.key());
    assertEquals(1, machine.graph().objects().size());

    machine.apply(new Instruction.Checkpoint(2));
    assertTrue(machine.graph().objects().isEmpty());
    before = machine.key();
    assertThrows(
        OracleLimitException.class, () -> machine.apply(new Instruction.Allocate(1, "a", "E")));
    assertEquals(before, machine.key());
    var fork = new GraphMachine(machine);
    assertEquals(machine.key(), fork.key());
    assertThrows(
        OracleLimitException.class, () -> fork.apply(new Instruction.Allocate(1, "a", "E")));
    assertEquals(machine.key(), fork.key());
  }

  @Test
  void repeatedRetiredSitesKeepFreshIdentitiesAndTheOriginalAllocationAllowance() {
    var machine =
        new GraphMachine(
            parse("shape E object 0 0; program {}"), new OracleLimits(100, 3, 0, 100), true);
    for (int visit = 1; visit <= 3; visit++) {
      machine.enterScope();
      machine.apply(new Instruction.Allocate(1, "a", "E"));
      machine.apply(new Instruction.IdentityHash(2, new Operand.Name("a")));
      assertEquals(
          new ObjectId(0, 1, visit), machine.identityHashes().getLast().subject().object());
      machine.leaveScope();
      machine.apply(new Instruction.Checkpoint(3));
      assertTrue(machine.graph().objects().isEmpty());
    }
    machine.enterScope();
    var before = machine.key();
    assertThrows(
        OracleLimitException.class, () -> machine.apply(new Instruction.Allocate(1, "a", "E")));
    assertEquals(before, machine.key());
    assertEquals(3, machine.identityHashes().size());
  }

  @Test
  void clonedArraysConsumeCumulativeObjectsAndSlotsAfterTheirRecordsRetire() {
    for (var limits :
        List.of(new OracleLimits(100, 2, 100, 100), new OracleLimits(100, 100, 4, 100))) {
      var machine =
          new GraphMachine(
              parse("shape A data 2; object 1 A [] [7, 9]; root base = 1; program {}"),
              limits,
              true);
      machine.apply(new Instruction.ReadRoot(1, "source", "A", "base"));
      machine.enterScope();
      machine.apply(new Instruction.Clone(2, "copy", "source"));
      machine.leaveScope();
      machine.apply(new Instruction.Checkpoint(3));
      assertEquals(1, machine.graph().objects().size());
      var before = machine.key();
      assertThrows(
          OracleLimitException.class,
          () -> machine.apply(new Instruction.Clone(2, "next", "source")));
      assertEquals(before, machine.key());
    }
  }

  @Test
  void keysDistinguishDifferentSpentAllocationAllowancesAfterRetirement() {
    var program = parse("shape E object 0 0; root empty = null; program {}");
    var limits = new OracleLimits(100, 1, 0, 100);
    var allocated = new GraphMachine(program, limits, true);
    var unallocated = new GraphMachine(program, limits, true);
    allocated.apply(new Instruction.Allocate(1, "a", "E"));
    unallocated.apply(new Instruction.ReadRoot(1, "a", "E", "empty"));
    // Match incidental site bookkeeping while giving the two states different spent allowances.
    unallocated.visit(1);
    for (var machine : List.of(allocated, unallocated)) {
      machine.apply(new Instruction.Release(2, "a"));
      machine.apply(new Instruction.Checkpoint(3));
    }
    assertEquals(allocated.graph(), unallocated.graph());
    assertEquals(allocated.steps(), unallocated.steps());
    assertNotEquals(allocated.key(), unallocated.key());
    assertThrows(
        OracleLimitException.class,
        () -> allocated.apply(new Instruction.Allocate(4, "fresh", "E")));
    unallocated.apply(new Instruction.Allocate(4, "fresh", "E"));
  }

  @Test
  void optimizationAdmissionExaminesUnusedDeclarationsAndSkippedSyntax() {
    assertTrue(Oracle.retirementEligible(parse("shape E object 0 0; program { checkpoint; }")));
    for (String source :
        List.of(
            "shape W weak; program {}",
            "shape W weak; queue q W; program {}",
            "shape N object 0 0; root r = null; program { repeat 0 { read-volatile-root h N r; } }",
            "shape N object 0 0; root r = null; program { if-nonzero 0 { compare-set-root ok r null"
                + " null; } }",
            "program { parallel { worker a {} worker b {} } }",
            "shape N object 0 0; root r = null; program { read-root owner N r; repeat 0 {"
                + " monitor owner { allocate child N; checkpoint; } } }",
            "shape N object 0 0; root r = null; program { read-root owner N r; if-nonzero 0 {"
                + " monitor owner { checkpoint; } } }")) {
      assertFalse(Oracle.retirementEligible(parse(source)), source);
    }
  }

  @Test
  void defaultMachineAndIneligibleExactExecutionRemainUnoptimized() {
    var program = parse("shape E object 0 0; object 1 E [] []; program {}");
    var machine = new GraphMachine(program, ROOM);
    machine.apply(new Instruction.Checkpoint(1));
    assertEquals(1, machine.graph().objects().size());
    var parallel = parse("program { parallel { worker a {} worker b {} } checkpoint; }");
    assertEquals(Oracle.interpret(parallel, ROOM, false), Oracle.interpret(parallel, ROOM, true));
  }

  @Test
  void monitorsKeepUnprunedRecordsRootsAndAllocationCheckpointBudgets() {
    var program =
        parse(
            """
            shape N object 0 1;
            object 1 N [] [7]; object 2 N [] [9];
            root main = 1; root saved = null;
            program {
              read-root owner N main;
              monitor owner {
                allocate child N;
                write-root saved child;
                release child;
                checkpoint;
              }
              checkpoint;
            }
            """);
    assertFalse(Oracle.retirementEligible(program));
    var result = Oracle.interpret(program, ROOM);
    assertEquals(2, result.finalGraph().objects().size());
    assertEquals(ObjectId.initial(1), result.finalGraph().roots().get("main").target());
    assertEquals(new ObjectId(0, 3, 1), result.finalGraph().roots().get("saved").target());
    assertEquals(2, result.checkpoints().size());
    var machine = new GraphMachine(program, ROOM, Oracle.retirementEligible(program));
    Oracle.execute(program.body(), machine);
    assertEquals(3, machine.graph().objects().size());
    assertTrue(machine.graph().objects().containsKey(ObjectId.initial(2)));
    assertEquals(result.checkpoints(), machine.checkpoints());
    assertEquals(result.events(), machine.events());
    for (int objects = 0; objects <= 4; objects++)
      for (int slots : List.of(0, 2, 3))
        for (int steps : List.of(0, 2, 5, 8, 9))
          for (int snapshots : List.of(0, 7, 100)) {
            var limits = new OracleLimits(steps, objects, slots, snapshots);
            assertEquals(
                outcome(program, limits, false), outcome(program, limits, true), limits.toString());
          }
  }

  @Test
  void physicalGarbageRemainsAllowedButCannotReplaceAnObservedRoot() {
    var program =
        parse("shape N object 0 1; object 1 N [] [7]; root r = null; program { checkpoint; }");
    var expected = Oracle.interpret(program, ROOM).finalGraph();
    var oldIdentity = ObjectId.initial(1);
    // Dead records have no payload obligation: the observer must retain, not repair/filter, them.
    var actualObjects =
        Map.of(oldIdentity, new HeapObject(program.shapes().getFirst(), List.of(), List.of(99L)));
    var retainedGarbage = new Graph(actualObjects, expected.roots(), expected.handles());
    assertEquals(
        ExactVerifier.Verdict.SATISFIED,
        ExactVerifier.verify(expected, retainedGarbage, true).verdict());
    var wrongRoot =
        new Graph(
            actualObjects, Map.of("r", Value.Reference.strong(oldIdentity)), expected.handles());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED, ExactVerifier.verify(expected, wrongRoot, true).verdict());
  }

  @Test
  void differentialExecutionPreservesResultsAndBudgetFailureBoundaries() {
    var program =
        parse(
            """
            shape N object 2 [int, long]; shape R refs 3;
            object 1 N [2, null] [4294967297, 7];
            object 2 N [1, null] [42, 9];
            object 3 R [1, null, 1] [];
            object 4 N [4, null] [99, 0];
            root main = 3; root saved = null;
            program {
              repeat 4 {
                scope {
                  read-root arr R main;
                  read-ref node N arr 0;
                  read-ref alias N arr 2;
                  identity-hash node;
                  read-data old node 0;
                  write-data node 0 4294967295;
                  allocate fresh N;
                  write-ref fresh 0 node;
                  write-root saved fresh;
                  clone copied arr;
                  copy-array arr 0 copied 1 2;
                  write-ref copied 0 fresh;
                  checkpoint;
                }
                checkpoint;
                write-root saved null;
              }
              write-root main null;
              checkpoint;
            }
            """);
    assertTrue(Oracle.retirementEligible(program));
    assertTrue(Oracle.interpret(program, ROOM).finalGraph().objects().isEmpty());
    for (int objects = 0; objects <= 13; objects++)
      for (int slots : List.of(0, 12, 30, 100))
        for (int steps : List.of(0, 5, 20, 200))
          for (int snapshots : List.of(0, 30, 1000)) {
            var limits = new OracleLimits(steps, objects, slots, snapshots);
            assertEquals(
                outcome(program, limits, false), outcome(program, limits, true), limits.toString());
          }
  }

  private record Failure(Class<?> type, String message) {}

  private static Object outcome(GirProgram program, OracleLimits limits, boolean retirement) {
    try {
      return Oracle.interpret(program, limits, retirement);
    } catch (GirException | OracleLimitException failure) {
      return new Failure(failure.getClass(), failure.getMessage());
    }
  }

  private static GirProgram parse(String source) {
    return GirParser.parse("gir 1; " + source);
  }
}
