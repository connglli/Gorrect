package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** HotSpot launch policy and evidence interpretation; no collector rule enters the oracle. */
final class HotSpotDriver {
  private HotSpotDriver() {}

  static Set<ExecutionEvidence.Frame> compiledFrames(Path path, long run) throws IOException {
    return HotSpotFrames.read(path, run);
  }

  static List<String> launchArguments(TargetProfile profile, Path directory, String observer) {
    var arguments = new ArrayList<String>();
    arguments.add(profile.java());
    arguments.addAll(profile.vmArgs());
    arguments.add("-XX:+UseSerialGC");
    arguments.add("-XX:ErrorFile=" + directory.resolve("logs/hs_err_pid%p.log"));
    arguments.add("-agentpath:" + directory.resolve("support/" + observer));
    if (profile.jit() != null) {
      arguments.add("-XX:+UnlockDiagnosticVMOptions");
      arguments.add("-XX:+WhiteBoxAPI");
      arguments.add("-Xbootclasspath/a:" + directory.resolve("support/whitebox.jar"));
    }
    return arguments;
  }

  static List<String> unsupported(TargetProfile profile) {
    var problems = new ArrayList<String>();
    if (!profile.vm().equals("hotspot"))
      problems.add("VM integration is not implemented: " + profile.vm());
    if (!profile.collector().equals("serial"))
      problems.add("Collector integration is not implemented: " + profile.collector());
    return problems;
  }

  static List<String> requirements(TargetProfile profile, Properties target) {
    var unmet = new ArrayList<String>();
    String vm = target.getProperty("java.vm.name", "");
    if (!(vm.contains("HotSpot") || vm.contains("OpenJDK")) || vm.contains("OpenJ9"))
      unmet.add("Expected HotSpot, observed " + vm);
    if (Integer.parseInt(target.getProperty("java.feature")) < 17)
      unmet.add("Target JDK is older than 17");
    if (!new java.util.HashSet<>(List.of(target.getProperty("collectors", "").split(",")))
        .equals(java.util.Set.of("Copy", "MarkSweepCompact")))
      unmet.add("Serial collector was not confirmed: " + target.getProperty("collectors"));
    long bits = Long.parseLong(target.getProperty("observer.capabilities"));
    if ((bits & HeapSnapshot.TAGGING) == 0) unmet.add("tagging");
    for (String requirement : profile.requirements()) {
      boolean available =
          switch (requirement) {
            case "tagging", "named-roots" -> (bits & HeapSnapshot.TAGGING) != 0;
            case "gc-events" -> (bits & HeapSnapshot.GC_EVENTS) != 0;
            case "compilation-events" ->
                (bits & HeapSnapshot.COMPILATION_EVENTS) != 0
                    && (bits & HeapSnapshot.COMPILATION_OVERFLOW) == 0;
            case "compiled-execution" ->
                profile.jit() != null && target.getProperty("jit.problem", "unavailable").isEmpty();
            case "gc-during-workers" -> (bits & HeapSnapshot.GC_EVENTS) != 0;
            case "concurrent-overlap", "contended-histories" -> true;
            case "reference-histories", "reference-queues" ->
                (bits & HeapSnapshot.REFERENCE_FIELDS) != 0;
            default -> false;
          };
      if (!available && !unmet.contains(requirement)) unmet.add(requirement);
    }
    return unmet;
  }
}
