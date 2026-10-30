package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.fuzz.WorkloadGenerator;
import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirFormatter;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.ConcurrentHistory;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.oracle.HistoryLimitException;
import io.connglli.gorrect.engine.oracle.HistoryLimits;
import io.connglli.gorrect.engine.oracle.HistoryOracle;
import io.connglli.gorrect.engine.oracle.HistoryPlan;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Shared execution path for saved cases, probes, replay, and generated campaigns. */
public final class RunEngine {
  private static final String OBSERVER = System.mapLibraryName("gorrect_observer");
  private static final String PROBE =
      "gir 1; shape Probe object 1 1; object 1 Probe [1] [42]; root main = 1; program { checkpoint;"
          + " }";
  private static final String REFERENCE_PROBE =
      """
      gir 1; shape Probe object 2 1;
      object 1 Probe [soft(1), weak(1)] [42]; root main = 1;
      program {
        scope {
          read-root owner Probe main;
          acquire held Probe owner 0 { collect; }
          is-cleared cleared owner 1; write-data owner 0 cleared;
          clear-ref owner 1;
          acquire absent Probe owner 1 { write-data absent 0 99; }
          poll-queue;
          write-weak owner 1 owner;
        }
        checkpoint;
      }
      """;

  private static final String CONTENDED_PROBE =
      """
      gir 1; shape Probe object 0 1; object 1 Probe [] [0]; object 2 Probe [] [0];
      root left = 1; root right = 2; root winner = null;
      program { parallel {
        worker left { read-volatile-root n Probe left; compare-set-root won winner null n; write-data n 0 won; }
        worker right { read-volatile-root n Probe right; compare-set-root won winner null n; write-data n 0 won; }
      } checkpoint; }
      """;

  private RunEngine() {}

  /** Executes a saved GIR case and retains both successful and failed execution artifacts. */
  public static RunReport run(Path input, Path profileFile, Path output) throws IOException {
    return runText(Files.readString(input), profileFile, output);
  }

  /** Campaign time caps execution internally; profile fields remain the launch configuration. */
  public static RunReport runBounded(
      Path input, Path profileFile, Path output, Duration remaining, Map<String, Object> provenance)
      throws IOException {
    var raw = JsonFiles.read(profileFile);
    var profile = TargetProfile.parse(raw, profileFile.toAbsolutePath().getParent());
    return prepare(
        Files.readString(input),
        profile,
        raw,
        output.toAbsolutePath().normalize(),
        null,
        System.nanoTime() + remaining.toNanos(),
        provenance);
  }

  /** Exercises named-root capture through the same execution and verification path as a case. */
  public static RunReport probe(Path profileFile, Path output) throws IOException {
    return runText(null, profileFile, output);
  }

  private static RunReport runText(String text, Path profileFile, Path output) throws IOException {
    var raw = JsonFiles.read(profileFile);
    TargetProfile profile;
    try {
      profile = TargetProfile.parse(raw, profileFile.toAbsolutePath().getParent());
    } catch (RuntimeException exception) {
      throw new IOException("Invalid profile: " + exception.getMessage(), exception);
    }
    if (text == null) {
      text =
          profile.requirements().stream()
                  .anyMatch(
                      requirement ->
                          requirement.equals("reference-histories")
                              || requirement.equals("reference-queues"))
              ? REFERENCE_PROBE
              : PROBE;
      if (profile.requirements().contains("contended-histories")) text = CONTENDED_PROBE;
      if (profile.jit() != null
          || profile.requirements().stream().anyMatch(GirValidator.STRESS_REQUIREMENTS::contains)) {
        var generated =
            WorkloadGenerator.generate(
                0, "concurrent", 2, profile.jit() == null ? 2 : profile.jit().warmupRounds() + 1);
        var probe =
            new GirProgram(
                generated.version(),
                generated.shapes(),
                generated.objects(),
                generated.roots(),
                generated.body(),
                generated.labels(),
                java.util.Set.of());
        text = GirFormatter.format(probe);
      }
      // A combined capability probe still needs an unconditional, quiescent request.
      if (profile.requirements().contains("gc-completion")) {
        int end = text.lastIndexOf('}');
        text = text.substring(0, end) + "collect;\n" + text.substring(end);
      }
    }
    return prepare(text, profile, raw, output.toAbsolutePath().normalize(), null);
  }

  /**
   * Reuses validated inputs and exact executable bytes, with a fresh run token and observations.
   */
  public static RunReport replay(Path saved, Path output) throws IOException {
    saved = saved.toAbsolutePath().normalize();
    var manifest = manifest(saved);
    Artifacts.check(saved, manifest);
    var profile = TargetProfile.parse(manifest.getAsJsonObject("profile"), saved);
    return prepare(
        Files.readString(saved.resolve("case.gir")),
        profile,
        manifest.getAsJsonObject("inputProfile"),
        output.toAbsolutePath().normalize(),
        saved);
  }

  private static RunReport prepare(
      String text, TargetProfile profile, JsonObject inputProfile, Path directory, Path saved)
      throws IOException {
    return prepare(text, profile, inputProfile, directory, saved, null, Map.of());
  }

  private static RunReport prepare(
      String text,
      TargetProfile profile,
      JsonObject inputProfile,
      Path directory,
      Path saved,
      Long campaignDeadline,
      Map<String, Object> provenance)
      throws IOException {
    // Refuse replacement: even an unsuccessful run must keep its own diagnostics.
    Files.createDirectories(directory.getParent());
    Files.createDirectory(directory);
    for (String name : new String[] {"generated/classes", "snapshots", "traces", "logs", "support"})
      Files.createDirectories(directory.resolve(name));
    long run = new SecureRandom().nextLong(1, Long.MAX_VALUE);
    var manifest = new JsonObject();
    manifest.addProperty("version", 1);
    manifest.addProperty("run", run);
    manifest.addProperty("created", Instant.now().toString());
    manifest.add("profile", JsonFiles.GSON.toJsonTree(profile));
    manifest.add("inputProfile", inputProfile);
    manifest.add("provenance", JsonFiles.GSON.toJsonTree(provenance));
    if (campaignDeadline != null)
      manifest.addProperty(
          "campaignBudgetMillis",
          Math.max(0, TimeUnit.NANOSECONDS.toMillis(campaignDeadline - System.nanoTime())));
    Files.writeString(directory.resolve("case.gir"), text);
    JsonFiles.write(directory.resolve("manifest.json"), manifest);
    RunReport result;
    try {
      var program = GirParser.parse(text);
      boolean references = ReferenceProgram.usesReferences(program);
      boolean concurrent = HistoryProgram.usesConcurrentHistory(program);
      if ((references || concurrent)
          && program.shapes().stream().anyMatch(shape -> shape.kind() != Shape.Kind.OBJECT))
        return finish(
            directory,
            manifest,
            new RunReport(
                RunReport.Outcome.UNSUPPORTED,
                ExactVerifier.Verdict.INCONCLUSIVE,
                List.of("History programs currently require ordinary-object shapes"),
                List.of()));
      manifest.addProperty("referenceHistory", references);
      manifest.addProperty("concurrentHistory", concurrent);
      var oracle =
          (references || concurrent)
              ? HistoryPlan.plan(program, OracleLimits.DEFAULT)
              : Oracle.interpret(program);
      var unsupported = new ArrayList<String>();
      if (program.requirements().contains("compiled-execution") && profile.jit() == null)
        unsupported.add("compiled-execution requires profile JIT control");
      if (oracle.checkpoints().stream()
          .anyMatch(checkpoint -> !checkpoint.graph().handles().isEmpty()))
        unsupported.add("local-bindings: a checkpoint has live GIR reference bindings");
      if (!unsupported.isEmpty())
        return finish(
            directory,
            manifest,
            new RunReport(
                RunReport.Outcome.UNSUPPORTED,
                ExactVerifier.Verdict.INCONCLUSIVE,
                unsupported,
                List.of()));
      if (concurrent) ConcurrentHistory.audit(program, historyLimits(profile));
      if (saved == null) {
        var emission = JavaEmitter.emit(program);
        Files.writeString(directory.resolve("generated/Case.java"), emission.source());
        JsonFiles.write(directory.resolve("generated/layout.json"), emission.layout());
        var recorderCounts = new java.util.TreeMap<Integer, Long>();
        recorderCounts.put(0, 0L);
        oracle.events().forEach(event -> recorderCounts.merge(event.worker(), 1L, Long::sum));
        var recorderPlan = new StringBuilder();
        recorderCounts.forEach(
            (worker, count) -> recorderPlan.append(worker).append('=').append(count).append('\n'));
        Files.writeString(directory.resolve("generated/recorders.properties"), recorderPlan);
        if (references || concurrent)
          Files.writeString(directory.resolve("generated/references.properties"), "enabled=true\n");
        Files.writeString(
            directory.resolve("generated/gc.properties"),
            "control="
                + profile.gc().control()
                + "\nevidence="
                + profile.collectionEvidence()
                + "\n");
        if (profile.jit() != null)
          Files.writeString(
              directory.resolve("generated/jit.properties"),
              "level="
                  + profile.jit().level()
                  + "\nwarmupRounds="
                  + profile.jit().warmupRounds()
                  + "\ndeadlineMillis="
                  + profile.jit().deadlineMillis()
                  + "\n");
        Files.copy(Path.of(profile.support().runtime()), directory.resolve("support/runtime.jar"));
        Files.copy(
            Path.of(profile.support().protocol()), directory.resolve("support/protocol.jar"));
        Files.copy(Path.of(profile.support().observer()), directory.resolve("support/" + OBSERVER));
        if (profile.support().whitebox() != null) {
          try (var jar = new java.util.jar.JarFile(profile.support().whitebox())) {
            if (jar.stream()
                .anyMatch(
                    entry ->
                        entry.getName().equals("Case.class")
                            || entry.getName().startsWith("Case$")
                            || entry.getName().startsWith("io/connglli/gorrect/")))
              throw new IOException("WhiteBox client shadows generated or runtime classes");
          }
          Files.copy(
              Path.of(profile.support().whitebox()), directory.resolve("support/whitebox.jar"));
        }
        Path compiler = compiler();
        manifest.add(
            "compiler",
            JsonFiles.GSON.toJsonTree(
                Map.of(
                    "executable",
                    compiler.toString(),
                    "sha256",
                    Artifacts.hash(compiler),
                    "engineRuntime",
                    System.getProperty("java.runtime.version"))));
        manifest.add("hashes", JsonFiles.GSON.toJsonTree(Artifacts.inputs(directory)));
        var compilation = compile(directory, profile, campaignDeadline);
        if (compilation != null) return finish(directory, manifest, compilation);
      } else {
        // Output directories exist, so copy children rather than replacing the run directory.
        for (String name : new String[] {"generated", "support"}) {
          try (var children = Files.list(saved.resolve(name))) {
            for (var child : children.toList()) {
              Path target = directory.resolve(name).resolve(child.getFileName());
              if (Files.isDirectory(target)) Files.delete(target);
              Artifacts.copyTree(child, target);
            }
          }
        }
        var previous = manifest(saved);
        Artifacts.check(directory, previous);
        manifest.add("compiler", previous.get("compiler"));
        if (previous.has("provenance")) manifest.add("provenance", previous.get("provenance"));
        manifest.addProperty("replayOf", saved.toString());
      }
      var installations = installations(profile);
      manifest.add("installationArtifacts", JsonFiles.GSON.toJsonTree(installations));
      var changes = new ArrayList<String>();
      if (saved != null) {
        var previous = manifest(saved).getAsJsonObject("installationArtifacts");
        var keys = new java.util.TreeSet<>(installations.keySet());
        keys.addAll(previous.keySet());
        for (String key : keys)
          if (!previous.has(key) || !previous.get(key).getAsString().equals(installations.get(key)))
            changes.add(key);
      }
      manifest.add("changedInstallationArtifacts", JsonFiles.GSON.toJsonTree(changes));
      manifest.add("hashes", JsonFiles.GSON.toJsonTree(Artifacts.inputs(directory)));
      JsonFiles.write(directory.resolve("manifest.json"), manifest);
      Map<String, String> savedEnvironment =
          saved == null
              ? null
              : JsonFiles.GSON.fromJson(
                  JsonFiles.required(manifest(saved), "environment"),
                  new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
      result =
          launch(directory, profile, manifest, oracle, run, savedEnvironment, campaignDeadline);
    } catch (OracleLimitException | HistoryLimitException exception) {
      result = RunReport.failure(RunReport.Outcome.CHECKING_LIMIT, exception.getMessage());
    } catch (UnsupportedHistoryException exception) {
      result = RunReport.failure(RunReport.Outcome.UNSUPPORTED, exception.getMessage());
    } catch (GirException exception) {
      result = RunReport.failure(RunReport.Outcome.INVALID_WORKLOAD, exception.getMessage());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      result = RunReport.failure(RunReport.Outcome.INTERRUPTED, "Engine interrupted");
    } catch (IOException exception) {
      result = RunReport.failure(RunReport.Outcome.STARTUP_FAILURE, exception.toString());
    }
    return finish(directory, manifest, result);
  }

  private static RunReport compile(Path directory, TargetProfile profile, Long campaignDeadline)
      throws IOException, InterruptedException {
    Path compiler = compiler();
    var command =
        List.of(
            compiler.toString(),
            "--release",
            "17",
            "-Xlint:all",
            "-Werror",
            "-cp",
            classpath(directory, false),
            "-d",
            directory.resolve("generated/classes").toString(),
            directory.resolve("generated/Case.java").toString());
    JsonFiles.write(directory.resolve("logs/compiler-command.json"), command);
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectOutput(directory.resolve("logs/compiler.stdout").toFile())
            .redirectError(directory.resolve("logs/compiler.stderr").toFile());
    cleanEnvironment(builder, Map.of());
    long limit = profile.limits().compileSeconds() * 1000L;
    boolean campaignBound =
        campaignDeadline != null
            && TimeUnit.NANOSECONDS.toMillis(campaignDeadline - System.nanoTime()) < limit;
    if (campaignDeadline != null)
      limit = Math.min(limit, TimeUnit.NANOSECONDS.toMillis(campaignDeadline - System.nanoTime()));
    if (limit <= 0)
      return RunReport.failure(
          RunReport.Outcome.CAMPAIGN_LIMIT, "Campaign deadline reached before compilation");
    Process process;
    try {
      process = builder.start();
    } catch (IOException exception) {
      return RunReport.failure(
          RunReport.Outcome.COMPILATION_FAILURE, "Cannot start javac: " + exception);
    }
    try {
      if (!process.waitFor(limit, TimeUnit.MILLISECONDS))
        return RunReport.failure(
            campaignBound ? RunReport.Outcome.CAMPAIGN_LIMIT : RunReport.Outcome.TIMEOUT,
            "Compilation deadline exceeded");
      return process.exitValue() == 0
          ? null
          : RunReport.failure(
              RunReport.Outcome.COMPILATION_FAILURE, "javac exited with " + process.exitValue());
    } finally {
      terminate(process);
    }
  }

  private static RunReport launch(
      Path directory,
      TargetProfile profile,
      JsonObject manifest,
      OracleResult oracle,
      long run,
      Map<String, String> savedEnvironment,
      Long campaignDeadline)
      throws IOException, InterruptedException {
    var messages = new ArrayList<ControlMessage>();
    Process process = null;
    boolean hello = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.limits().runSeconds());
    if (campaignDeadline != null) deadline = Math.min(deadline, campaignDeadline);
    long startupDeadline =
        Math.min(
            deadline,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.limits().startupSeconds()));
    try (var control = new LocalControl()) {
      manifest.addProperty("controlTransport", "unix");
      manifest.addProperty("controlSocket", control.address().toString());
      var resolution = VmProbe.resolve(profile, directory, savedEnvironment, campaignDeadline);
      if (resolution.failure() != null) return resolution.failure();
      profile = resolution.profile();
      manifest.add("profile", JsonFiles.GSON.toJsonTree(profile));
      var command = new ArrayList<>(VmDriver.launchArguments(profile, directory, OBSERVER));
      command.addAll(
          List.of(
              "-cp",
              classpath(directory, true),
              "Case",
              control.address().toString(),
              Long.toString(run),
              directory.toString(),
              directory.resolve("support/" + OBSERVER).toString(),
              Integer.toString(profile.limits().records()),
              Integer.toString(profile.limits().traceEvents())));
      var builder =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectOutput(directory.resolve("logs/stdout.log").toFile())
              .redirectError(directory.resolve("logs/stderr.log").toFile());
      if (savedEnvironment == null) cleanEnvironment(builder, profile.environment());
      else {
        builder.environment().clear();
        builder.environment().putAll(savedEnvironment);
      }
      manifest.add("command", JsonFiles.GSON.toJsonTree(command));
      manifest.add("environment", JsonFiles.GSON.toJsonTree(builder.environment()));
      JsonFiles.write(directory.resolve("manifest.json"), manifest);
      milliseconds(deadline);
      process = builder.start();
      control.accept(startupDeadline);
      var in = control.input();
      var out = control.output();
      var first = ControlMessage.read(in, run);
      messages.add(first);
      if (first.kind() != ControlMessage.Kind.HELLO || first.sequence() != 0)
        return RunReport.failure(RunReport.Outcome.PROTOCOL_ERROR, "Missing startup handshake");
      hello = true;
      var target = target(directory);
      List<String> unmet;
      try {
        unmet = VmDriver.requirements(profile, target);
      } catch (RuntimeException exception) {
        return RunReport.failure(
            RunReport.Outcome.PROTOCOL_ERROR, "Invalid target capability report: " + exception);
      }
      manifest.add("target", JsonFiles.GSON.toJsonTree(target));
      if (manifest.has("referenceHistory")
          && manifest.get("referenceHistory").getAsBoolean()
          && (Long.parseLong(target.getProperty("observer.capabilities"))
                  & io.connglli.gorrect.protocol.HeapSnapshot.REFERENCE_FIELDS)
              == 0) unmet.add("reference-histories");
      if (!unmet.isEmpty())
        return new RunReport(
            RunReport.Outcome.UNSUPPORTED, ExactVerifier.Verdict.INCONCLUSIVE, unmet, List.of());
      control.deadline(deadline);
      acknowledge(out, run, 0);
      int index = 0;
      while (true) {
        var message = ControlMessage.read(in, run);
        messages.add(message);
        if (message.kind() == ControlMessage.Kind.FAILED)
          return RunReport.failure(RunReport.Outcome.RUNTIME_ERROR, message.detail());
        if (message.kind() == ControlMessage.Kind.COMPLETE) {
          if (index != oracle.checkpoints().size() + 1 || message.sequence() != index)
            return RunReport.failure(
                RunReport.Outcome.PROTOCOL_ERROR, "Premature target completion");
          if (!process.waitFor(milliseconds(deadline), TimeUnit.MILLISECONDS))
            return RunReport.failure(
                campaignDeadline != null && deadline == campaignDeadline
                    ? RunReport.Outcome.CAMPAIGN_LIMIT
                    : RunReport.Outcome.TIMEOUT,
                "Target did not exit");
          return process.exitValue() == 0
              ? new RunReport(
                  RunReport.Outcome.COMPLETED,
                  ExactVerifier.Verdict.INCONCLUSIVE,
                  List.of(),
                  List.of())
              : RunReport.failure(
                  RunReport.Outcome.CRASH, "Target exited with " + process.exitValue());
        }
        int operation =
            index < oracle.checkpoints().size() ? oracle.checkpoints().get(index).operation() : 0;
        long occurrence =
            index < oracle.checkpoints().size() ? oracle.checkpoints().get(index).occurrence() : 1;
        if (index > oracle.checkpoints().size()
            || message.kind() != ControlMessage.Kind.CHECKPOINT
            || message.sequence() != index + 1L
            || message.operation() != operation
            || message.occurrence() != occurrence)
          return RunReport.failure(
              RunReport.Outcome.PROTOCOL_ERROR, "Checkpoint order or identity mismatch");
        try {
          snapshot(directory, run, message.sequence());
        } catch (IOException exception) {
          return RunReport.failure(RunReport.Outcome.EXTRACTION_ERROR, exception.toString());
        }
        acknowledge(out, run, ++index);
      }
    } catch (SocketTimeoutException exception) {
      if (!hello && process != null && !process.isAlive())
        return RunReport.failure(
            RunReport.Outcome.STARTUP_FAILURE,
            "Target exited before handshake: " + process.exitValue());
      return RunReport.failure(
          campaignDeadline != null
                  && (hello ? deadline == campaignDeadline : startupDeadline == campaignDeadline)
              ? RunReport.Outcome.CAMPAIGN_LIMIT
              : RunReport.Outcome.TIMEOUT,
          hello ? "Run deadline exceeded" : "Startup deadline exceeded");
    } catch (IOException exception) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException("Control operation interrupted");
      if (!hello) return RunReport.failure(RunReport.Outcome.STARTUP_FAILURE, exception.toString());
      // EOF can arrive just before the OS publishes the target's exit status.
      if (process != null) process.waitFor(100, TimeUnit.MILLISECONDS);
      if (process != null && !process.isAlive() && process.exitValue() != 0)
        return RunReport.failure(
            RunReport.Outcome.CRASH,
            "Target exited with " + process.exitValue() + ": " + exception);
      return RunReport.failure(RunReport.Outcome.PROTOCOL_ERROR, exception.toString());
    } catch (UnsupportedOperationException exception) {
      return new RunReport(
          RunReport.Outcome.UNSUPPORTED,
          ExactVerifier.Verdict.INCONCLUSIVE,
          List.of("Unix domain sockets"),
          List.of(exception.toString()));
    } finally {
      if (process != null) {
        manifest.addProperty("terminatedByEngine", process.isAlive());
        terminate(process);
        manifest.addProperty("targetExit", process.isAlive() ? -1 : process.exitValue());
      }
      var transcript = new JsonObject();
      transcript.addProperty("version", 1);
      transcript.addProperty("run", run);
      transcript.add("messages", JsonFiles.GSON.toJsonTree(messages));
      JsonFiles.write(directory.resolve("traces/control.json"), transcript);
    }
  }

  /** Re-derives expectations exclusively from saved GIR; never needs the profile's installation. */
  public static RunReport verify(Path directory) throws IOException {
    directory = directory.toAbsolutePath().normalize();
    RunReport report;
    try {
      var manifest = manifest(directory);
      var execution =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("execution.json")), RunReport.class);
      if (execution.execution() != RunReport.Outcome.COMPLETED) {
        report = diagnoseReferenceFailure(directory, manifest, execution);
        JsonFiles.write(directory.resolve("result.json"), report);
        return report;
      }
      Artifacts.check(directory, manifest);
      long run = JsonFiles.number(JsonFiles.required(manifest, "run"));
      var profile = TargetProfile.parse(manifest.getAsJsonObject("profile"), directory);
      var program = GirParser.parse(Files.readString(directory.resolve("case.gir")));
      boolean references = ReferenceProgram.usesReferences(program);
      boolean concurrent = HistoryProgram.usesConcurrentHistory(program);
      var oracle =
          (references || concurrent)
              ? HistoryPlan.plan(program, OracleLimits.DEFAULT)
              : Oracle.interpret(program);
      var layout =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("generated/layout.json")), ObservationLayout.class);
      var problems = new ArrayList<String>();
      var unmet = new ArrayList<String>();
      unmet.addAll(VmDriver.requirements(profile, target(directory)));
      var trace = ExecutionTrace.read(directory.resolve("traces/execution.gtrace"), run);
      if (!trace.complete()) throw new IOException("Execution trace is incomplete");
      if (references || concurrent) {
        controls(directory, run, oracle);
        var observations = new ArrayList<HistoryOracle.Observation>();
        for (int index = 1; index <= oracle.checkpoints().size() + 1; index++) {
          var snapshot = snapshot(directory, run, index);
          compilationCoverage(snapshot, profile, unmet);
          var projection = SnapshotProjector.project(snapshot, layout);
          observations.add(
              new HistoryOracle.Observation(projection.graph(), projection.problems()));
        }
        var history = ReferenceHistory.read(directory.resolve("traces/references.ghistory"), run);
        if (!history.complete()) throw new IOException("Reference history is incomplete");
        var result =
            concurrent
                ? ConcurrentHistory.check(
                    program, trace, history, observations, historyLimits(profile))
                : HistoryOracle.check(
                    program, trace, history, observations, historyLimits(profile));
        problems.addAll(result.problems());
        if (result.execution() != null) {
          var coverage =
              StressVerifier.verify(directory, run, program, result.execution(), profile);
          for (String requirement : coverage.unmet())
            if (!unmet.contains(requirement)) unmet.add(requirement);
          problems.addAll(coverage.problems());
          var completion =
              CollectionVerifier.verify(
                  directory, run, program, result.execution(), profile, target(directory));
          for (String requirement : completion.unmet())
            if (!unmet.contains(requirement)) unmet.add(requirement);
          problems.addAll(completion.problems());
        }
        report =
            new RunReport(
                result.invalidWorkload()
                    ? RunReport.Outcome.INVALID_WORKLOAD
                    : RunReport.Outcome.COMPLETED,
                result.verdict(),
                unmet,
                problems);
        JsonFiles.write(directory.resolve("result.json"), report);
        return report;
      }
      var stress = StressVerifier.verify(directory, run, program, oracle, profile);
      for (String requirement : stress.unmet())
        if (!unmet.contains(requirement)) unmet.add(requirement);
      problems.addAll(stress.problems());
      var completion =
          CollectionVerifier.verify(directory, run, program, oracle, profile, target(directory));
      for (String requirement : completion.unmet())
        if (!unmet.contains(requirement)) unmet.add(requirement);
      problems.addAll(completion.problems());
      boolean violated =
          !trace.byWorker().equals(new ExecutionTrace(run, true, oracle.events()).byWorker());
      if (violated) problems.add("Operation order or scalar-read trace differs from GIR");
      controls(directory, run, oracle);
      boolean inconclusive = false;
      for (int i = 0; i <= oracle.checkpoints().size(); i++) {
        var visit =
            i < oracle.checkpoints().size()
                ? oracle.checkpoints().get(i)
                : new OracleResult.Checkpoint(0, 1, oracle.finalGraph());
        var snapshot = snapshot(directory, run, i + 1L);
        compilationCoverage(snapshot, profile, unmet);
        var result = SnapshotProjector.project(snapshot, layout).verify(visit.graph());
        if (result.verdict() == ExactVerifier.Verdict.INCONCLUSIVE) inconclusive = true;
        if (result.verdict() == ExactVerifier.Verdict.VIOLATED) violated = true;
        for (String problem : result.differences())
          problems.add("Checkpoint " + (i + 1) + ": " + problem);
      }
      // Extraction gaps cannot become a pass, even when another checkpoint is fully covered.
      var verdict =
          violated
              ? ExactVerifier.Verdict.VIOLATED
              : inconclusive ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.SATISFIED;
      report = new RunReport(RunReport.Outcome.COMPLETED, verdict, unmet, problems);
    } catch (OracleLimitException | HistoryLimitException exception) {
      report = RunReport.failure(RunReport.Outcome.CHECKING_LIMIT, exception.getMessage());
    } catch (UnsupportedHistoryException exception) {
      report = RunReport.failure(RunReport.Outcome.UNSUPPORTED, exception.getMessage());
    } catch (GirException exception) {
      report = RunReport.failure(RunReport.Outcome.INVALID_WORKLOAD, exception.getMessage());
    } catch (IOException | RuntimeException exception) {
      report = RunReport.failure(RunReport.Outcome.EXTRACTION_ERROR, exception.toString());
    }
    JsonFiles.write(directory.resolve("result.json"), report);
    return report;
  }

  private static RunReport finish(Path directory, JsonObject manifest, RunReport execution)
      throws IOException {
    JsonFiles.write(directory.resolve("manifest.json"), manifest);
    JsonFiles.write(directory.resolve("execution.json"), execution);
    if (execution.execution() == RunReport.Outcome.COMPLETED
        || execution.execution() == RunReport.Outcome.RUNTIME_ERROR) return verify(directory);
    JsonFiles.write(directory.resolve("result.json"), execution);
    return execution;
  }

  private static RunReport diagnoseReferenceFailure(
      Path directory, JsonObject manifest, RunReport execution) {
    if (execution.execution() != RunReport.Outcome.RUNTIME_ERROR
        || manifest.has("concurrentHistory") && manifest.get("concurrentHistory").getAsBoolean()
        || !manifest.has("referenceHistory")
        || !manifest.get("referenceHistory").getAsBoolean()) return execution;
    try {
      Artifacts.check(directory, manifest);
      long run = JsonFiles.number(JsonFiles.required(manifest, "run"));
      var program = GirParser.parse(Files.readString(directory.resolve("case.gir")));
      var profile = TargetProfile.parse(manifest.getAsJsonObject("profile"), directory);
      var layout =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("generated/layout.json")), ObservationLayout.class);
      var observations = new ArrayList<HistoryOracle.Observation>();
      for (int index = 1;
          Files.exists(directory.resolve("snapshots/" + index + ".gsnap"));
          index++) {
        var projection = SnapshotProjector.project(snapshot(directory, run, index), layout);
        observations.add(new HistoryOracle.Observation(projection.graph(), projection.problems()));
      }
      var result =
          HistoryOracle.checkFailure(
              program,
              ExecutionTrace.read(directory.resolve("traces/execution.gtrace"), run),
              ReferenceHistory.read(directory.resolve("traces/references.ghistory"), run),
              observations,
              new HistoryLimits(
                  profile.limits().historyStates(), profile.limits().historyTransitions()));
      if (result.invalidWorkload()) {
        var report =
            new RunReport(
                RunReport.Outcome.INVALID_WORKLOAD,
                ExactVerifier.Verdict.INCONCLUSIVE,
                execution.unmetRequirements(),
                result.problems());
        return report;
      }
    } catch (IOException | RuntimeException ignored) {
      // Partial failure artifacts cannot establish a legal path or override the execution error.
    }
    return execution;
  }

  private static ControlMessage[] controls(Path directory, long run, OracleResult oracle)
      throws IOException {
    var transcript = JsonFiles.read(directory.resolve("traces/control.json"));
    if (JsonFiles.number(JsonFiles.required(transcript, "version")) != 1
        || JsonFiles.number(JsonFiles.required(transcript, "run")) != run)
      throw new IOException("Control transcript identity mismatch");
    var messages =
        JsonFiles.GSON.fromJson(JsonFiles.required(transcript, "messages"), ControlMessage[].class);
    if (messages.length != oracle.checkpoints().size() + 3
        || messages[0].kind() != ControlMessage.Kind.HELLO
        || messages[0].sequence() != 0
        || messages[messages.length - 1].kind() != ControlMessage.Kind.COMPLETE
        || messages[messages.length - 1].sequence() != oracle.checkpoints().size() + 1L)
      throw new IOException("Control transcript is incomplete");
    for (var message : messages)
      if (message.run() != run) throw new IOException("Control transcript contains another run");
    for (int index = 0; index <= oracle.checkpoints().size(); index++) {
      var expected =
          index < oracle.checkpoints().size()
              ? oracle.checkpoints().get(index)
              : new OracleResult.Checkpoint(0, 1, oracle.finalGraph());
      var message = messages[index + 1];
      if (message.kind() != ControlMessage.Kind.CHECKPOINT
          || message.sequence() != index + 1L
          || message.operation() != expected.operation()
          || message.occurrence() != expected.occurrence())
        throw new IOException("Control checkpoint identity mismatch");
    }
    return messages;
  }

  private static void compilationCoverage(
      io.connglli.gorrect.protocol.HeapSnapshot snapshot,
      TargetProfile profile,
      List<String> unmet) {
    if (profile.requirements().contains("compilation-events")
        && (snapshot.capabilities()
                & io.connglli.gorrect.protocol.HeapSnapshot.COMPILATION_OVERFLOW)
            != 0
        && !unmet.contains("compilation-events")) unmet.add("compilation-events");
  }

  private static HistoryLimits historyLimits(TargetProfile profile) {
    return new HistoryLimits(
        profile.limits().historyStates(), profile.limits().historyTransitions());
  }

  private static JsonObject manifest(Path directory) throws IOException {
    var value = JsonFiles.read(directory.resolve("manifest.json"));
    if (JsonFiles.number(JsonFiles.required(value, "version")) != 1)
      throw new IOException("Unsupported run manifest");
    return value;
  }

  private static Properties target(Path directory) throws IOException {
    var target = new Properties();
    try (var input = Files.newInputStream(directory.resolve("target.properties"))) {
      target.load(input);
    }
    return target;
  }

  private static io.connglli.gorrect.protocol.HeapSnapshot snapshot(
      Path directory, long run, long checkpoint) throws IOException {
    Path file = directory.resolve("snapshots/" + checkpoint + ".gsnap");
    if (Files.size(file) > SnapshotCodec.MAX_BYTES)
      throw new IOException("Snapshot exceeds byte limit");
    return SnapshotCodec.read(Files.readAllBytes(file), run, checkpoint);
  }

  private static Map<String, String> installations(TargetProfile profile) throws IOException {
    var paths = new LinkedHashMap<String, Path>();
    paths.put("java", Path.of(profile.java()));
    paths.put("runtime", Path.of(profile.support().runtime()));
    paths.put("protocol", Path.of(profile.support().protocol()));
    paths.put("observer", Path.of(profile.support().observer()));
    if (profile.support().whitebox() != null)
      paths.put("whitebox", Path.of(profile.support().whitebox()));
    if (Files.exists(Path.of(profile.java()))) {
      Path home = Path.of(profile.java()).toRealPath().getParent().getParent();
      paths.putAll(VmDriver.installations(profile, home));
    }
    var hashes = new LinkedHashMap<String, String>();
    for (var entry : paths.entrySet())
      hashes.put(
          entry.getKey(),
          Files.isRegularFile(entry.getValue()) ? Artifacts.hash(entry.getValue()) : "missing");
    return hashes;
  }

  private static String classpath(Path directory, boolean generated) {
    var paths = new ArrayList<String>();
    if (generated) paths.add(directory.resolve("generated/classes").toString());
    paths.add(directory.resolve("support/runtime.jar").toString());
    paths.add(directory.resolve("support/protocol.jar").toString());
    return String.join(java.io.File.pathSeparator, paths);
  }

  private static Path compiler() {
    return Path.of(
        System.getProperty("java.home"),
        "bin",
        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac");
  }

  static void cleanEnvironment(ProcessBuilder builder, Map<String, String> additions) {
    builder
        .environment()
        .keySet()
        .removeIf(
            name ->
                TargetProfile.ENVIRONMENT_OPTIONS.contains(
                    name.toUpperCase(java.util.Locale.ROOT)));
    builder.environment().putAll(additions);
  }

  static int milliseconds(long deadline) throws SocketTimeoutException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining <= 0) throw new SocketTimeoutException("Run deadline exceeded");
    return (int) Math.min(remaining, Integer.MAX_VALUE);
  }

  private static void acknowledge(DataOutputStream out, long run, long sequence)
      throws IOException {
    new ControlMessage(ControlMessage.Kind.ACK, run, sequence, 0, 0, "").write(out);
  }

  static void terminate(Process process) {
    if (!process.isAlive()) return;
    process.descendants().forEach(child -> child.destroyForcibly());
    process.destroy();
    try {
      if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
