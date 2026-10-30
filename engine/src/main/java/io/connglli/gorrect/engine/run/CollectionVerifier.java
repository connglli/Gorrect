package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.protocol.CollectionEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Coverage proof for each explicit collection; graph/reference policy remains in the oracle. */
final class CollectionVerifier {
  record Result(List<String> unmet, List<String> problems) {}

  private record Visit(int worker, int group, long round) {}

  private record Key(int worker, int group, long round, long ordinal) {}

  private record Expected(int operation, int phase, long thread) {}

  private CollectionVerifier() {}

  static Result verify(
      Path directory,
      long run,
      GirProgram program,
      OracleResult oracle,
      TargetProfile profile,
      Properties target) {
    if (!profile.requirements().contains("gc-completion")) return new Result(List.of(), List.of());
    try {
      var evidence =
          HotSpotCollections.read(
              directory.resolve("traces/workers.jfr"),
              run,
              Long.parseLong(target.getProperty("process.pid")),
              Long.parseLong(target.getProperty("vm.start")));
      var workers = ExecutionEvidence.read(directory.resolve("traces/workers.gevidence"), run);
      var threads = new HashMap<String, Long>();
      for (var window : workers.windows())
        threads.put(
            window.worker() + ":" + window.group() + ":" + window.round() + ":" + window.phase(),
            window.thread());
      var collects = new HashSet<Integer>();
      declarations(program.body(), collects);
      var ordinals = new HashMap<Visit, Long>();
      var expected = new HashMap<Key, Expected>();
      for (var event : oracle.events())
        if (event.kind() == ExecutionTrace.Kind.OPERATION && collects.contains(event.operation())) {
          long ordinal =
              ordinals.merge(
                  new Visit(event.worker(), event.group(), event.round()), 1L, Long::sum);
          long thread =
              event.worker() == 0
                  ? Long.parseLong(target.getProperty("main.thread"))
                  : threads.getOrDefault(
                      event.worker()
                          + ":"
                          + event.group()
                          + ":"
                          + event.round()
                          + ":"
                          + event.phase(),
                      -1L);
          expected.put(
              new Key(event.worker(), event.group(), event.round(), ordinal),
              new Expected(event.operation(), event.phase(), thread));
        }
      return compare(expected, evidence, profile);
    } catch (IOException | RuntimeException unavailable) {
      return new Result(
          List.of("gc-completion"),
          List.of("Cycle-completion evidence unavailable: " + unavailable.getMessage()));
    }
  }

  private static Result compare(
      Map<Key, Expected> expected, CollectionEvidence evidence, TargetProfile profile) {
    if (expected.isEmpty())
      return new Result(
          List.of("gc-completion"), List.of("No explicit collection request was executed"));
    var seen = new HashSet<Key>();
    var problems = new ArrayList<String>();
    var cycles =
        evidence.cycles().stream()
            .sorted(Comparator.comparing(CollectionEvidence.Cycle::begin))
            .toList();
    for (var request : ordered(evidence.requests())) {
      var key = new Key(request.worker(), request.group(), request.round(), request.ordinal());
      var match = expected.get(key);
      if (match == null
          || !seen.add(key)
          || match.operation() != request.operation()
          || match.phase() != request.phase()
          || match.thread() != request.thread()
          || !request.returned()
          || !profile.gc().control().equals(request.control())) {
        problems.add(
            "Collection request identity, thread, control, or completion differs from GIR");
        break;
      }
      boolean completed = false;
      // Require a whole qualifying cycle inside the request. An earlier in-flight cycle or a
      // pause alone cannot prove post-request completion. Coalesced requests may share a cycle.
      for (int index = lowerBound(cycles, request.begin()); index < cycles.size(); index++) {
        var cycle = cycles.get(index);
        if (cycle.begin().isAfter(request.end())) break;
        if (completedWithin(request, cycle, profile.collector(), profile.collectorMode())) {
          completed = true;
          break;
        }
      }
      if (!completed) {
        problems.add("No complete collector cycle within request " + key);
        break;
      }
    }
    if (seen.size() != expected.size()) problems.add("Missing collection request evidence");
    return problems.isEmpty()
        ? new Result(List.of(), List.of())
        : new Result(List.of("gc-completion"), List.copyOf(problems));
  }

  static boolean qualifies(String collector, String name) {
    return switch (collector) {
      case "serial" -> name.equals("SerialOld");
      case "parallel" -> name.equals("ParallelOld");
      case "g1" -> Set.of("G1Full", "G1Old").contains(name);
      case "zgc" -> Set.of("Z", "ZGC Major").contains(name);
      case "shenandoah" -> name.equals("Shenandoah");
      default -> false;
    };
  }

  static List<CollectionEvidence.Request> ordered(List<CollectionEvidence.Request> requests) {
    var sorted =
        requests.stream()
            .sorted(
                Comparator.comparingInt(CollectionEvidence.Request::worker)
                    .thenComparingInt(CollectionEvidence.Request::group)
                    .thenComparingLong(CollectionEvidence.Request::round)
                    .thenComparingLong(CollectionEvidence.Request::ordinal))
            .toList();
    var ends = new HashMap<Visit, Instant>();
    for (var request : sorted) {
      var previous =
          ends.put(new Visit(request.worker(), request.group(), request.round()), request.end());
      if (previous != null && request.begin().isBefore(previous))
        throw new IllegalArgumentException("Collection spans overlap in worker request order");
    }
    return sorted;
  }

  static boolean completedWithin(
      CollectionEvidence.Request request, CollectionEvidence.Cycle cycle, String collector) {
    return completedWithin(request, cycle, collector, "default");
  }

  static boolean completedWithin(
      CollectionEvidence.Request request,
      CollectionEvidence.Cycle cycle,
      String collector,
      String mode) {
    String cause =
        switch (request.control()) {
          case "system" -> "System.gc()";
          case "whitebox-full" -> "WhiteBox Initiated Full GC";
          default -> null;
        };
    return request.returned()
        && cause != null
        && cause.equals(cycle.cause())
        && qualifies(collector, cycle.name())
        && (!collector.equals("zgc")
            || mode.equals("default")
            || cycle.name().equals(mode.equals("generational") ? "ZGC Major" : "Z"))
        && !cycle.begin().isBefore(request.begin())
        && !cycle.end().isAfter(request.end())
        && cycle.end().isAfter(cycle.begin());
  }

  private static int lowerBound(List<CollectionEvidence.Cycle> cycles, Instant begin) {
    int low = 0, high = cycles.size();
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (cycles.get(mid).begin().isBefore(begin)) low = mid + 1;
      else high = mid;
    }
    return low;
  }

  private static void declarations(List<Instruction> body, Set<Integer> collects) {
    for (var instruction : body)
      switch (instruction) {
        case Instruction.Collect collect -> collects.add(collect.id());
        case Instruction.Scope scope -> declarations(scope.body(), collects);
        case Instruction.Repeat repeat -> declarations(repeat.body(), collects);
        case Instruction.Acquire acquire -> declarations(acquire.body(), collects);
        case Instruction.IfPresent present -> declarations(present.body(), collects);
        case Instruction.Parallel parallel ->
            parallel.workers().forEach(worker -> declarations(worker.body(), collects));
        default -> {}
      }
    ;
  }
}
