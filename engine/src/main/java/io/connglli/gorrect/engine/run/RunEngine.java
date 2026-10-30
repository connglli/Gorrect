package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.Artifacts.manifest;
import static io.connglli.gorrect.engine.run.RunVerifier.historyLimits;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
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
import io.connglli.gorrect.engine.oracle.ConcurrentHistory;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.oracle.HistoryLimitException;
import io.connglli.gorrect.engine.oracle.HistoryPlan;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Shared execution path for saved cases, probes, replay, and generated campaigns. */
public final class RunEngine {
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

  /**
   * Prepares, compiles, executes, and verifies a GIR file using one target profile. Output must be
   * a fresh directory; both successful and failed attempts retain a bundle when preparation reaches
   * bundle creation. Execution and validation outcomes are returned as separate report dimensions.
   *
   * @throws IOException if inputs cannot be read or required bundle files cannot be created/written
   */
  public static RunReport run(Path input, Path profileFile, Path output) throws IOException {
    return runText(Files.readString(input), profileFile, output);
  }

  /**
   * Runs one campaign case with a shared remaining-time deadline and retained generator provenance.
   * The deadline caps compiler/probe/target waits in addition to profile limits; it does not
   * interrupt host model preparation or artifact retention. Expiry is reported as CAMPAIGN_LIMIT.
   * Output has the same fresh-directory contract as {@link #run}.
   */
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

  /**
   * Selects a built-in case for the profile's requested capabilities, then uses the normal run
   * path. The report distinguishes unavailable capabilities from execution-dependent requirements
   * that were not established. Output is a fresh retained bundle, including failed probes.
   */
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
                Set.of());
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
   * Reuses validated GIR and exact bundled classes/support without recompilation. A fresh bundle
   * receives a new run token and observations, using the recorded profile and launch environment.
   * Changes to the installed JVM/support files are reported in provenance rather than silently
   * replacing the saved executable bytes. The saved bundle is not modified.
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
      // Conditional plans reserve storage/control visits; their graph values are placeholders.
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
        writeWorkload(directory, profile, program, oracle, references || concurrent, manifest);
        var compilation = RunCompiler.compile(directory, profile, campaignDeadline);
        if (compilation != null) return finish(directory, manifest, compilation);
      } else {
        Artifacts.restoreExecutable(saved, directory);
        var previous = manifest(saved);
        Artifacts.check(directory, previous);
        manifest.add("compiler", previous.get("compiler"));
        if (previous.has("provenance")) manifest.add("provenance", previous.get("provenance"));
        manifest.addProperty("replayOf", saved.toString());
      }

      var installations = Artifacts.installations(profile);
      manifest.add("installationArtifacts", JsonFiles.GSON.toJsonTree(installations));
      var changes = new ArrayList<String>();
      if (saved != null) {
        var previous = manifest(saved).getAsJsonObject("installationArtifacts");
        var keys = new TreeSet<>(installations.keySet());
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
                  new TypeToken<Map<String, String>>() {}.getType());

      result =
          TargetRunner.launch(
              directory, profile, manifest, oracle, run, savedEnvironment, campaignDeadline);
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

  /** Writes executable inputs only; oracle values never enter generated source or support. */
  private static void writeWorkload(
      Path directory,
      TargetProfile profile,
      GirProgram program,
      OracleResult oracle,
      boolean recordReferences,
      JsonObject manifest)
      throws IOException {
    var emission = JavaEmitter.emit(program);
    Files.writeString(directory.resolve("generated/Case.java"), emission.source());
    JsonFiles.write(directory.resolve("generated/layout.json"), emission.layout());

    var recorderCounts = new TreeMap<Integer, Long>();
    recorderCounts.put(0, 0L);
    oracle.events().forEach(event -> recorderCounts.merge(event.worker(), 1L, Long::sum));
    var recorderPlan = new StringBuilder();
    recorderCounts.forEach(
        (worker, count) -> recorderPlan.append(worker).append('=').append(count).append('\n'));
    Files.writeString(directory.resolve("generated/recorders.properties"), recorderPlan);

    if (recordReferences)
      Files.writeString(directory.resolve("generated/references.properties"), "enabled=true\n");

    Files.writeString(
        directory.resolve("generated/gc.properties"),
        "control=" + profile.gc().control() + "\nevidence=" + profile.collectionEvidence() + "\n");
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

    Artifacts.copySupport(directory, profile);
    Path compiler = RunCompiler.executable();
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
  }

  /**
   * Re-derives expectations from saved GIR and checks retained execution/evidence without a target
   * installation. Rewrites result.json in the bundle. Invalid evidence and exhausted checking
   * bounds become reports; persistence failures propagate as IOException. A validated conditional
   * failure prefix may establish INVALID_WORKLOAD instead of the original runtime error.
   */
  public static RunReport verify(Path directory) throws IOException {
    return RunVerifier.verify(directory);
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
}
