package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticQueueFrontierTest {
  private static final Shape NODE = new Shape("N", Shape.Kind.OBJECT, 0, 0);
  private static final Shape WEAK = new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0);
  private static final Shape SOFT = new Shape("S", Shape.Kind.SOFT_REFERENCE, 0, 0);
  private static final ObjectId A = ObjectId.initial(1), B = ObjectId.initial(2);
  private static final WrapperId FIRST = new WrapperId(0, 1, 1, -1);
  private static final WrapperId SECOND = new WrapperId(0, 2, 1, -1);

  private record Fixture(Graph graph, ReferenceFrontier frontier) {}

  private static Fixture fixture(boolean alias, Shape first, Shape second, HistoryLimits limits) {
    var graph =
        new Graph(
            Map.of(
                A,
                new HeapObject(NODE, List.of(), List.of()),
                B,
                new HeapObject(NODE, List.of(), List.of())),
            Map.of(
                "first",
                Value.Reference.toWrapper(FIRST),
                "second",
                Value.Reference.toWrapper(SECOND)),
            Map.of(),
            Map.of(
                FIRST,
                new HeapReference(first, HeapId.object(A)),
                SECOND,
                new HeapReference(second, HeapId.object(alias ? A : B))),
            Map.of("a", new HeapQueue(1, Set.of()), "b", new HeapQueue(2, Set.of())));
    var frontier = new ReferenceFrontier(limits);
    frontier.register(new Value.Reference(first.strength(), A, FIRST), "a");
    frontier.register(new Value.Reference(second.strength(), alias ? A : B, SECOND), "b");
    return new Fixture(graph, frontier);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void candidateSetsMatchIndependentCohortAndDeliveryEnumeration(boolean alias) {
    for (var shape : List.of(SOFT, WEAK)) {
      var fixture = fixture(alias, shape, shape, HistoryLimits.DEFAULT);
      fixture.frontier().expand(fixture.graph());
      // Equal-strength aliases clear atomically; independent targets clear independently. For
      // every cleared set each wrapper can remain pending or become a member, without progress.
      assertEquals(
          expected(alias ? List.of(0, 3) : List.of(0, 1, 2, 3)),
          signatures(fixture.frontier()),
          shape + " alias=" + alias);
    }
  }

  @Test
  void weakClearingWaitsForTheSoftAliasWithoutErasingDeliveryChoices() {
    var fixture = fixture(true, SOFT, WEAK, HistoryLimits.DEFAULT);
    fixture.frontier().expand(fixture.graph());
    assertEquals(expected(List.of(0, 1, 3)), signatures(fixture.frontier()));
  }

  private static Set<String> expected(List<Integer> clearings) {
    var expected = new HashSet<String>();
    for (int cleared : clearings)
      for (int inserted = 0; inserted < 4; inserted++)
        if ((inserted & ~cleared) == 0)
          expected.add(cleared + ":" + (cleared & ~inserted) + ":0:" + inserted + ":" + inserted);
    return expected;
  }

  private static int mask(BitSet bits) {
    return bits.isEmpty() ? 0 : (int) bits.toLongArray()[0];
  }

  private static Set<String> signatures(ReferenceFrontier frontier) {
    var result = new HashSet<String>();
    for (var state : frontier.states())
      result.add(
          mask(state.cleared())
              + ":"
              + mask(state.pending())
              + ":"
              + mask(state.delivered())
              + ":"
              + mask(state.inserted())
              + ":"
              + mask(state.members()));
    return result;
  }

  @Test
  void programClearAndInitiallyNullConstructionCreateNoNotificationDebt() {
    var fixture = fixture(false, WEAK, WEAK, HistoryLimits.DEFAULT);
    fixture.frontier().programClear(FIRST);
    fixture.frontier().programClear(SECOND);
    fixture.frontier().expand(fixture.graph());
    assertEquals(Set.of("3:0:0:0:0"), signatures(fixture.frontier()));
    var nullReference = new ReferenceFrontier(HistoryLimits.DEFAULT);
    nullReference.register(new Value.Reference(Value.ReferenceKind.WEAK, null, FIRST), "a");
    assertEquals(Set.of("1:0:0:0:0"), signatures(nullReference));
  }

  @Test
  void clearPreservesExistingDebtAndNullPollingCanDelayDeliveryIndefinitely() {
    var fixture = fixture(false, WEAK, WEAK, HistoryLimits.DEFAULT);
    var frontier = fixture.frontier();
    frontier.expand(fixture.graph());
    assertTrue(frontier.observeCleared(FIRST, true));
    var before = frontier.key();
    frontier.programClear(FIRST);
    assertEquals(before, frontier.key());
    for (int repeat = 0; repeat < 8; repeat++) assertTrue(frontier.poll("a", null));
    assertTrue(frontier.states().stream().allMatch(state -> state.pending().get(0)));
    assertTrue(frontier.states().stream().noneMatch(state -> state.members().get(0)));
    assertTrue(frontier.poll("a", FIRST));
    assertTrue(frontier.states().stream().allMatch(state -> state.inserted().get(0)));
    assertTrue(frontier.states().stream().noneMatch(state -> state.members().get(0)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void handlerOrApiMayWinButConsumptionPermanentlyPreventsReinsertion(boolean apiWins) {
    var fixture = fixture(false, WEAK, WEAK, HistoryLimits.DEFAULT);
    var frontier = fixture.frontier();
    frontier.expand(fixture.graph());
    assertTrue(frontier.observeCleared(FIRST, true));
    assertTrue(frontier.enqueue(FIRST, apiWins));
    assertTrue(
        frontier.states().stream()
            .allMatch(
                state ->
                    state.cleared().get(0)
                        && state.inserted().get(0)
                        && state.members().get(0)
                        && !state.pending().get(0)));
    var inserted = frontier.key();
    assertTrue(frontier.enqueue(FIRST, false));
    assertEquals(inserted, frontier.key());
    assertTrue(frontier.poll("a", FIRST));
    assertNotEquals(inserted, frontier.key());
    frontier.expand(fixture.graph());
    assertTrue(frontier.enqueue(FIRST, false));
    assertTrue(frontier.states().stream().noneMatch(state -> state.members().get(0)));
    assertFalse(frontier.poll("a", FIRST));
  }

  @Test
  void falseNoQueueEnqueueClearsAndWrongQueueCannotSupplyMembers() {
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, A, FIRST), false);
    assertTrue(frontier.enqueue(FIRST, false));
    assertEquals(Set.of("1:0:0:0:0"), signatures(frontier));
    assertFalse(frontier.poll("a", FIRST));
    var fixture = fixture(false, WEAK, WEAK, HistoryLimits.DEFAULT);
    fixture.frontier().expand(fixture.graph());
    assertFalse(fixture.frontier().poll("b", FIRST));
  }

  @Test
  void semanticMemberRootsProtectWrapperChainsEvenWhenPhysicalMembershipIsUnknown() {
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, null, FIRST), "a");
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, null, SECOND, FIRST), false);
    assertTrue(frontier.enqueue(FIRST, true));
    var graph =
        new Graph(
            Map.of(),
            Map.of("witness", Value.Reference.toWrapper(SECOND)),
            Map.of(),
            Map.of(
                FIRST,
                new HeapReference(WEAK, null),
                SECOND,
                new HeapReference(WEAK, HeapId.wrapper(FIRST))),
            Map.of("a", new HeapQueue(1, Set.of())));
    frontier.expand(graph);
    assertTrue(frontier.states().stream().noneMatch(state -> state.cleared().get(1)));
    var corrupted = new HashMap<>(graph.wrappers());
    corrupted.put(SECOND, new HeapReference(WEAK, null));
    var partial =
        new Graph(
            Map.of(), graph.roots(), Map.of(), corrupted, Map.of("a", new HeapQueue(1, null)));
    assertFalse(frontier.matchCovered(graph, partial).isEmpty());
  }

  @Test
  void legacyPollCannotConsumeNamedQueueNotificationDebt() {
    var fixture = fixture(false, WEAK, WEAK, HistoryLimits.DEFAULT);
    fixture.frontier().expand(fixture.graph());
    assertFalse(fixture.frontier().poll(FIRST));
    assertTrue(fixture.frontier().poll("a", FIRST));
  }

  @Test
  void stateBudgetFailureLeavesThePriorJointFrontierUnchanged() {
    var fixture = fixture(false, WEAK, WEAK, new HistoryLimits(2, 10000));
    var before = fixture.frontier().key();
    assertThrows(HistoryLimitException.class, () -> fixture.frontier().expand(fixture.graph()));
    assertEquals(before, fixture.frontier().key());
  }

  @Test
  void apiBudgetFailureDoesNotPartiallyInsertOrClear() {
    var frontier = new ReferenceFrontier(new HistoryLimits(2, 10));
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, A, FIRST), "a");
    var before = frontier.states();
    assertThrows(HistoryLimitException.class, () -> frontier.enqueue(FIRST, true));
    assertEquals(before, frontier.states());
  }

  @Test
  void registrationBudgetFailureDoesNotInstallAnInstance() {
    var frontier = new ReferenceFrontier(new HistoryLimits(2, 1));
    assertThrows(
        HistoryLimitException.class,
        () -> frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, A, FIRST), "a"));
    assertFalse(frontier.known(FIRST));
    assertEquals(1, frontier.size());
  }
}
