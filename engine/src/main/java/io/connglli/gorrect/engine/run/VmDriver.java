package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Host integration boundary for launch policy and VM evidence. Drivers interpret only the selected
 * VM's facts and recordings; graph expectations remain in the portable oracle. Preflight capability
 * checks establish availability, while verifiers establish what happened during this run.
 */
final class VmDriver {
  private VmDriver() {}

  static String observerArgument(TargetProfile profile, Path directory, String observer) {
    return "-agentpath:"
        + directory.resolve("support/" + observer)
        + (ObservationProfile.parse(profile.observation()).bindings() ? "=locals" : "");
  }

  /**
   * Returns the command prefix through VM options and agent setup, including the executable. The
   * runner appends classpath, generated main class, and control arguments. Directory is the run
   * bundle; observer names the bundled native library. Collector mode must already be resolved.
   */
  static List<String> launchArguments(TargetProfile profile, Path directory, String observer) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.launchArguments(profile, directory, observer)
        : OpenJ9Driver.launchArguments(profile, directory, observer);
  }

  /**
   * Selects the requested family/mode using target facts, never the engine JDK's flag inventory.
   */
  static List<String> collectorArguments(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.collectorArguments(profile, facts)
        : List.of("-Xgcpolicy:" + profile.collector());
  }

  /**
   * Confirms the collector family from target policy/options and beans; mode is checked separately.
   */
  static boolean selected(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.selected(profile.collector(), facts)
        : OpenJ9Driver.selected(profile.collector(), facts);
  }

  /** Resolves the actual mode from target facts, rejecting unrecognized HotSpot modes. */
  static String mode(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.mode(profile.collector(), facts)
        : "default";
  }

  /** Checks VM-family identity only; it makes no collector or capability claim. */
  static boolean matches(TargetProfile profile, Properties facts) {
    String name = facts.getProperty("java.vm.name", "");
    return profile.vm().equals("openj9")
        ? name.contains("OpenJ9")
        : (name.contains("HotSpot") || name.contains("OpenJDK")) && !name.contains("OpenJ9");
  }

  /**
   * Decodes raw worker witnesses for this run, retaining false compiled flags for missing or
   * interpreted methods. Missing files and malformed recordings raise IOException. The stress
   * verifier separately matches each witness to its worker window and GIR request.
   */
  static Set<ExecutionEvidence.Frame> compiledFrames(
      TargetProfile profile, Path directory, long run) throws IOException {
    if (profile.vm().equals("hotspot"))
      return HotSpotDriver.compiledFrames(directory.resolve("traces/workers.jfr"), run);
    return OpenJ9Driver.compiledFrames(
        directory.resolve("traces/workers.gstacks"), run, Artifacts.target(directory));
  }

  /**
   * Returns a mutable list of preflight gaps from the actual target report, including identity,
   * control policy, and requested capabilities. Empty means the run can be attempted; it does not
   * satisfy execution-dependent stress requirements. Malformed required facts raise runtime errors.
   */
  static List<String> requirements(TargetProfile profile, Properties target) {
    var unmet = new ArrayList<String>();
    if (Integer.parseInt(target.getProperty("java.feature")) < 17)
      unmet.add("Target JDK is older than 17");

    if (profile.vm().equals("hotspot")) HotSpotDriver.identity(profile, target, unmet);
    else OpenJ9Driver.identity(profile, target, unmet);

    if (!target.getProperty("gc.control", "").equals(profile.gc().control())
        || !target.getProperty("gc.problem", "").isEmpty())
      unmet.add(
          "GC control unavailable: " + target.getProperty("gc.problem", "missing control report"));

    long bits = Long.parseLong(target.getProperty("observer.capabilities"));
    if ((bits & HeapSnapshot.TAGGING) == 0) unmet.add("tagging");
    if (ObservationProfile.parse(profile.observation()).bindings()
        && (bits & HeapSnapshot.LOCAL_VARIABLES) == 0) unmet.add("local-bindings");

    for (String requirement : profile.requirements()) {
      boolean available =
          switch (requirement) {
            case "tagging", "named-roots" -> (bits & HeapSnapshot.TAGGING) != 0;
            case "local-bindings" ->
                ObservationProfile.parse(profile.observation()).bindings()
                    && (bits & HeapSnapshot.LOCAL_VARIABLES) != 0;
            case "gc-completion" -> target.getProperty("gc.evidence", "false").equals("true");
            case "gc-events" -> (bits & HeapSnapshot.GC_EVENTS) != 0;
            case "compilation-events" ->
                (bits & HeapSnapshot.COMPILATION_EVENTS) != 0
                    && (bits & HeapSnapshot.COMPILATION_OVERFLOW) == 0;
            case "compiled-execution" ->
                profile.jit() != null && target.getProperty("jit.problem", "unavailable").isEmpty();
            case "gc-during-workers" -> (bits & HeapSnapshot.GC_EVENTS) != 0;
            case "concurrent-overlap", "semantic-reads", "alias-checks" -> true;
            case "contended-histories" ->
                !ObservationProfile.parse(profile.observation()).bindings();
            case "reference-histories", "reference-queues" ->
                !ObservationProfile.parse(profile.observation()).bindings()
                    && (bits & HeapSnapshot.REFERENCE_FIELDS) != 0;
            default -> false;
          };
      if (!available && !unmet.contains(requirement)) unmet.add(requirement);
    }

    return unmet;
  }

  /**
   * Checks only VM-specific requested/installed level ranges; diagnostics and frames need checking.
   */
  static boolean installed(TargetProfile profile, ExecutionEvidence.Compilation compilation) {
    return profile.jit() != null
        && compilation.requested() == profile.jit().level()
        && compilation.installed() >= compilation.requested()
        && compilation.installed() <= (profile.vm().equals("hotspot") ? 4 : 1);
  }

  /** Maps provenance keys to installation files; absent optional paths are retained for hashing. */
  static Map<String, Path> installations(TargetProfile profile, Path home) throws IOException {
    var paths = new LinkedHashMap<String, Path>();
    for (String name :
        List.of("release", "lib/server/libjvm.so", "lib/server/libjvm.dylib", "bin/server/jvm.dll"))
      paths.put("target/" + name, home.resolve(name));
    if (profile.vm().equals("openj9")) paths.putAll(OpenJ9Driver.installations(home));
    return paths;
  }
}
