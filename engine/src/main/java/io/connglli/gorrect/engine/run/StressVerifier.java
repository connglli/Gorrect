package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates synchronization evidence before assessing stress coverage independently of graph
 * values.
 */
final class StressVerifier {
  record Result(List<String> unmet, List<String> problems) {}

  private record Visit(int group, long round) {}

  private record WindowKey(int worker, int group, long round, int phase) {}

  private record WorkerVisit(int worker, int group, long round) {}

  private StressVerifier() {}

  static Result verify(
      Path directory, long run, GirProgram program, OracleResult oracle, TargetProfile profile)
      throws IOException {
    var declarations = new LinkedHashMap<Integer, Instruction.Parallel>();
    var collects = new HashSet<Integer>();
    declarations(program.body(), declarations, collects);
    var expected = new ArrayList<Visit>();
    var rounds = new HashMap<Integer, Long>();
    for (var event : oracle.events())
      if (event.worker() == 0
          && event.kind() == ExecutionTrace.Kind.OPERATION
          && declarations.containsKey(event.operation()))
        expected.add(new Visit(event.operation(), rounds.merge(event.operation(), 1L, Long::sum)));
    var collectionOrdinals = new HashMap<WorkerVisit, Long>();
    var requests = new HashMap<WindowKey, Set<Long>>();
    for (var event : oracle.events())
      if (event.worker() > 0
          && event.kind() == ExecutionTrace.Kind.OPERATION
          && collects.contains(event.operation())) {
        long ordinal =
            collectionOrdinals.merge(
                new WorkerVisit(event.worker(), event.group(), event.round()), 1L, Long::sum);
        requests
            .computeIfAbsent(
                new WindowKey(event.worker(), event.group(), event.round(), event.phase()),
                ignored -> new HashSet<>())
            .add(ordinal);
      }
    Path path = directory.resolve("traces/workers.gevidence");
    var evidence =
        Files.exists(path)
            ? ExecutionEvidence.read(path, run)
            : new ExecutionEvidence(run, expected.isEmpty(), List.of(), List.of());
    if (!evidence.complete() || evidence.groups().size() != expected.size())
      throw new IOException("Worker synchronization evidence is incomplete");
    var windows = new HashMap<WindowKey, ExecutionEvidence.Window>();
    for (var window : evidence.windows()) {
      var key = new WindowKey(window.worker(), window.group(), window.round(), window.phase());
      if (windows.putIfAbsent(key, window) != null)
        throw new IOException("Duplicate worker window");
      if (!((window.gcBefore() == -1 && window.gcAfter() == -1 && window.collection() == 0)
          || (window.gcBefore() >= 0
              && window.gcAfter() > window.gcBefore()
              && window.collection() > 0)))
        throw new IOException("Inconsistent collection evidence");
    }
    int windowCount = 0;
    for (int index = 0; index < expected.size(); index++) {
      var visit = expected.get(index);
      var group = evidence.groups().get(index);
      var declaration = declarations.get(visit.group());
      if (group.operation() != visit.group()
          || group.round() != visit.round()
          || !group.joined()
          || !group
              .workers()
              .equals(declaration.workers().stream().map(Instruction.Worker::id).toList()))
        throw new IOException("Worker joins or group order differ from GIR");
      if (!((group.gcBefore() == -1 && group.gcAfter() == -1)
          || (group.gcBefore() >= 0 && group.gcAfter() >= group.gcBefore())))
        throw new IOException("Invalid group GC counters");
      int phases =
          1
              + (int)
                  declaration.workers().getFirst().body().stream()
                      .filter(op -> op instanceof Instruction.Barrier)
                      .count();
      var threads = new HashMap<Integer, Long>();
      long previousEnd = Long.MIN_VALUE;
      for (int phase = 0; phase < phases; phase++) {
        long end = Long.MIN_VALUE;
        for (int worker : group.workers()) {
          var window = windows.get(new WindowKey(worker, visit.group(), visit.round(), phase));
          if (window == null || window.begin() < previousEnd)
            throw new IOException("Missing or unordered phase window");
          var thread = threads.putIfAbsent(worker, window.thread());
          if (thread != null && thread != window.thread())
            throw new IOException("Worker changed threads within a group");
          end = Math.max(end, window.end());
          windowCount++;
          if (window.collection() > 0) {
            if (group.gcBefore() < 0
                || window.gcBefore() < group.gcBefore()
                || window.gcAfter() > group.gcAfter())
              throw new IOException("Worker GC counters lie outside the group interval");
            if (!requests
                .getOrDefault(new WindowKey(worker, visit.group(), visit.round(), phase), Set.of())
                .contains(window.collection()))
              throw new IOException("Collection evidence has no matching GIR request");
          }
        }
        previousEnd = end;
      }
      if (threads.values().stream().distinct().count() != group.workers().size())
        throw new IOException("Workers share a thread identity");
    }
    if (windowCount != windows.size()) throw new IOException("Unexpected worker window");
    var required = new HashSet<>(program.requirements());
    required.addAll(profile.requirements());
    required.retainAll(GirValidator.STRESS_REQUIREMENTS);
    if (required.isEmpty()) return new Result(List.of(), List.of());
    var problems = new ArrayList<String>();
    Set<ExecutionEvidence.Frame> frames = Set.of();
    if (required.contains("compiled-execution"))
      try {
        frames = VmDriver.compiledFrames(profile, directory, run);
        for (var frame : frames) {
          var key = new WindowKey(frame.worker(), frame.group(), frame.round(), frame.phase());
          var window = windows.get(key);
          if (window == null || window.thread() != frame.thread())
            throw new IOException("VM frame witness does not match a worker window");
          if (frame.point() == ExecutionEvidence.ENTER && frame.phase() != 0)
            throw new IOException("VM entry witness has a noninitial phase");
          int last =
              (int)
                  declarations.get(frame.group()).workers().getFirst().body().stream()
                      .filter(op -> op instanceof Instruction.Barrier)
                      .count();
          if (frame.point() == ExecutionEvidence.EXIT && frame.phase() != last)
            throw new IOException("VM exit witness has a nonfinal phase");
          if (frame.point() >= ExecutionEvidence.BEFORE_COLLECTION
              && !requests.getOrDefault(key, Set.of()).contains(frame.collection()))
            throw new IOException("VM witness has no matching collection request");
        }
      } catch (IOException failure) {
        frames = Set.of();
        problems.add("Compiled-frame evidence unavailable: " + failure.getMessage());
      }
    var individuallyMet = new HashSet<String>();
    for (var group : evidence.groups()) {
      var groupWindows =
          evidence.windows().stream()
              .filter(
                  window -> window.group() == group.operation() && window.round() == group.round())
              .toList();
      var overlap = new HashSet<Integer>();
      for (int phase :
          groupWindows.stream().map(ExecutionEvidence.Window::phase).distinct().toList()) {
        var values = groupWindows.stream().filter(window -> window.phase() == phase).toList();
        long begin = values.stream().mapToLong(ExecutionEvidence.Window::begin).max().orElseThrow();
        long end = values.stream().mapToLong(ExecutionEvidence.Window::end).min().orElseThrow();
        if (group.workers().size() > 1 && begin < end) overlap.add(phase);
      }
      boolean concurrent = !required.contains("concurrent-overlap") || !overlap.isEmpty();
      boolean compiled =
          !required.contains("compiled-execution")
              || compiledGroup(group, groupWindows, frames, profile);
      if (!overlap.isEmpty()) individuallyMet.add("concurrent-overlap");
      if (compiled && required.contains("compiled-execution"))
        individuallyMet.add("compiled-execution");
      if (groupWindows.stream().anyMatch(window -> window.collection() > 0))
        individuallyMet.add("gc-during-workers");
      boolean gc = !required.contains("gc-during-workers");
      for (var window : groupWindows)
        if (window.collection() > 0
            && (!required.contains("concurrent-overlap") || overlap.contains(window.phase()))) {
          if (!required.contains("compiled-execution")
              || (frame(frames, window, ExecutionEvidence.BEFORE_COLLECTION, window.collection())
                  && frame(
                      frames, window, ExecutionEvidence.AFTER_COLLECTION, window.collection())))
            gc = true;
        }
      if (concurrent && compiled && gc) return new Result(List.of(), List.of());
    }
    problems.add(
        "Stress conditions were not jointly established in one worker-group visit: "
            + required.stream().sorted().toList());
    var unmet =
        required.stream()
            .filter(requirement -> !individuallyMet.contains(requirement))
            .sorted()
            .toList();
    return new Result(
        unmet.isEmpty() ? required.stream().sorted().toList() : unmet, List.copyOf(problems));
  }

  private static boolean compiledGroup(
      ExecutionEvidence.Group group,
      List<ExecutionEvidence.Window> windows,
      Set<ExecutionEvidence.Frame> frames,
      TargetProfile profile) {
    if (profile.jit() == null
        || group.round() <= profile.jit().warmupRounds()
        || group.compilations().size() != group.workers().size()) return false;
    for (int worker : group.workers()) {
      var compilation =
          group.compilations().stream().filter(value -> value.worker() == worker).toList();
      if (compilation.size() != 1) return false;
      var value = compilation.getFirst();
      if (!value.method().equals(ExecutionEvidence.workerMethod(worker))
          || !VmDriver.installed(profile, value)
          || !value.problem().isEmpty()) return false;
      var first =
          windows.stream()
              .filter(window -> window.worker() == worker && window.phase() == 0)
              .findFirst()
              .orElseThrow();
      var last =
          windows.stream()
              .filter(window -> window.worker() == worker)
              .max(java.util.Comparator.comparingInt(ExecutionEvidence.Window::phase))
              .orElseThrow();
      if (!frame(frames, first, ExecutionEvidence.ENTER, 0)
          || !frame(frames, last, ExecutionEvidence.EXIT, 0)) return false;
    }
    return true;
  }

  private static boolean frame(
      Set<ExecutionEvidence.Frame> frames,
      ExecutionEvidence.Window window,
      int point,
      long collection) {
    return frames.contains(
        new ExecutionEvidence.Frame(
            window.worker(),
            window.group(),
            window.round(),
            window.phase(),
            point,
            collection,
            window.thread(),
            true));
  }

  private static void declarations(
      List<Instruction> body, Map<Integer, Instruction.Parallel> groups, Set<Integer> collects) {
    for (var instruction : body)
      switch (instruction) {
        case Instruction.Parallel parallel -> {
          groups.put(parallel.id(), parallel);
          parallel.workers().forEach(worker -> declarations(worker.body(), groups, collects));
        }
        case Instruction.Acquire acquire -> declarations(acquire.body(), groups, collects);
        case Instruction.IfPresent present -> declarations(present.body(), groups, collects);
        case Instruction.Scope scope -> declarations(scope.body(), groups, collects);
        case Instruction.Repeat repeat -> declarations(repeat.body(), groups, collects);
        case Instruction.Collect collect -> collects.add(collect.id());
        default -> {}
      }
  }
}
