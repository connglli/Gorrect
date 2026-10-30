package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.StackEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** OpenJ9 collector selection and extended-stack decoding; no policy enters graph semantics. */
final class OpenJ9Driver {
  private record Identity(
      int worker, int group, long round, int phase, int point, long collection) {}

  private OpenJ9Driver() {}

  static List<String> launchArguments(TargetProfile profile, Path directory, String observer) {
    var args = new ArrayList<String>();
    args.add(profile.java());
    if (profile.jit() != null) {
      // Compile actual modeled worker calls, with no extra invocation or graph mutation.
      if (profile.vmArgs().stream()
          .noneMatch(
              arg ->
                  arg.equals("-Xint")
                      || arg.equals("-Xnojit")
                      || arg.equalsIgnoreCase("-Djava.compiler=NONE")))
        args.add("-Xjit:count=0,limit={Case.worker*}");
    }
    args.addAll(profile.vmArgs());
    if (profile.jit() != null) {
      args.add("-Xshareclasses:none");
      args.add("-Xnoaot");
    }
    args.add("-Xgcpolicy:" + profile.collector());
    args.add("-Xdump:directory=" + directory.resolve("logs"));
    args.add("-agentpath:" + directory.resolve("support/" + observer));
    return args;
  }

  static boolean selected(String collector, Properties facts) {
    if (!facts.getProperty("collector.policy", "").equals("-Xgcpolicy:" + collector)) return false;
    var beans = Set.copyOf(List.of(facts.getProperty("collectors", "").split(",")));
    return beans.equals(
        switch (collector) {
          case "gencon" -> Set.of("scavenge", "global");
          case "balanced" -> Set.of("partial gc", "global garbage collect");
          case "optavgpause", "optthruput", "metronome" -> Set.of("global");
          default -> Set.of();
        });
  }

  static void identity(TargetProfile profile, Properties target, List<String> unmet) {
    if (!VmDriver.matches(profile, target))
      unmet.add("Expected OpenJ9, observed " + target.getProperty("java.vm.name", ""));
    if (!selected(profile.collector(), target))
      unmet.add("Requested OpenJ9 collector was not confirmed: " + profile.collector());
  }

  static Set<ExecutionEvidence.Frame> compiledFrames(Path path, long run, Properties target)
      throws IOException {
    StackEvidence evidence;
    try {
      evidence =
          StackEvidence.read(
              path,
              run,
              Long.parseLong(target.getProperty("process.pid")),
              Long.parseLong(target.getProperty("vm.start")));
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid OpenJ9 VM identity", malformed);
    }
    var result = new HashSet<ExecutionEvidence.Frame>();
    var identities = new HashSet<Identity>();
    for (var witness : evidence.witnesses()) {
      if (!identities.add(
          new Identity(
              witness.worker(),
              witness.group(),
              witness.round(),
              witness.phase(),
              witness.point(),
              witness.collection()))) throw new IOException("Duplicate OpenJ9 witness identity");
      boolean compiled = false;
      for (var frame : witness.frames())
        if (frame.owner().equals("LCase;")
            && frame.method().equals(ExecutionEvidence.workerMethod(witness.worker()))
            && frame
                .descriptor()
                .equals(
                    "(Lio/connglli/gorrect/runtime/TargetSession;Lio/connglli/gorrect/runtime/WorkerContext;)V")) {
          compiled = frame.kind() == 1 || frame.kind() == 2;
          break;
        }
      result.add(
          new ExecutionEvidence.Frame(
              witness.worker(),
              witness.group(),
              witness.round(),
              witness.phase(),
              witness.point(),
              witness.collection(),
              witness.thread(),
              compiled));
    }
    return Set.copyOf(result);
  }

  static Map<String, Path> installations(Path home) throws IOException {
    var paths = new java.util.LinkedHashMap<String, Path>();
    // OpenJ9 splits VM, GC, JIT, and JVMTI code across libraries; libjvm alone is insufficient.
    for (String name :
        List.of(
            "lib/default", "lib/compressedrefs", "lib/j9vm", "bin/default", "bin/compressedrefs")) {
      Path libraries = home.resolve(name);
      paths.put("target/" + name + "/options.default", libraries.resolve("options.default"));
      if (Files.isDirectory(libraries))
        try (var files = Files.list(libraries)) {
          for (var file : files.filter(Files::isRegularFile).sorted().toList())
            if (file.getFileName().toString().matches(".*\\.(so|dll|dylib)"))
              paths.put("target/" + name + "/" + file.getFileName(), file);
        }
    }
    return paths;
  }
}
