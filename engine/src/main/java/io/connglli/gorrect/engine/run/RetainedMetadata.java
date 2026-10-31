package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.RetainedBindings;
import io.connglli.gorrect.engine.observe.RetainedLocalMetadata;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.ObserverPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checks retained provenance and frame coordinates without changing snapshots or inventing roots.
 */
final class RetainedMetadata {
  private static final int FLAGS =
      CodeEvidence.PREPARED
          | CodeEvidence.INTENDED_BOUND
          | CodeEvidence.SYSTEM_LOADER
          | CodeEvidence.JVMBCI;
  private static final String EXECUTE = "(Lio/connglli/gorrect/runtime/TargetSession;)V";
  private static final String WORKER =
      "(Lio/connglli/gorrect/runtime/TargetSession;Lio/connglli/gorrect/runtime/WorkerContext;)V";
  private static final String MAIN = "([Ljava/lang/String;)V";

  private RetainedMetadata() {}

  /** Checks provider agreement; absent plan/layout metadata is the defined legacy VM-table rule. */
  static void selection(Path directory, TargetProfile profile, ObservationLayout layout)
      throws IOException {
    String selected = profile.bindingMetadata();
    if (!selected.equals(layout.bindingMetadata()))
      throw new IOException("Profile/layout metadata provider mismatch");
    Path path = directory.resolve("generated/observer.properties");
    if (!Files.exists(path)) {
      if (profile.retainedMetadata()) throw new IOException("Missing retained observer plan");
      return;
    }
    if (!ObserverPlan.read(path).bindingMetadata().equals(selected))
      throw new IOException("Conflicting observer plan");
  }

  /** Startup is checked before HELLO acknowledgement and again by offline verification. */
  static CodeEvidence startup(Path directory, long run) throws IOException {
    var evidence =
        CodeEvidence.read(directory.resolve("traces/code.gcode"), run, CodeEvidence.Stage.STARTUP);
    valid(evidence);
    byte[] code = evidence.code();
    Path retained = directory.resolve("generated/classes/Case.class");
    if (Files.size(retained) > CodeEvidence.MAX_CODE_BYTES
        || !Arrays.equals(code, Files.readAllBytes(retained)))
      throw new IOException("Loaded Case bytes differ from retained Case.class");
    var metadata = RetainedLocalMetadata.read(code, "LCase;");
    var expected =
        metadata.methods().stream()
            .map(method -> List.of(method.name(), method.descriptor()))
            .collect(Collectors.toSet());
    var actual =
        evidence.methods().stream()
            .map(method -> List.of(method.name(), method.descriptor()))
            .collect(Collectors.toSet());
    if (!actual.equals(expected))
      throw new IOException("Certified methods differ from loaded class bytes");
    return evidence;
  }

  /**
   * Missing or invalid terminal status prevents a pass without invalidating earlier raw evidence.
   */
  static void completion(Path directory, long run) throws IOException {
    var startup = startup(directory, run);
    var terminal =
        CodeEvidence.read(
            directory.resolve("traces/code-final.gcode"), run, CodeEvidence.Stage.FINAL);
    valid(terminal);
    if (terminal.epoch() != startup.epoch()
        || terminal.classToken() != startup.classToken()
        || terminal.loaderToken() != startup.loaderToken()
        || terminal.flags() != startup.flags())
      throw new IOException("Final code certificate differs from startup certificate");
  }

  /**
   * Matches every parked participant to its independently selected role before physical mapping.
   */
  static RetainedBindings capture(
      Path directory, HeapSnapshot snapshot, List<BindingEvidence> participants, boolean terminal)
      throws IOException {
    var code = startup(directory, snapshot.run());
    if ((snapshot.capabilities() & HeapSnapshot.LOCAL_VARIABLES) != 0)
      throw new IOException("Retained capture unexpectedly enables local-variable access");
    var evidence =
        FrameEvidence.read(
            directory.resolve("snapshots/" + snapshot.checkpoint() + ".gframe"),
            snapshot.run(),
            snapshot.checkpoint());
    if (evidence.status() != CodeEvidence.Status.VALID
        || evidence.jvmtiError() != 0
        || evidence.beforeEpoch() != code.epoch()
        || evidence.afterEpoch() != code.epoch()
        || evidence.participants().size() != participants.size())
      throw new IOException("Incomplete or changed frame certificate");
    var tables = tables(code);
    checkRawMethods(snapshot, code);
    var frames = new HashMap<Integer, RetainedBindings.Participant>();
    for (int index = 0; index < participants.size(); index++) {
      var binding = participants.get(index);
      var participant = evidence.participants().get(index);
      var role =
          binding.worker() == 0
              ? terminal ? FrameEvidence.Role.MAIN : FrameEvidence.Role.EXECUTE
              : FrameEvidence.Role.WORKER;
      if (participant.worker() != binding.worker()
          || participant.thread() != binding.thread()
          || participant.role() != role
          || terminal && (participants.size() != 1 || !binding.bindings().isEmpty()))
        throw new IOException("Frame participant role/thread/order mismatch");
      String name =
          role == FrameEvidence.Role.MAIN
              ? "main"
              : role == FrameEvidence.Role.EXECUTE
                  ? "execute"
                  : ExecutionEvidence.workerMethod(binding.worker());
      String descriptor =
          role == FrameEvidence.Role.MAIN
              ? MAIN
              : role == FrameEvidence.Role.EXECUTE ? EXECUTE : WORKER;
      var expected =
          code.methods().stream()
              .filter(
                  method -> method.name().equals(name) && method.descriptor().equals(descriptor))
              .toList();
      if (expected.size() != 1 || !tables.lengths().containsKey(expected.getFirst().token()))
        throw new IOException("Missing certified role method or local-variable table");
      long token = expected.getFirst().token();
      if (terminal) {
        var departed =
            code.methods().stream()
                .filter(
                    method ->
                        method.name().equals("execute") && method.descriptor().equals(EXECUTE)
                            || method.name().matches("worker[0-9]+")
                                && method.descriptor().equals(WORKER))
                .map(CodeEvidence.Method::token)
                .collect(Collectors.toSet());
        if (participant.before().stream().anyMatch(frame -> departed.contains(frame.method()))
            || participant.after().stream().anyMatch(frame -> departed.contains(frame.method())))
          throw new IOException("Terminal capture still contains a workload role activation");
      }
      var before = participant.before().stream().filter(frame -> frame.method() == token).toList();
      var after = participant.after().stream().filter(frame -> frame.method() == token).toList();
      if (before.size() != 1
          || after.size() != 1
          || !before.getFirst().equals(after.getFirst())
          || before.getFirst().bci() < 0
          || before.getFirst().bci() >= tables.lengths().get(token))
        throw new IOException("Missing, ambiguous or unstable parked role frame");
      frames.put(
          binding.worker(),
          new RetainedBindings.Participant(binding.thread(), before.getFirst(), role));
    }
    return new RetainedBindings(tables.methods(), frames);
  }

  private record Tables(Map<Long, HeapSnapshot.MethodInfo> methods, Map<Long, Integer> lengths) {}

  private static Tables tables(CodeEvidence code) throws IOException {
    var classInfo = RetainedLocalMetadata.read(code.code(), "LCase;");
    var methods = new HashMap<Long, HeapSnapshot.MethodInfo>();
    var lengths = new HashMap<Long, Integer>();
    for (var certified : code.methods()) {
      var method =
          classInfo.methods().stream()
              .filter(
                  candidate ->
                      candidate.name().equals(certified.name())
                          && candidate.descriptor().equals(certified.descriptor()))
              .findFirst()
              .orElseThrow();
      if (method.code().isEmpty()) continue;
      var scopes = method.code().orElseThrow();
      // Missing debug tables cannot become empty successful binding metadata.
      if (!scopes.hasLocalVariableTable()) continue;
      methods.put(
          certified.token(),
          new HeapSnapshot.MethodInfo(
              certified.token(),
              "LCase;",
              certified.name(),
              certified.descriptor(),
              scopes.locals().stream()
                  .map(
                      local ->
                          new HeapSnapshot.LocalVariable(
                              local.start(),
                              local.length(),
                              local.slot(),
                              local.name(),
                              local.descriptor()))
                  .toList()));
      lengths.put(certified.token(), scopes.length());
    }
    return new Tables(methods, lengths);
  }

  private static void checkRawMethods(HeapSnapshot snapshot, CodeEvidence code) throws IOException {
    var tokens = new HashSet<Long>();
    for (var method : snapshot.methods()) {
      if (method.token() == 0 || !tokens.add(method.token()))
        throw new IOException("Invalid or duplicate raw method token");
      var certified =
          code.methods().stream()
              .filter(candidate -> candidate.token() == method.token())
              .findFirst();
      if ((method.owner().equals("LCase;") || certified.isPresent())
          && (certified.isEmpty()
              || !method.owner().equals("LCase;")
              || !certified.orElseThrow().name().equals(method.name())
              || !certified.orElseThrow().descriptor().equals(method.descriptor())))
        throw new IOException("Raw method metadata conflicts with certified class");
    }
  }

  private static void valid(CodeEvidence evidence) throws IOException {
    if (evidence.status() != CodeEvidence.Status.VALID
        || evidence.jvmtiError() != 0
        || evidence.flags() != FLAGS
        || evidence.classToken() <= 0
        || evidence.loaderToken() <= 0)
      throw new IOException("Loaded Case certification unavailable: " + evidence.status());
  }
}
