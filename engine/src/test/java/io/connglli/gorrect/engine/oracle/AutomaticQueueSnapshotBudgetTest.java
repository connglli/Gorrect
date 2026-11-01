package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutomaticQueueSnapshotBudgetTest {
  private static final WrapperId WRAPPER = new WrapperId(0, 1, 1, -1);

  @Test
  void queuedOnlyLiteralNullWrapperCostsFiveCellsAndChargesRemainCumulative() {
    var program =
        GirParser.parse(
            "gir 1; shape W weak; queue q W; program {"
                + " allocate-ref r W null q; enqueue-ref inserted r; release r; }");
    var machine = new GraphMachine(program, new OracleLimits(10, 10, 10, 9));
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    machine.apply((Instruction.Operation) program.body().get(0));
    frontier.register(machine.referenceValue("r", -1), "q");
    assertTrue(frontier.enqueue(WRAPPER, true));
    machine.enqueueResult((Instruction.EnqueueReference) program.body().get(1), true);
    machine.apply((Instruction.Operation) program.body().get(2));
    var bound = frontier.snapshotBound(machine.graph());
    assertEquals(
        new Graph(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(WRAPPER, new HeapReference(program.shapes().getFirst(), null)),
            Map.of("q", new HeapQueue(1, Set.of(WRAPPER)))),
        bound);
    // Snapshot + queue + member + two wrapper cells = five, independent of physical coverage.
    machine.chargeSnapshot(bound);
    var before = machine.key();
    assertThrows(OracleLimitException.class, () -> machine.chargeSnapshot(bound));
    assertEquals(before, machine.key());
    var afterConsumption = new ReferenceFrontier(frontier);
    assertTrue(afterConsumption.poll("q", WRAPPER));
    var empty = afterConsumption.snapshotBound(machine.graph());
    assertTrue(empty.wrappers().isEmpty());
    assertEquals(new HeapQueue(1, Set.of()), empty.queues().get("q"));
    machine.chargeSnapshot(empty); // A failed five-cell charge consumed none of the four remaining.
    machine.chargeSnapshot(empty);
    assertThrows(OracleLimitException.class, () -> machine.chargeSnapshot(empty));
  }

  @Test
  void accountingUnionRetainsConstructorEdgesWithoutChangingSemanticCandidatesOrRoots() {
    var node = new Shape("N", Shape.Kind.OBJECT, 0, 0);
    var weak = new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0);
    var target = ObjectId.initial(1);
    var model =
        new Graph(
            Map.of(target, new HeapObject(node, List.of(), List.of())),
            Map.of(),
            Map.of(),
            Map.of(WRAPPER, new HeapReference(weak, HeapId.object(target))),
            Map.of("q", new HeapQueue(1, Set.of())));
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, target, WRAPPER), "q");
    frontier.expand(model);
    var before = frontier.key();
    var bound = frontier.snapshotBound(model);
    // Pending and inserted alternatives supply one accounting union. Keeping the construction edge
    // deliberately bounds more storage than the cleared inserted alternative alone needs.
    assertEquals(Set.of(WRAPPER), bound.queues().get("q").members());
    assertTrue(bound.wrappers().containsKey(WRAPPER));
    assertTrue(bound.objects().containsKey(target));
    assertEquals(before, frontier.key());
    frontier.expand(model);
    assertEquals(before, frontier.key());
    assertTrue(frontier.acquire(WRAPPER, target));
    assertEquals(1, frontier.size());
    assertTrue(frontier.snapshotBound(model).wrappers().isEmpty());
  }

  @Test
  void boundWorkExhaustionDoesNotChangeFrontierOrChargeTheMachine() {
    var program = GirParser.parse("gir 1; shape W weak; queue q W; program {} ");
    var machine = new GraphMachine(program, new OracleLimits(1, 1, 1, 2));
    var frontier = new ReferenceFrontier(new HistoryLimits(1, 1));
    var before = machine.key();
    assertThrows(
        HistoryLimitException.class,
        () -> machine.chargeSnapshot(frontier.snapshotBound(machine.graph())));
    assertEquals(before, machine.key());
    machine.chargeSnapshot(machine.graph().accessible());
    assertThrows(
        OracleLimitException.class, () -> machine.chargeSnapshot(machine.graph().accessible()));
  }
}
