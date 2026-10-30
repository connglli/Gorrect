package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.Artifacts.OBSERVER;
import static io.connglli.gorrect.engine.run.Artifacts.classpath;
import static io.connglli.gorrect.engine.run.Artifacts.snapshot;
import static io.connglli.gorrect.engine.run.Artifacts.target;
import static io.connglli.gorrect.engine.run.ProcessSupport.cleanEnvironment;
import static io.connglli.gorrect.engine.run.ProcessSupport.milliseconds;
import static io.connglli.gorrect.engine.run.ProcessSupport.terminate;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.engine.oracle.OracleResult;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one target process and validates its synchronous control conversation. */
final class TargetRunner {
  private TargetRunner() {}

  /**
   * Resolves the collector, launches one target, and checks its handshake/checkpoint conversation.
   * The oracle argument supplies control identities, not graph predictions used by the target.
   * Normal completion requires all captures to decode and the process to exit successfully; graph
   * and stress verification happen afterward.
   *
   * <p>Mutates manifest with resolved settings and process facts and retains the control
   * transcript. Every exit path closes the endpoint and terminates a surviving process.
   * Interruption propagates; ordinary target failures become reports, while transcript-persistence
   * I/O may propagate.
   */
  static RunReport launch(
      Path directory,
      TargetProfile profile,
      JsonObject manifest,
      OracleResult oracle,
      long run,
      Map<String, String> savedEnvironment,
      Long campaignDeadline)
      throws IOException, InterruptedException {
    var messages = new ArrayList<ControlMessage>();
    var checkpoints = new CheckpointPlan(oracle);
    boolean bounded =
        manifest.get("referenceHistory").getAsBoolean()
            || manifest.get("concurrentHistory").getAsBoolean();
    var visits = new HashMap<Integer, Long>();
    boolean finalCapture = false;
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
      command.add("-Dgorrect.observation=" + profile.observation());
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
                  & HeapSnapshot.REFERENCE_FIELDS)
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
          if (!finalCapture || message.sequence() != index)
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

        var visit = new CheckpointPlan.Visit(message.operation(), message.occurrence());
        boolean planned = checkpoints.permits(visit.operation(), visit.occurrence());
        if (!bounded)
          planned &=
              index < checkpoints.visits().size() && checkpoints.visits().get(index).equals(visit);
        if (finalCapture
            || !planned
            || message.kind() != ControlMessage.Kind.CHECKPOINT
            || message.sequence() != index + 1L
            || message.occurrence() != visits.getOrDefault(message.operation(), 0L) + 1)
          return RunReport.failure(
              RunReport.Outcome.PROTOCOL_ERROR, "Checkpoint order or identity mismatch");

        try {
          snapshot(directory, run, message.sequence());
        } catch (IOException exception) {
          return RunReport.failure(RunReport.Outcome.EXTRACTION_ERROR, exception.toString());
        }
        visits.put(message.operation(), message.occurrence());
        finalCapture = message.operation() == 0;
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

  private static void acknowledge(DataOutputStream out, long run, long sequence)
      throws IOException {
    new ControlMessage(ControlMessage.Kind.ACK, run, sequence, 0, 0, "").write(out);
  }
}
