package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Dispatches VM mechanics while keeping capability checks and the oracle shared. */
final class VmDriver {
  private VmDriver() {}

  static List<String> launchArguments(TargetProfile profile, Path directory, String observer) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.launchArguments(profile, directory, observer)
        : OpenJ9Driver.launchArguments(profile, directory, observer);
  }

  static List<String> collectorArguments(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.collectorArguments(profile, facts)
        : List.of("-Xgcpolicy:" + profile.collector());
  }

  static boolean selected(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.selected(profile.collector(), facts)
        : OpenJ9Driver.selected(profile.collector(), facts);
  }

  static String mode(TargetProfile profile, Properties facts) {
    return profile.vm().equals("hotspot")
        ? HotSpotDriver.mode(profile.collector(), facts)
        : "default";
  }

  static boolean matches(TargetProfile profile, Properties facts) {
    String name = facts.getProperty("java.vm.name", "");
    return profile.vm().equals("openj9")
        ? name.contains("OpenJ9")
        : (name.contains("HotSpot") || name.contains("OpenJDK")) && !name.contains("OpenJ9");
  }

  static Set<ExecutionEvidence.Frame> compiledFrames(
      TargetProfile profile, Path directory, long run) throws IOException {
    if (profile.vm().equals("hotspot"))
      return HotSpotDriver.compiledFrames(directory.resolve("traces/workers.jfr"), run);
    var target = new Properties();
    try (var input = java.nio.file.Files.newInputStream(directory.resolve("target.properties"))) {
      target.load(input);
    }
    return OpenJ9Driver.compiledFrames(directory.resolve("traces/workers.gstacks"), run, target);
  }

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
    for (String requirement : profile.requirements()) {
      boolean available =
          switch (requirement) {
            case "tagging", "named-roots" -> (bits & HeapSnapshot.TAGGING) != 0;
            case "gc-completion" -> target.getProperty("gc.evidence", "false").equals("true");
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

  static boolean installed(TargetProfile profile, ExecutionEvidence.Compilation compilation) {
    return profile.jit() != null
        && compilation.requested() == profile.jit().level()
        && compilation.installed() >= compilation.requested()
        && compilation.installed() <= (profile.vm().equals("hotspot") ? 4 : 1);
  }

  static Map<String, Path> installations(TargetProfile profile, Path home) throws IOException {
    var paths = new java.util.LinkedHashMap<String, Path>();
    for (String name :
        List.of("release", "lib/server/libjvm.so", "lib/server/libjvm.dylib", "bin/server/jvm.dll"))
      paths.put("target/" + name, home.resolve(name));
    if (profile.vm().equals("openj9")) paths.putAll(OpenJ9Driver.installations(home));
    return paths;
  }
}
