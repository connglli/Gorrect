package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Capture quotas and lexical declaration coverage; history-plan graphs supply no predicted values.
 */
public final class CheckpointPlan {
  public record Visit(int operation, long occurrence) {}

  /** Actual participant arrivals at a canonical worker checkpoint, before phase release. */
  public record Cut(
      int group, long round, int ordinal, int phase, Map<Integer, Visit> participants) {
    public Cut {
      participants = Map.copyOf(participants);
    }
  }

  private record WorkerVisit(int worker, int group, long round) {}

  private record ArrivalKey(int worker, int group, long round, int phase, int operation) {}

  private record Arrival(
      int worker, int operation, long occurrence, int group, long round, int phase) {}

  private final Map<Integer, Long> maxima = new HashMap<>();
  private final Map<Integer, Set<Integer>> declarations = new HashMap<>();
  private final List<Visit> visits;

  public CheckpointPlan(OracleResult plan) {
    var order = new ArrayList<Visit>();
    for (var point : plan.checkpoints()) {
      maxima.merge(point.operation(), point.occurrence(), Math::max);
      var keys = Set.copyOf(point.graph().handles().keySet());
      var previous = declarations.putIfAbsent(point.operation(), keys);
      if (previous != null && !previous.equals(keys))
        throw new IllegalArgumentException("Checkpoint declaration scope changes across visits");
      order.add(new Visit(point.operation(), point.occurrence()));
    }
    order.add(new Visit(0, 1));
    visits = List.copyOf(order);
  }

  /** Exact order for a deterministic oracle, or maximum visit order for a history storage plan. */
  public List<Visit> visits() {
    return visits;
  }

  public boolean permits(int operation, long occurrence) {
    return operation == 0
        ? occurrence == 1
        : occurrence > 0 && occurrence <= maxima.getOrDefault(operation, 0L);
  }

  /** The lexical scope of an executed site is fixed even when some visits are skipped. */
  public Set<Integer> declarations(int operation) {
    if (operation == 0) return Set.of();
    var result = declarations.get(operation);
    if (result == null) throw new IllegalArgumentException("Unknown checkpoint site");
    return result;
  }

  /**
   * Classifies actual markers without accepting branch legality. Worker captures occur within the
   * main trace's executed group visit, in the designated worker's local order.
   */
  public static List<Visit> recorded(GirProgram program, ExecutionTrace trace) {
    return List.copyOf(recording(program, trace).keySet());
  }

  /** Returns participant context for worker captures; main and final captures have no cut. */
  public static Map<Visit, Cut> recordedCuts(GirProgram program, ExecutionTrace trace) {
    var result = new HashMap<Visit, Cut>();
    recording(program, trace)
        .forEach(
            (visit, cut) -> {
              if (cut != null) result.put(visit, cut);
            });
    return Map.copyOf(result);
  }

  private static Map<Visit, Cut> recording(GirProgram program, ExecutionTrace trace) {
    var sites =
        program
            .instructions()
            .filter(instruction -> instruction instanceof Instruction.Checkpoint)
            .map(Instruction::id)
            .collect(Collectors.toSet());
    var groups = new HashMap<Integer, Instruction.Parallel>();
    program
        .instructions()
        .forEach(
            instruction -> {
              if (instruction instanceof Instruction.Parallel parallel)
                groups.put(parallel.id(), parallel);
            });
    var pointsByWorker = new HashMap<Integer, List<Integer>>();
    groups
        .values()
        .forEach(
            group ->
                group
                    .workers()
                    .forEach(
                        worker ->
                            pointsByWorker.put(
                                worker.id(),
                                worker.body().stream()
                                    .filter(
                                        instruction ->
                                            instruction instanceof Instruction.Checkpoint)
                                    .map(Instruction::id)
                                    .toList())));
    var arrivals = new HashMap<WorkerVisit, List<Arrival>>();
    var indexed = new HashMap<ArrivalKey, Arrival>();
    for (var worker : trace.byWorker().entrySet()) {
      if (worker.getKey() == 0) continue;
      var visits = new HashMap<Integer, Long>();
      for (var event : worker.getValue())
        if (event.kind() == ExecutionTrace.Kind.OPERATION && sites.contains(event.operation())) {
          var arrival =
              new Arrival(
                  event.worker(),
                  event.operation(),
                  visits.merge(event.operation(), 1L, Long::sum),
                  event.group(),
                  event.round(),
                  event.phase());
          var key =
              new ArrivalKey(
                  event.worker(), event.group(), event.round(), event.phase(), event.operation());
          if (indexed.putIfAbsent(key, arrival) != null)
            throw new IllegalArgumentException("Duplicate worker checkpoint arrival");
          arrivals
              .computeIfAbsent(
                  new WorkerVisit(event.worker(), event.group(), event.round()),
                  ignored -> new ArrayList<>())
              .add(arrival);
        }
    }
    var result = new LinkedHashMap<Visit, Cut>();
    var counters = new HashMap<Integer, Long>();
    for (var event : trace.byWorker().getOrDefault(0, List.of())) {
      if (event.kind() != ExecutionTrace.Kind.OPERATION) continue;
      if (sites.contains(event.operation()))
        result.put(
            new Visit(event.operation(), counters.merge(event.operation(), 1L, Long::sum)), null);
      var group = groups.get(event.operation());
      if (group == null) continue;
      long round = counters.merge(group.id(), 1L, Long::sum);
      var designated = group.workers().getFirst();
      int ordinal = 0;
      for (var arrival :
          arrivals.getOrDefault(new WorkerVisit(designated.id(), group.id(), round), List.of())) {
        var participants = new HashMap<Integer, Visit>();
        participants.put(0, new Visit(group.id(), round));
        for (var declaration : group.workers()) {
          var points = pointsByWorker.get(declaration.id());
          if (ordinal >= points.size())
            throw new IllegalArgumentException("Unexpected worker checkpoint");
          int operation = points.get(ordinal);
          var peer =
              indexed.get(
                  new ArrivalKey(declaration.id(), group.id(), round, arrival.phase(), operation));
          if (peer == null)
            throw new IllegalArgumentException("Incomplete worker checkpoint arrivals");
          participants.put(declaration.id(), new Visit(peer.operation(), peer.occurrence()));
        }
        var key = new Visit(arrival.operation(), arrival.occurrence());
        result.put(key, new Cut(group.id(), round, ++ordinal, arrival.phase(), participants));
      }
    }
    result.put(new Visit(0, 1), null);
    return result;
  }
}
