package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.MonitorProgram;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Future semantic uses for deterministic main-thread checkpoints. Exact-oracle branch markers
 * select the route; they supply no physical roots. Reference histories need a separate protection
 * proof and remain rejected by the profile's capability checks.
 */
public final class NaturalLiveness {
  private record Point(int operation, long visit) {}

  private record Step(int definition, Set<Integer> uses, Point checkpoint) {}

  private final Map<Point, Set<Integer>> live = new HashMap<>();
  private final Map<Integer, Long> visits = new HashMap<>();
  private final ArrayDeque<Map<String, Integer>> scopes = new ArrayDeque<>();
  private final List<Step> steps = new ArrayList<>();
  private final ArrayDeque<ExecutionTrace.Event> branches = new ArrayDeque<>();
  private boolean exactPath;
  private long work;

  private NaturalLiveness() {}

  /** Expands bounded literal control flow; definitions separate successive loop activations. */
  public static NaturalLiveness analyze(GirProgram program) {
    if (MonitorProgram.usesMonitors(program))
      throw new UnsupportedHistoryException("Monitors require controlled observation");
    var analysis = new NaturalLiveness();
    analysis.block(program.body());
    return analysis.finish();
  }

  /**
   * Follows the exact oracle's complete main route. Callers must validate actual scalar/control
   * correspondence before using these requirements to omit local comparisons.
   */
  public static NaturalLiveness analyze(GirProgram program, OracleResult oracle) {
    if (MonitorProgram.usesMonitors(program))
      throw new UnsupportedHistoryException("Monitors require controlled observation");
    var analysis = new NaturalLiveness();
    analysis.exactPath = true;
    oracle.events().stream()
        .filter(event -> event.worker() == 0 && event.kind() == ExecutionTrace.Kind.BRANCH)
        .forEach(analysis.branches::addLast);
    analysis.block(program.body());
    if (!analysis.branches.isEmpty())
      throw new IllegalArgumentException("Unused exact-oracle branch markers");
    return analysis.finish();
  }

  private NaturalLiveness finish() {
    var used = new HashSet<Integer>();
    long cells = 0;
    for (int i = steps.size() - 1; i >= 0; i--) {
      var step = steps.get(i);
      if (step.checkpoint() != null) {
        cells += 1L + used.size();
        if (cells > OracleLimits.DEFAULT.snapshotCells())
          throw new OracleLimitException("Natural-liveness checkpoint storage exhausted");
        live.put(step.checkpoint(), Set.copyOf(used));
      }
      used.remove(step.definition());
      used.addAll(step.uses());
    }
    steps.clear();
    return this;
  }

  public Set<Integer> at(int operation, long visit) {
    return live.getOrDefault(new Point(operation, visit), Set.of());
  }

  /** Keeps only active handles whose values will be used before their activation ends. */
  public Graph obligations(Graph graph, int operation, long visit) {
    var handles = new HashMap<Integer, Value.Reference>();
    for (int id : at(operation, visit)) {
      var value = graph.handles().get(id);
      if (value != null) handles.put(id, value);
    }
    return new Graph(graph.objects(), graph.roots(), handles, graph.wrappers());
  }

  /** Main-thread visits requiring this binding under the legacy unconditional selection rule. */
  public List<Long> visits(int operation, int binding) {
    return live.entrySet().stream()
        .filter(
            entry -> entry.getKey().operation() == operation && entry.getValue().contains(binding))
        .map(entry -> entry.getKey().visit())
        .sorted()
        .toList();
  }

  private void charge() {
    if (++work > OracleLimits.DEFAULT.steps())
      throw new OracleLimitException("Natural-liveness analysis work exhausted");
  }

  private void block(List<Instruction> body) {
    scopes.push(new HashMap<>());
    for (var instruction : body) {
      charge();
      if (instruction instanceof Instruction.Scope scope) block(scope.body());
      else if (instruction instanceof Instruction.Repeat repeat) {
        for (long i = 0; i < repeat.count(); i++) {
          charge();
          block(repeat.body());
        }
      } else if (instruction instanceof Instruction.Parallel) {
        // Workers cannot capture caller bindings; their semantic reads have independent traces.
      } else if (instruction instanceof Instruction.IfPresent present && exactPath) {
        var used = new HashSet<Integer>();
        use(present.name(), used);
        steps.add(new Step(0, used, null));
        if (selected(present.id())) block(present.body());
      } else if (instruction instanceof Instruction.IfNonzero branch && exactPath) {
        block(selected(branch.id()) ? branch.thenBody() : branch.elseBody());
      } else if (instruction instanceof Instruction.Operation op) operation(op);
      else
        throw new UnsupportedHistoryException(
            "Natural liveness requires unconditional control flow");
    }
    scopes.pop();
  }

  private boolean selected(int operation) {
    var event = branches.pollFirst();
    if (event == null || event.operation() != operation || event.value() < 0 || event.value() > 1)
      throw new IllegalArgumentException("Exact-oracle branch route is inconsistent");
    return event.value() != 0;
  }

  private void operation(Instruction.Operation operation) {
    var used = new HashSet<Integer>();
    int definition = 0;
    Point point = null;
    switch (operation) {
      case Instruction.Allocate op -> {
        definition = op.id();
        scopes.peek().put(op.name(), op.id());
      }
      case Instruction.ReadRoot op -> {
        definition = op.id();
        scopes.peek().put(op.name(), op.id());
      }
      case Instruction.ReadReference op -> {
        use(op.owner(), used);
        definition = op.id();
        scopes.peek().put(op.name(), op.id());
      }
      case Instruction.ReadData op -> use(op.owner(), used);
      case Instruction.WriteData op -> use(op.owner(), used);
      case Instruction.WriteReference op -> {
        use(op.owner(), used);
        use(op.value(), used);
      }
      case Instruction.WriteRoot op -> use(op.value(), used);
      case Instruction.SameObject op -> {
        use(op.left(), used);
        use(op.right(), used);
      }
      case Instruction.Release op -> scopes.peek().remove(op.name());
      case Instruction.Checkpoint op ->
          point = new Point(op.id(), visits.merge(op.id(), 1L, Long::sum));
      case Instruction.Collect ignored -> {}
      default ->
          throw new UnsupportedHistoryException("Operation needs path-sensitive natural liveness");
    }
    steps.add(new Step(definition, used, point));
  }

  private void use(Operand value, Set<Integer> used) {
    if (value instanceof Operand.Name name) use(name.name(), used);
  }

  private void use(String name, Set<Integer> used) {
    for (var scope : scopes) {
      Integer id = scope.get(name);
      if (id != null) {
        used.add(id);
        return;
      }
    }
    throw new IllegalArgumentException("Unmapped reference binding " + name);
  }
}
