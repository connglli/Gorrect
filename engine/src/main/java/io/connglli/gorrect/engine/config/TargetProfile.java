package io.connglli.gorrect.engine.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolved launch settings. Paths are relative to the profile file, never the shell directory. */
public record TargetProfile(
    int version,
    String vm,
    String java,
    String collector,
    String collectorMode,
    String observation,
    List<String> vmArgs,
    Map<String, String> environment,
    Support support,
    List<String> requirements,
    Limits limits,
    Jit jit,
    Gc gc) {
  public static final Set<String> ENVIRONMENT_OPTIONS =
      Set.of(
          "JAVA_TOOL_OPTIONS",
          "JDK_JAVA_OPTIONS",
          "_JAVA_OPTIONS",
          "OPENJ9_JAVA_OPTIONS",
          "IBM_JAVA_OPTIONS",
          "CLASSPATH");

  public record Support(String runtime, String protocol, String observer, String whitebox) {
    public Support(String runtime, String protocol, String observer) {
      this(runtime, protocol, observer, null);
    }
  }

  /** VM installation settings; OpenJ9 level 1 means installed code, not an optimization tier. */
  public record Jit(int level, int warmupRounds, int deadlineMillis) {}

  /** Collection request policy; completion still requires independently decoded VM events. */
  public record Gc(String control) {}

  public boolean usesWhiteBox() {
    return vm.equals("hotspot") && (jit != null || gc.control().equals("whitebox-full"));
  }

  public boolean collectionEvidence() {
    return vm.equals("hotspot") && (jit != null || requirements.contains("gc-completion"));
  }

  public TargetProfile withCollectorMode(String mode) {
    return new TargetProfile(
        version,
        vm,
        java,
        collector,
        mode,
        observation,
        vmArgs,
        environment,
        support,
        requirements,
        limits,
        jit,
        gc);
  }

  public record Limits(
      int startupSeconds,
      int runSeconds,
      int compileSeconds,
      int records,
      int traceEvents,
      int historyStates,
      int historyTransitions) {}

  public TargetProfile {
    vmArgs = List.copyOf(vmArgs);
    environment = Map.copyOf(environment);
    requirements = List.copyOf(requirements);
  }

  public static TargetProfile read(Path path) throws IOException {
    try {
      return parse(JsonFiles.read(path), path.toAbsolutePath().getParent());
    } catch (RuntimeException exception) {
      throw new IOException("Invalid profile: " + exception.getMessage(), exception);
    }
  }

  /** Also used for embedded profiles during offline verification; no installation is accessed. */
  public static TargetProfile parse(JsonObject object, Path base) {
    JsonFiles.fields(
        object,
        "version",
        "vm",
        "java",
        "collector",
        "collectorMode",
        "observation",
        "vmArgs",
        "environment",
        "support",
        "requirements",
        "limits",
        "jit",
        "gc");
    if (JsonFiles.number(JsonFiles.required(object, "version")) != 1)
      throw new IllegalArgumentException("Unsupported profile version");
    String vm = text(object, "vm"), collector = text(object, "collector");
    if (!Set.of("hotspot", "openj9").contains(vm))
      throw new IllegalArgumentException("Unknown VM integration " + vm);
    if (!Set.of(
            "serial",
            "parallel",
            "g1",
            "zgc",
            "shenandoah",
            "gencon",
            "balanced",
            "optavgpause",
            "optthruput",
            "metronome")
        .contains(collector)) throw new IllegalArgumentException("Unknown collector " + collector);
    if (Set.of("serial", "parallel", "g1", "zgc", "shenandoah").contains(collector)
        != vm.equals("hotspot"))
      throw new IllegalArgumentException("Collector does not belong to VM integration");
    String mode = object.has("collectorMode") ? text(object, "collectorMode") : "default";
    var modes =
        collector.equals("zgc")
            ? Set.of("default", "generational", "non-generational")
            : collector.equals("shenandoah")
                ? Set.of("default", "satb", "iu", "passive")
                : Set.of("default");
    if (!modes.contains(mode))
      throw new IllegalArgumentException("Invalid collector mode " + mode + " for " + collector);
    String observation = text(object, "observation");
    if (!observation.equals("named-roots-diagnostic"))
      throw new IllegalArgumentException("Unknown observation profile " + observation);
    var args = strings(object.get("vmArgs"));
    for (String argument : args) {
      // The driver owns the main class, classpath, observer, collector, and crash destination.
      if (argument.indexOf('\0') >= 0
          || !argument.startsWith("-")
          || argument.matches(
              "(?i)(-cp|-classpath|--class-path.*|-jar|-m|--module.*|-agent.*|-javaagent.*|-Xbootclasspath.*|-Xgcpolicy.*|-Xoptionsfile.*|-Xdump.*|-XX:[+-]Use.*GC|-XX:[+-]ZGenerational|-XX:ShenandoahGCMode=.*|-XX:(ErrorFile|OnError|OnOutOfMemoryError|Flags|VMOptionsFile)=.*|-version|--version|-help|--help|-X|--dry-run|-Djava.class.path=.*)"))
        throw new IllegalArgumentException("Conflicting or reserved VM argument " + argument);
    }
    var environment = new HashMap<String, String>();
    if (object.has("environment")) {
      for (var entry : object.getAsJsonObject("environment").entrySet()) {
        if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")
            || ENVIRONMENT_OPTIONS.contains(entry.getKey().toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException(
              "Reserved or invalid environment variable " + entry.getKey());
        String value = JsonFiles.string(entry.getValue());
        if (value.indexOf('\0') >= 0)
          throw new IllegalArgumentException("Invalid environment value");
        environment.put(entry.getKey(), value);
      }
    }
    var support = JsonFiles.required(object, "support").getAsJsonObject();
    JsonFiles.fields(support, "runtime", "protocol", "observer", "whitebox");
    var artifacts =
        new Support(
            resolve(base, text(support, "runtime")),
            resolve(base, text(support, "protocol")),
            resolve(base, text(support, "observer")),
            support.has("whitebox") && !support.get("whitebox").isJsonNull()
                ? resolve(base, text(support, "whitebox"))
                : null);
    Jit jit = null;
    if (object.has("jit") && !object.get("jit").isJsonNull()) {
      var settings = object.getAsJsonObject("jit");
      JsonFiles.fields(settings, "level", "warmupRounds", "deadlineMillis");
      if (vm.equals("hotspot") && artifacts.whitebox() == null)
        throw new IllegalArgumentException("HotSpot JIT control requires support.whitebox");
      long warmup =
          settings.has("warmupRounds") ? JsonFiles.number(settings.get("warmupRounds")) : 1;
      if (warmup < 0 || warmup > 1000)
        throw new IllegalArgumentException("Invalid JIT warm-up count");
      jit =
          new Jit(
              limit(settings, "level", vm.equals("hotspot") ? 4 : 1, vm.equals("hotspot") ? 4 : 1),
              (int) warmup,
              limit(settings, "deadlineMillis", 5000, 60000));
      if (vm.equals("hotspot") && args.contains("-XX:-UnlockDiagnosticVMOptions"))
        throw new IllegalArgumentException("JIT control requires diagnostic VM options");
    }
    for (String argument : args)
      if (vm.equals("openj9") && jit != null && argument.startsWith("-Xjit"))
        throw new IllegalArgumentException("OpenJ9 JIT options belong to profile.jit");
      else if (vm.equals("openj9")
          && jit != null
          && (argument.startsWith("-Xaot")
              || (argument.startsWith("-Xshareclasses")
                  && !argument.equals("-Xshareclasses:none"))))
        throw new IllegalArgumentException("OpenJ9 JIT profiles disable shared-cache/AOT reuse");
      else if (argument.matches("-XX:[+-]WhiteBoxAPI"))
        throw new IllegalArgumentException("WhiteBox options belong to the driver");
    var requirements =
        object.has("requirements")
            ? strings(object.get("requirements"))
            : List.of("tagging", "named-roots");
    if (Set.copyOf(requirements).size() != requirements.size())
      throw new IllegalArgumentException("Duplicate capability requirement");
    for (String requirement : requirements)
      if (!Set.of(
                  "tagging",
                  "named-roots",
                  "gc-events",
                  "compilation-events",
                  "local-bindings",
                  "compiled-execution",
                  "gc-completion",
                  "contended-histories",
                  "reference-histories",
                  "reference-queues",
                  "reference-reclamation")
              .contains(requirement)
          && !Set.of("concurrent-overlap", "gc-during-workers").contains(requirement))
        throw new IllegalArgumentException("Unknown requirement " + requirement);
    var gcSettings =
        object.has("gc") && !object.get("gc").isJsonNull()
            ? object.getAsJsonObject("gc")
            : new JsonObject();
    JsonFiles.fields(gcSettings, "control");
    String gcControl =
        gcSettings.has("control")
            ? text(gcSettings, "control")
            : jit == null || !vm.equals("hotspot") ? "system" : "whitebox-full";
    if (!Set.of("system", "whitebox-full").contains(gcControl))
      throw new IllegalArgumentException("Unknown GC control " + gcControl);
    if (gcControl.equals("whitebox-full")
        && (!vm.equals("hotspot") || artifacts.whitebox() == null))
      throw new IllegalArgumentException(
          "WhiteBox GC control requires HotSpot and support.whitebox");
    if (vm.equals("hotspot")
        && (jit != null || gcControl.equals("whitebox-full"))
        && args.contains("-XX:-UnlockDiagnosticVMOptions"))
      throw new IllegalArgumentException("WhiteBox control requires diagnostic VM options");
    if (Set.of("iu", "passive").contains(mode) && args.contains("-XX:-UnlockExperimentalVMOptions"))
      throw new IllegalArgumentException(
          "Selected Shenandoah mode requires experimental VM options");
    if (mode.equals("passive") && args.contains("-XX:-UnlockDiagnosticVMOptions"))
      throw new IllegalArgumentException("Passive mode requires diagnostic VM options");
    JsonObject limits = object.has("limits") ? object.getAsJsonObject("limits") : new JsonObject();
    JsonFiles.fields(
        limits,
        "startupSeconds",
        "runSeconds",
        "compileSeconds",
        "records",
        "traceEvents",
        "historyStates",
        "historyTransitions");
    var bounds =
        new Limits(
            limit(limits, "startupSeconds", 10, 3600),
            limit(limits, "runSeconds", 30, 86400),
            limit(limits, "compileSeconds", 30, 3600),
            limit(limits, "records", 100_000, 1_000_000),
            limit(limits, "traceEvents", 100_000, 1_000_000),
            limit(limits, "historyStates", 4096, 1_000_000),
            limit(limits, "historyTransitions", 1_000_000, 10_000_000));
    if (bounds.startupSeconds() > bounds.runSeconds())
      throw new IllegalArgumentException("Startup deadline exceeds run deadline");
    return new TargetProfile(
        1,
        vm,
        resolve(base, text(object, "java")),
        collector,
        mode,
        observation,
        args,
        environment,
        artifacts,
        requirements,
        bounds,
        jit,
        new Gc(gcControl));
  }

  private static int limit(JsonObject object, String name, int fallback, int maximum) {
    long value = object.has(name) ? JsonFiles.number(object.get(name)) : fallback;
    if (value < 1 || value > maximum) throw new IllegalArgumentException("Invalid limit " + name);
    return (int) value;
  }

  private static String text(JsonObject object, String name) {
    return JsonFiles.string(JsonFiles.required(object, name));
  }

  private static String resolve(Path base, String value) {
    if (value.isBlank() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Empty or invalid artifact path");
    return base.resolve(value).normalize().toAbsolutePath().toString();
  }

  private static List<String> strings(JsonElement value) {
    var result = new ArrayList<String>();
    if (value != null) for (var item : value.getAsJsonArray()) result.add(JsonFiles.string(item));
    return result;
  }
}
