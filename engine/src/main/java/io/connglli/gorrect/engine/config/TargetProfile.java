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
    String observation,
    List<String> vmArgs,
    Map<String, String> environment,
    Support support,
    List<String> requirements,
    Limits limits,
    Jit jit) {
  public static final Set<String> ENVIRONMENT_OPTIONS =
      Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH");

  public record Support(String runtime, String protocol, String observer, String whitebox) {
    public Support(String runtime, String protocol, String observer) {
      this(runtime, protocol, observer, null);
    }
  }

  /** HotSpot installation requests; actual compiled execution requires separate frame evidence. */
  public record Jit(int level, int warmupRounds, int deadlineMillis) {}

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
        "observation",
        "vmArgs",
        "environment",
        "support",
        "requirements",
        "limits",
        "jit");
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
    String observation = text(object, "observation");
    if (!observation.equals("named-roots-diagnostic"))
      throw new IllegalArgumentException("Unknown observation profile " + observation);
    var args = strings(object.get("vmArgs"));
    for (String argument : args) {
      // The driver owns the main class, classpath, observer, collector, and crash destination.
      if (argument.indexOf('\0') >= 0
          || !argument.startsWith("-")
          || argument.matches(
              "(?i)(-cp|-classpath|--class-path.*|-jar|-m|--module.*|-agent.*|-javaagent.*|-Xbootclasspath.*|-Xgcpolicy.*|-XX:[+-]Use.*GC|-XX:(ErrorFile|OnError|OnOutOfMemoryError|Flags|VMOptionsFile)=.*|-version|--version|-help|--help|-X|--dry-run|-Djava.class.path=.*)"))
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
      if (!vm.equals("hotspot") || artifacts.whitebox() == null)
        throw new IllegalArgumentException("HotSpot JIT control requires support.whitebox");
      long warmup =
          settings.has("warmupRounds") ? JsonFiles.number(settings.get("warmupRounds")) : 1;
      if (warmup < 0 || warmup > 1000)
        throw new IllegalArgumentException("Invalid JIT warm-up count");
      jit =
          new Jit(
              limit(settings, "level", 4, 4),
              (int) warmup,
              limit(settings, "deadlineMillis", 5000, 60000));
      if (args.contains("-XX:-UnlockDiagnosticVMOptions"))
        throw new IllegalArgumentException("JIT control requires diagnostic VM options");
    }
    for (String argument : args)
      if (argument.matches("-XX:[+-]WhiteBoxAPI"))
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
        observation,
        args,
        environment,
        artifacts,
        requirements,
        bounds,
        jit);
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
