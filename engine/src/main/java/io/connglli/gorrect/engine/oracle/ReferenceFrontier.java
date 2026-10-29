package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GC changes only wrapper state. Ordinary graph mutations are shared across the surviving frontier.
 */
final class ReferenceFrontier {
  record Instance(WrapperId id, Value.ReferenceKind kind, ObjectId target) {}

  record State(BitSet cleared, BitSet pending, BitSet delivered) {}

  private final HistoryLimits limits;
  private final List<Instance> instances = new ArrayList<>();
  private final Map<WrapperId, Integer> indices = new HashMap<>();
  private Set<State> states = new LinkedHashSet<>();
  private long work;

  ReferenceFrontier(HistoryLimits limits) {
    this.limits = limits;
    states.add(new State(new BitSet(), new BitSet(), new BitSet()));
  }

  void register(Value.Reference value) {
    if (value.kind() == Value.ReferenceKind.STRONG) return;
    if (indices.containsKey(value.wrapper()))
      throw new IllegalArgumentException("Duplicate model wrapper identity");
    int index = instances.size();
    indices.put(value.wrapper(), index);
    instances.add(new Instance(value.wrapper(), value.kind(), value.target()));
    if (value.target() == null) {
      var next = new LinkedHashSet<State>();
      for (var state : states) {
        var cleared = copy(state.cleared());
        cleared.set(index);
        next.add(new State(cleared, state.pending(), state.delivered()));
      }
      states = next;
    }
  }

  /** Includes arbitrary legal GC activity between observations, without requiring any clearing. */
  void expand(Graph graph) {
    var strong = reach(graph, null, Set.of(), 0);
    var closure = new LinkedHashSet<>(states);
    var queue = new ArrayDeque<>(states);
    while (!queue.isEmpty()) {
      var state = queue.removeFirst();
      for (int index = 0; index < instances.size(); index++) {
        spend();
        var instance = instances.get(index);
        if (state.cleared().get(index)
            || instance.target() == null
            || strong.contains(instance.target())) continue;
        var soft = reach(graph, state, Set.of(), 1);
        if (instance.kind() == Value.ReferenceKind.WEAK && soft.contains(instance.target()))
          continue;
        // A reference object may outlive its owner. Retaining that wrapper does not create a
        // strong referent root; it supplies only its own soft/weak edge for this transition.
        var eligible =
            reach(
                graph,
                state,
                Set.of(instance.target()),
                instance.kind() == Value.ReferenceKind.SOFT ? 1 : 2);
        eligible.removeAll(instance.kind() == Value.ReferenceKind.SOFT ? strong : soft);
        var cohort =
            reverse(
                graph,
                state,
                instance.target(),
                eligible,
                instance.kind() == Value.ReferenceKind.WEAK);
        var cleared = copy(state.cleared());
        var pending = copy(state.pending());
        spend(instances.size());
        for (int member = 0; member < instances.size(); member++) {
          var value = instances.get(member);
          if (value.kind() == instance.kind()
              && cohort.contains(value.target())
              && !cleared.get(member)) {
            cleared.set(member);
            pending.set(member);
          }
        }
        var next = new State(cleared, pending, state.delivered());
        if (closure.add(next)) {
          if (closure.size() > limits.states())
            throw new HistoryLimitException("Reference-history state budget exhausted");
          queue.addLast(next);
        }
      }
    }
    states = closure;
  }

  boolean acquire(WrapperId id, ObjectId target) {
    int index = index(id);
    var instance = instances.get(index);
    states.removeIf(
        state ->
            target == null
                ? !state.cleared().get(index)
                : state.cleared().get(index) || !target.equals(instance.target()));
    return !states.isEmpty();
  }

  boolean observeCleared(WrapperId id, boolean cleared) {
    int index = index(id);
    states.removeIf(state -> state.cleared().get(index) != cleared);
    return !states.isEmpty();
  }

  void programClear(WrapperId id) {
    int index = index(id);
    var next = new LinkedHashSet<State>();
    for (var state : states) {
      var cleared = copy(state.cleared());
      cleared.set(index);
      next.add(new State(cleared, state.pending(), state.delivered()));
    }
    states = next;
  }

  boolean poll(WrapperId id) {
    if (id == null) return true; // Queue delivery can lag clearing by an unbounded interval.
    Integer index = indices.get(id);
    if (index == null) return false;
    var next = new LinkedHashSet<State>();
    for (var state : states)
      if (state.pending().get(index) && !state.delivered().get(index)) {
        var pending = copy(state.pending());
        var delivered = copy(state.delivered());
        pending.clear(index);
        delivered.set(index);
        next.add(new State(state.cleared(), pending, delivered));
      }
    states = next;
    return !states.isEmpty();
  }

  Instance instance(WrapperId id) {
    return instances.get(index(id));
  }

  boolean known(WrapperId id) {
    return indices.containsKey(id);
  }

  Set<State> states() {
    return Set.copyOf(states);
  }

  List<String> match(Graph model, Graph observed) {
    var next = new LinkedHashSet<State>();
    List<String> problems = List.of("No legal clearing history matches the snapshot");
    for (var state : states) {
      spend();
      var result = ExactVerifier.verifyAccessible(view(model, state), observed, true);
      if (result.verdict() == ExactVerifier.Verdict.SATISFIED) next.add(state);
      else problems = result.differences();
    }
    states = next;
    return next.isEmpty() ? problems : List.of();
  }

  private Graph view(Graph graph, State state) {
    var objects = new HashMap<ObjectId, HeapObject>();
    graph
        .objects()
        .forEach(
            (id, object) -> {
              spend(1L + object.shape().slots());
              var values =
                  object.references().stream()
                      .map(
                          value ->
                              value.kind() != Value.ReferenceKind.STRONG
                                      && state.cleared().get(index(value.wrapper()))
                                  ? new Value.Reference(value.kind(), null, value.wrapper())
                                  : value)
                      .toList();
              objects.put(id, new HeapObject(object.shape(), values, object.data()));
            });
    return new Graph(objects, graph.roots(), graph.handles());
  }

  private Set<ObjectId> reach(Graph graph, State state, Set<ObjectId> additional, int strength) {
    var found = new HashSet<ObjectId>();
    var queue = new ArrayDeque<ObjectId>(additional);
    spend(1L + graph.roots().size() + graph.handles().size());
    graph
        .roots()
        .values()
        .forEach(
            value -> {
              if (value.target() != null) queue.addLast(value.target());
            });
    graph
        .handles()
        .values()
        .forEach(
            value -> {
              if (value.target() != null) queue.addLast(value.target());
            });
    while (!queue.isEmpty()) {
      var id = queue.removeFirst();
      if (!found.add(id)) continue;
      var object = graph.objects().get(id);
      if (object == null) throw new IllegalArgumentException("Missing model referent " + id);
      spend(1L + object.references().size());
      for (var value : object.references())
        if (value.target() != null
            && value.kind().ordinal() <= strength
            && (value.kind() == Value.ReferenceKind.STRONG
                || !state.cleared().get(index(value.wrapper())))) queue.addLast(value.target());
    }
    return found;
  }

  private Set<ObjectId> reverse(
      Graph graph, State state, ObjectId seed, Set<ObjectId> eligible, boolean softEdges) {
    var incoming = new HashMap<ObjectId, List<ObjectId>>();
    spend(graph.objects().size());
    for (var entry : graph.objects().entrySet())
      if (eligible.contains(entry.getKey())) {
        spend(1L + entry.getValue().references().size());
        for (var value : entry.getValue().references())
          if (value.target() != null
              && (value.kind() == Value.ReferenceKind.STRONG
                  || (softEdges
                      && value.kind() == Value.ReferenceKind.SOFT
                      && !state.cleared().get(index(value.wrapper())))))
            incoming
                .computeIfAbsent(value.target(), ignored -> new ArrayList<>())
                .add(entry.getKey());
      }
    var found = new HashSet<ObjectId>();
    var queue = new ArrayDeque<ObjectId>();
    queue.add(seed);
    while (!queue.isEmpty()) {
      spend();
      var id = queue.removeFirst();
      if (found.add(id)) queue.addAll(incoming.getOrDefault(id, List.of()));
    }
    return found;
  }

  private int index(WrapperId id) {
    var index = indices.get(id);
    if (index == null) throw new IllegalArgumentException("Unknown wrapper incarnation " + id);
    return index;
  }

  private void spend() {
    spend(1);
  }

  private void spend(long units) {
    if (units > limits.transitions() - work)
      throw new HistoryLimitException("Reference-history transition budget exhausted");
    work += units;
  }

  private static BitSet copy(BitSet value) {
    return (BitSet) value.clone();
  }
}
