package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.nio.file.Files;
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
    arguments.add(VmDriver.executable(profile, QueueResources.controlled(profile, directory)));
    arguments.addAll(
        launchOptions(
            profile,
            directory,
            observer,
            targetFacts(directory),
            QueueResources.controlled(profile, directory)));
    return arguments;
  }

  /** Pure owned options shared by launch and retained live/offline evidence checks. */
  static List<String> launchOptions(
      TargetProfile profile, Path directory, String observer, Properties facts) {
    return launchOptions(profile, directory, observer, facts, profile.retainedMetadata());
  }

  static List<String> launchOptions(
      TargetProfile profile,
      Path directory,
      String observer,
      Properties facts,
      boolean controlled) {
    var arguments = new ArrayList<String>();
    arguments.addAll(profile.vmArgs());
    if (controlled) arguments.add("-XX:+DisableAttachMechanism");
    arguments.addAll(collectorArguments(profile, facts));
    arguments.add("-XX:ErrorFile=" + directory.resolve("logs/hs_err_pid%p.log"));
    arguments.add(VmDriver.observerArgument(profile, directory, observer));
    if (profile.usesWhiteBox()) {
      arguments.add("-XX:+UnlockDiagnosticVMOptions");
      arguments.add("-XX:+WhiteBoxAPI");
      arguments.add("-Xbootclasspath/a:" + directory.resolve("support/whitebox.jar"));
    }
    return arguments;
  }

  /** Initial retained coordinate qualification; every capture still needs actual matching roots. */
  static boolean retainedCoordinates(Properties facts) {
    return Set.of("17", "21").contains(facts.getProperty("java.feature", ""))
        && facts.getProperty("os.name", "").equals("Linux")
        && facts.getProperty("os.arch", "").equals("amd64");
  }

  static String flag(String collector) {
    return switch (collector) {
      case "serial" -> "UseSerialGC";
      case "parallel" -> "UseParallelGC";
      case "g1" -> "UseG1GC";
      case "zgc" -> "UseZGC";
      case "shenandoah" -> "UseShenandoahGC";
      default -> throw new IllegalArgumentException("Unsupported HotSpot collector " + collector);
    };
  }

  static List<String> collectorArguments(TargetProfile profile, Properties facts) {
    var flags = new ArrayList<String>();
    String mode = profile.collectorMode();
    if (profile.collector().equals("shenandoah") && Set.of("iu", "passive").contains(mode))
      flags.add("-XX:+UnlockExperimentalVMOptions");
    if (mode.equals("passive")) flags.add("-XX:+UnlockDiagnosticVMOptions");
    flags.add("-XX:+" + flag(profile.collector()));
    if (profile.collector().equals("zgc") && !mode.equals("default")) {
      if (facts.containsKey("flag.ZGenerational"))
        flags.add("-XX:" + (mode.equals("generational") ? "+" : "-") + "ZGenerational");
      else if (!mode.equals(mode("zgc", facts)))
        throw new IllegalArgumentException("ZGC mode unavailable: " + mode);
    }
    if (profile.collector().equals("shenandoah") && !mode.equals("default"))
      flags.add("-XX:ShenandoahGCMode=" + mode);
    return flags;
  }

  static String mode(String collector, Properties facts) {
    var beans = Set.copyOf(List.of(facts.getProperty("collectors", "").split(",")));
    // Bean identities distinguish pre-generational and generational-only VMs when the toggle
    // itself is absent; the engine JDK version cannot resolve the selected target mode.
    if (collector.equals("zgc")) {
      String mode;
      if (beans.equals(Set.of("ZGC Cycles", "ZGC Pauses"))) mode = "non-generational";
      else if (beans.equals(
          Set.of("ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses")))
        mode = "generational";
      else throw new IllegalArgumentException("Unrecognized ZGC mode/beans: " + beans);

      // Gen-only VMs can omit the toggle, but a present flag must agree with the bean identity.
      String toggle = facts.getProperty("flag.ZGenerational");
      if (toggle != null && !toggle.equals(Boolean.toString(mode.equals("generational"))))
        throw new IllegalArgumentException("ZGC generation flag disagrees with collector beans");
      return mode;
    }
    if (collector.equals("shenandoah")) {
      String mode = facts.getProperty("flag.ShenandoahGCMode", "");
      if (!Set.of("satb", "iu", "passive").contains(mode))
        throw new IllegalArgumentException("Unrecognized Shenandoah mode: " + mode);
      return mode;
    }
    return "default";
  }

  static boolean selected(String collector, Properties facts) {
    for (String family : List.of("serial", "parallel", "g1", "zgc", "shenandoah")) {
      String value = facts.getProperty("flag." + flag(family));
      if (family.equals(collector)
          ? !"true".equals(value)
          : value != null && !"false".equals(value)) return false;
    }
    var beans = Set.copyOf(List.of(facts.getProperty("collectors", "").split(",")));
    return switch (collector) {
      case "serial" -> beans.equals(Set.of("Copy", "MarkSweepCompact"));
      case "parallel" -> beans.equals(Set.of("PS Scavenge", "PS MarkSweep"));
      case "g1" ->
          beans.containsAll(Set.of("G1 Young Generation", "G1 Old Generation"))
              && Set.of("G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC")
                  .containsAll(beans);
      case "zgc" -> {
        try {
          mode(collector, facts);
          yield true;
        } catch (IllegalArgumentException failure) {
          yield false;
        }
      }
      case "shenandoah" -> beans.equals(Set.of("Shenandoah Pauses", "Shenandoah Cycles"));
      default -> false;
    };
  }

  private static Properties targetFacts(Path directory) {
    var facts = new Properties();
    Path file = directory.resolve("collector.properties");
    if (Files.exists(file))
      try (var in = Files.newInputStream(file)) {
        facts.load(in);
      } catch (IOException failure) {
        throw new IllegalArgumentException("Cannot read collector resolution", failure);
      }
    return facts;
  }

  static void identity(TargetProfile profile, Properties target, List<String> unmet) {
    String vm = target.getProperty("java.vm.name", "");
    if (!(vm.contains("HotSpot") || vm.contains("OpenJDK")) || vm.contains("OpenJ9"))
      unmet.add("Expected HotSpot, observed " + vm);
    String family = profile.collector();
    if (!selected(family, target)) unmet.add("Requested collector was not confirmed: " + family);
    try {
      String mode = mode(family, target);
      if (!profile.collectorMode().equals("default") && !profile.collectorMode().equals(mode))
        unmet.add(
            "Collector mode differs: requested " + profile.collectorMode() + ", observed " + mode);
    } catch (IllegalArgumentException failure) {
      unmet.add(failure.getMessage());
    }
  }
}
