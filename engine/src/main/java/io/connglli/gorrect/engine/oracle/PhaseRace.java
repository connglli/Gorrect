package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Vector clocks establish volatile publication; timestamps and recorder order add no edges. */
final class PhaseRace {
  private record Access(int worker, GraphMachine.Memory memory, long[] clock) {}

  private final HistoryBudget budget;
  private final Map<Integer, long[]> clocks = new HashMap<>();
  private final Map<GraphMachine.Slot, long[]> releases = new HashMap<>();
  // A later atomic access must not hide an earlier conflicting plain access.
  private final ArrayList<Access> accesses = new ArrayList<>();

  PhaseRace(HistoryBudget budget) {
    this.budget = budget;
  }

  PhaseRace(PhaseRace source) {
    source.budget.spend(
        source.accesses.size() + (ExecutionTrace.MAX_WORKERS + 1L) * source.clocks.size());
    budget = source.budget;
    source.clocks.forEach((worker, clock) -> clocks.put(worker, clock.clone()));
    // Published stamps are immutable; only the evolving per-worker clocks need deep copies.
    releases.putAll(source.releases);
    accesses.addAll(source.accesses);
  }

  void access(int worker, GraphMachine.Memory memory) {
    if (worker == 0 || memory == null) return;
    var clock = clocks.computeIfAbsent(worker, ignored -> new long[ExecutionTrace.MAX_WORKERS + 1]);
    if (memory.atomic() && memory.read()) {
      var release = releases.get(memory.slot());
      if (release != null)
        for (int index = 0; index < clock.length; index++)
          clock[index] = Math.max(clock[index], release[index]);
    }
    clock[worker]++;
    for (var previous : accesses) {
      budget.spend(1);
      if (previous.worker() == worker
          || !previous.memory().slot().equals(memory.slot())
          || (!previous.memory().write() && !memory.write())
          || (previous.memory().atomic() && memory.atomic())) continue;
      if (!before(previous.clock(), clock))
        throw new UnsupportedHistoryException(
            "Unordered ordinary access between workers "
                + previous.worker()
                + " and "
                + worker
                + " at "
                + memory.slot());
    }
    var stamp = clock.clone();
    accesses.add(new Access(worker, memory, stamp));
    if (memory.atomic() && memory.write()) {
      // Later reads synchronize with every preceding volatile write to this variable. The latest
      // writer need not have acquired earlier writes, so replacing their clocks would lose edges.
      var combined = stamp.clone();
      var earlier = releases.get(memory.slot());
      if (earlier != null)
        for (int index = 0; index < combined.length; index++)
          combined[index] = Math.max(combined[index], earlier[index]);
      releases.put(memory.slot(), combined);
    }
  }

  private static boolean before(long[] earlier, long[] later) {
    for (int index = 0; index < earlier.length; index++)
      if (earlier[index] > later[index]) return false;
    return true;
  }
}
