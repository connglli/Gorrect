package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.Artifacts.manifest;
import static io.connglli.gorrect.engine.run.Artifacts.snapshot;
import static io.connglli.gorrect.engine.run.Artifacts.target;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.observe.NaturalLiveness;
import io.connglli.gorrect.engine.observe.ObservationCapabilities;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
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
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks saved execution evidence without launching or consulting the target JVM. */
final class RunVerifier {
  private RunVerifier() {}

  /**
   * Implements {@link RunEngine#verify(Path)}: reads one retained bundle and persists its report.
   * Expected graphs come from saved GIR; layouts only map captured fields to logical roles.
   */
  static RunReport verify(Path directory) throws IOException {
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
      var observation = ObservationProfile.parse(profile.observation());
      var unsupported = ObservationCapabilities.unsupported(program, observation);
      if (!unsupported.isEmpty())
        throw new UnsupportedHistoryException(String.join("; ", unsupported));
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
      var exercised = new HashSet<String>();
      var observedOperations = new HashSet<Integer>();
      for (var event : trace.events())
        if (event.kind() == ExecutionTrace.Kind.SCALAR_READ)
          observedOperations.add(event.operation());
      program
          .instructions()
          .forEach(
              instruction -> {
                if (observedOperations.contains(instruction.id())) {
                  if (instruction instanceof Instruction.ReadData) exercised.add("semantic-reads");
                  if (instruction instanceof Instruction.SameObject) exercised.add("alias-checks");
                }
              });

      if (references || concurrent) {

        controls(directory, run, oracle);
        var observations = new ArrayList<HistoryOracle.Observation>();
        for (int index = 1; index <= oracle.checkpoints().size() + 1; index++) {
          var snapshot = snapshot(directory, run, index);
          compilationCoverage(snapshot, profile, unmet);
          var expectedBindings =
              index <= oracle.checkpoints().size()
                  ? oracle.checkpoints().get(index - 1).graph().handles().keySet()
                  : Set.<Integer>of();
          var projection =
              projection(directory, snapshot, layout, observation, expectedBindings, exercised);
          observations.add(
              new HistoryOracle.Observation(projection.graph(), projection.problems()));
        }

        var history = ReferenceHistory.read(directory.resolve("traces/references.ghistory"), run);
        if (!history.complete()) throw new IOException("Reference history is incomplete");

        // Coverage checks below may use the path only after its choices and observations match.
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
        if (result.verdict() == ExactVerifier.Verdict.SATISFIED) exercised.add("named-roots");
        var verified = new HashSet<String>();
        if (result.execution() != null) {
          verified.addAll(exercised);
          if (result.verdict() != ExactVerifier.Verdict.SATISFIED) {
            verified.remove("named-roots");
            verified.remove("local-bindings");
          }
        }
        requiredObservations(profile, exercised, unmet, problems);
        report = new RunReport(report.execution(), report.verdict(), unmet, problems);
        observationReport(directory, program, observation, exercised, verified);
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
      var verified = new HashSet<String>();
      if (!violated) verified.addAll(exercised);

      controls(directory, run, oracle);
      boolean inconclusive = false;
      var natural = observation.controlled() ? null : NaturalLiveness.analyze(program);
      for (int i = 0; i <= oracle.checkpoints().size(); i++) {
        var visit =
            i < oracle.checkpoints().size()
                ? oracle.checkpoints().get(i)
                : new OracleResult.Checkpoint(0, 1, oracle.finalGraph());
        var snapshot = snapshot(directory, run, i + 1L);
        compilationCoverage(snapshot, profile, unmet);

        var expected =
            natural == null
                ? visit.graph()
                : natural.obligations(visit.graph(), visit.operation(), visit.occurrence());
        var projection =
            projection(
                directory, snapshot, layout, observation, expected.handles().keySet(), exercised);
        if (projection.graph().isPresent()) exercised.add("named-roots");
        var result = projection.verify(expected);
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
      if (verdict == ExactVerifier.Verdict.SATISFIED) verified.addAll(exercised);
      requiredObservations(profile, exercised, unmet, problems);
      report = new RunReport(RunReport.Outcome.COMPLETED, verdict, unmet, problems);
      observationReport(directory, program, observation, exercised, verified);
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

  /** Coverage uses declaration keys only; values come exclusively from target evidence. */
  private static SnapshotProjector.Projection projection(
      Path directory,
      HeapSnapshot snapshot,
      ObservationLayout layout,
      ObservationProfile profile,
      Set<Integer> expectedBindings,
      Set<String> exercised)
      throws IOException {
    BindingEvidence bindings = null;
    if (profile.bindings()) {
      bindings =
          BindingEvidence.read(
              directory.resolve("snapshots/" + snapshot.checkpoint() + ".gbind"),
              snapshot.run(),
              snapshot.checkpoint());
      if (bindings.thread() != Long.parseLong(target(directory).getProperty("main.thread"))
          || bindings.worker() != 0
          || bindings.activation() != 1)
        throw new IOException("Binding thread/activation mismatch");
      if (!bindings.bindings().isEmpty()) exercised.add("local-bindings");
      var declarations =
          bindings.bindings().stream()
              .map(BindingEvidence.Binding::declaration)
              .collect(Collectors.toSet());
      if (!declarations.equals(expectedBindings))
        return new SnapshotProjector.Projection(
            Optional.empty(), List.of("Incomplete binding declaration coverage"));
    } else if (!expectedBindings.isEmpty())
      return new SnapshotProjector.Projection(
          Optional.empty(), List.of("Named-root profile does not cover live bindings"));
    return SnapshotProjector.project(snapshot, layout, bindings);
  }

  private static void observationReport(
      Path directory,
      GirProgram program,
      ObservationProfile profile,
      Set<String> exercised,
      Set<String> verified)
      throws IOException {
    JsonFiles.write(
        directory.resolve("observation-capabilities.json"),
        ObservationCapabilities.describe(program, profile, target(directory), exercised, verified));
  }

  private static void requiredObservations(
      TargetProfile profile, Set<String> exercised, List<String> unmet, List<String> problems) {
    for (String requirement : List.of("local-bindings", "semantic-reads", "alias-checks"))
      if (profile.requirements().contains(requirement) && !exercised.contains(requirement)) {
        if (!unmet.contains(requirement)) unmet.add(requirement);
        problems.add("Required mechanism was not exercised: " + requirement);
      }
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

      var plan = HistoryPlan.plan(program, OracleLimits.DEFAULT);
      var observations = new ArrayList<HistoryOracle.Observation>();
      for (int index = 1;
          Files.exists(directory.resolve("snapshots/" + index + ".gsnap"));
          index++) {
        var expectedBindings =
            index <= plan.checkpoints().size()
                ? plan.checkpoints().get(index - 1).graph().handles().keySet()
                : Set.<Integer>of();
        var projection =
            projection(
                directory,
                snapshot(directory, run, index),
                layout,
                ObservationProfile.parse(profile.observation()),
                expectedBindings,
                new HashSet<>());
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
      HeapSnapshot snapshot, TargetProfile profile, List<String> unmet) {
    if (profile.requirements().contains("compilation-events")
        && (snapshot.capabilities() & HeapSnapshot.COMPILATION_OVERFLOW) != 0
        && !unmet.contains("compilation-events")) unmet.add("compilation-events");
  }

  static HistoryLimits historyLimits(TargetProfile profile) {
    return new HistoryLimits(
        profile.limits().historyStates(), profile.limits().historyTransitions());
  }
}
