package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceFrontierTest {
  private static final ObjectId OWNER = ObjectId.initial(1),
      A = ObjectId.initial(2),
      B = ObjectId.initial(3);
  private static final Shape HOLDER = new Shape("Holder", Shape.Kind.OBJECT, 3, 0);
  private static final Shape NODE = new Shape("Node", Shape.Kind.OBJECT, 1, 0);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void matchesAnExhaustiveModelWithExplicitReferenceObjects(boolean arrays) {
    // The independent model enumerates retained wrapper roots, classifies reachability with
    // explicit wrapper vertices, and enumerates every legal atomic clearing choice.
    for (int kinds = 0; kinds < 8; kinds++)
      for (int targets = 0; targets < 8; targets++)
        for (int edges = 0; edges < 9; edges++)
          for (int roots = 0; roots < 8; roots++) {
            var slots = new ArrayList<Value.Reference>();
            for (int index = 0; index < 3; index++)
              slots.add(
                  new Value.Reference(
                      (kinds & (1 << index)) == 0
                          ? Value.ReferenceKind.SOFT
                          : Value.ReferenceKind.WEAK,
                      (targets & (1 << index)) == 0 ? A : B,
                      new WrapperId(0, 0, 1, index)));
            var named = new HashMap<String, Value.Reference>();
            if ((roots & 4) != 0) named.put("owner", Value.Reference.strong(OWNER));
            if ((roots & 1) != 0) named.put("a", Value.Reference.strong(A));
            if ((roots & 2) != 0) named.put("b", Value.Reference.strong(B));
            var graph =
                new Graph(
                    Map.of(
                        OWNER,
                        new HeapObject(
                            arrays ? new Shape("Holder", Shape.Kind.REFERENCE_ARRAY, 3, 0) : HOLDER,
                            slots,
                            List.of()),
                        A,
                        node(edges % 3, arrays),
                        B,
                        node(edges / 3, arrays)),
                    named,
                    Map.of());
            var frontier = new ReferenceFrontier(new HistoryLimits(1000, 100000));
            slots.forEach(frontier::register);
            frontier.expand(graph);
            var actual = new HashSet<Integer>();
            for (var state : frontier.states())
              actual.add(state.cleared().isEmpty() ? 0 : (int) state.cleared().toLongArray()[0]);
            assertEquals(
                exhaustive(graph, slots),
                actual,
                "kinds=" + kinds + " targets=" + targets + " edges=" + edges + " roots=" + roots);
          }
  }

  @Test
  void checksMixedPathsThroughReferenceObjectsInReferents() {
    for (var outerKind : List.of(Value.ReferenceKind.SOFT, Value.ReferenceKind.WEAK))
      for (var innerKind : List.of(Value.ReferenceKind.SOFT, Value.ReferenceKind.WEAK)) {
        var a = new Value.Reference(outerKind, A, new WrapperId(0, 0, 1, 0));
        var b = new Value.Reference(outerKind, B, new WrapperId(0, 0, 1, 1));
        var nested = new Value.Reference(innerKind, B, new WrapperId(0, 0, 2, 0));
        var slots = List.of(a, b, nested);
        var graph =
            new Graph(
                Map.of(
                    OWNER,
                    new HeapObject(HOLDER, List.of(a, b, Value.Reference.NULL), List.of()),
                    A,
                    new HeapObject(NODE, List.of(nested), List.of()),
                    B,
                    node(0)),
                Map.of("owner", Value.Reference.strong(OWNER)),
                Map.of());
        var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
        slots.forEach(frontier::register);
        frontier.expand(graph);
        var actual = new HashSet<Integer>();
        for (var state : frontier.states())
          actual.add(state.cleared().isEmpty() ? 0 : (int) state.cleared().toLongArray()[0]);
        assertEquals(exhaustive(graph, slots), actual, outerKind + "/" + innerKind);
      }
  }

  private static int vertex(ObjectId id) {
    return id.equals(OWNER) ? 0 : id.equals(A) ? 1 : 2;
  }

  private static HeapObject node(int target) {
    return node(target, false);
  }

  private static HeapObject node(int target, boolean array) {
    return new HeapObject(
        array ? new Shape("Node", Shape.Kind.REFERENCE_ARRAY, 1, 0) : NODE,
        List.of(target == 0 ? Value.Reference.NULL : Value.Reference.strong(target == 1 ? A : B)),
        List.of());
  }

  private static Set<Integer> exhaustive(Graph graph, List<Value.Reference> slots) {
    var found = new HashSet<Integer>();
    found.add(0);
    boolean changed;
    do {
      changed = false;
      for (int cleared : Set.copyOf(found))
        for (int retained = 0; retained < (1 << slots.size()); retained++) {
          int count = 3 + slots.size();
          boolean[][] edge = new boolean[count][count];
          int[] strength = new int[count];
          for (var object : graph.objects().entrySet()) {
            for (var value : object.getValue().references()) {
              if (value.kind() == Value.ReferenceKind.STRONG) {
                if (value.target() != null)
                  edge[vertex(object.getKey())][vertex(value.target())] = true;
              } else {
                int index = slots.indexOf(value);
                edge[vertex(object.getKey())][3 + index] = true;
                strength[3 + index] = value.kind() == Value.ReferenceKind.SOFT ? 1 : 2;
                if ((cleared & (1 << index)) == 0 && value.target() != null)
                  edge[3 + index][vertex(value.target())] = true;
              }
            }
          }
          boolean[] root = new boolean[count];
          graph
              .roots()
              .values()
              .forEach(
                  value -> {
                    if (value.target() != null) root[vertex(value.target())] = true;
                  });
          for (int i = 0; i < slots.size(); i++) root[3 + i] = (retained & (1 << i)) != 0;
          boolean[][] reach = new boolean[3][];
          for (int level = 0; level < 3; level++)
            reach[level] = reachable(edge, strength, root, level);
          for (int seed = 0; seed < 3; seed++)
            for (int kind = 1; kind <= 2; kind++) {
              if (!reach[kind][seed] || reach[kind - 1][seed]) continue;
              // Find every same-strength referent with an eligible path to the seed.
              int next = cleared;
              for (int i = 0; i < slots.size(); i++) {
                int target = vertex(slots.get(i).target());
                if (strength[3 + i] != kind || !reach[kind][target] || reach[kind - 1][target])
                  continue;
                boolean[] start = new boolean[count];
                start[target] = true;
                if (reachable(edge, strength, start, kind - 1)[seed]) next |= 1 << i;
              }
              changed |= found.add(next);
            }
        }
    } while (changed);
    return found;
  }

  private static boolean[] reachable(
      boolean[][] edges, int[] strength, boolean[] roots, int level) {
    boolean[] reached = roots.clone();
    boolean changed;
    do {
      changed = false;
      for (int source = 0; source < edges.length; source++)
        if (reached[source] && strength[source] <= level)
          for (int target = 0; target < edges.length; target++)
            if (edges[source][target] && !reached[target]) {
              reached[target] = true;
              changed = true;
            }
    } while (changed);
    return reached;
  }

  @Test
  void distinguishesExplicitClearDelayedDeliveryAndReplacement() {
    var old = new Value.Reference(Value.ReferenceKind.WEAK, A, new WrapperId(0, 0, 1, 0));
    var graph =
        new Graph(
            Map.of(
                OWNER,
                new HeapObject(
                    HOLDER, List.of(old, Value.Reference.NULL, Value.Reference.NULL), List.of()),
                A,
                node(0)),
            Map.of("r", Value.Reference.strong(OWNER)),
            Map.of());
    var explicit = new ReferenceFrontier(HistoryLimits.DEFAULT);
    explicit.register(old);
    explicit.programClear(old.wrapper());
    assertFalse(explicit.poll(old.wrapper()));
    var gc = new ReferenceFrontier(HistoryLimits.DEFAULT);
    gc.register(old);
    gc.expand(graph);
    assertTrue(gc.observeCleared(old.wrapper(), true));
    assertTrue(gc.poll(null));
    assertTrue(gc.poll(old.wrapper()));
    assertFalse(gc.poll(old.wrapper()));
    var replaced = new ReferenceFrontier(HistoryLimits.DEFAULT);
    replaced.register(old);
    replaced.programClear(old.wrapper());
    var fresh = new Value.Reference(Value.ReferenceKind.WEAK, A, new WrapperId(0, 9, 1, -1));
    replaced.register(fresh);
    assertTrue(replaced.acquire(fresh.wrapper(), A));
    assertFalse(replaced.acquire(old.wrapper(), A));
  }

  @Test
  void reportsExhaustedSearchWithoutAValidationVerdict() {
    var slot = new Value.Reference(Value.ReferenceKind.WEAK, A, new WrapperId(0, 0, 1, 0));
    var graph =
        new Graph(
            Map.of(
                OWNER,
                new HeapObject(
                    HOLDER, List.of(slot, Value.Reference.NULL, Value.Reference.NULL), List.of()),
                A,
                node(0)),
            Map.of("r", Value.Reference.strong(OWNER)),
            Map.of());
    var frontier = new ReferenceFrontier(new HistoryLimits(1, 100));
    frontier.register(slot);
    assertThrows(HistoryLimitException.class, () -> frontier.expand(graph));
  }
}
