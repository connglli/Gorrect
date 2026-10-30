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
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
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
      var captures = new CaptureReader(directory, run, profile, layout);
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

        var messages = controls(directory, run, oracle, program, trace, true);
        var checkpoints = new CheckpointPlan(oracle);
        var observations = new ArrayList<HistoryOracle.Observation>();
        for (int index = 1; index < messages.length - 1; index++) {
          var expectedBindings = checkpoints.declarations(messages[index].operation());
          var projection = captures.read(index, expectedBindings);
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
        captures.merge(exercised, unmet, problems);
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
                captures.failed()
                    ? RunReport.Outcome.EXTRACTION_ERROR
                    : result.invalidWorkload()
                        ? RunReport.Outcome.INVALID_WORKLOAD
                        : RunReport.Outcome.COMPLETED,
                captures.failed() && result.verdict() != ExactVerifier.Verdict.VIOLATED
                    ? ExactVerifier.Verdict.INCONCLUSIVE
                    : result.verdict(),
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

      controls(directory, run, oracle, program, trace, false);
      boolean inconclusive = false;
      var natural = observation.controlled() ? null : NaturalLiveness.analyze(program);
      for (int i = 0; i <= oracle.checkpoints().size(); i++) {
        var visit =
            i < oracle.checkpoints().size()
                ? oracle.checkpoints().get(i)
                : new OracleResult.Checkpoint(0, 1, oracle.finalGraph());
        var expected =
            natural == null
                ? visit.graph()
                : natural.obligations(visit.graph(), visit.operation(), visit.occurrence());
        var projection = captures.read(i + 1L, expected.handles().keySet());
        if (projection.graph().isPresent()) exercised.add("named-roots");
        var result = projection.verify(expected);
        if (result.verdict() == ExactVerifier.Verdict.INCONCLUSIVE) inconclusive = true;
        if (result.verdict() == ExactVerifier.Verdict.VIOLATED) violated = true;
        for (String problem : result.differences())
          problems.add("Checkpoint " + (i + 1) + ": " + problem);
      }

      captures.merge(exercised, unmet, problems);

      // Extraction gaps cannot become a pass, even when another checkpoint is fully covered.
      var verdict =
          violated
              ? ExactVerifier.Verdict.VIOLATED
              : inconclusive ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.SATISFIED;
      if (verdict == ExactVerifier.Verdict.SATISFIED) verified.addAll(exercised);
      requiredObservations(profile, exercised, unmet, problems);
      report =
          new RunReport(
              captures.failed() ? RunReport.Outcome.EXTRACTION_ERROR : RunReport.Outcome.COMPLETED,
              verdict,
              unmet,
              problems);
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

  /** Loads independent captures without letting a later gap erase an earlier refutation. */
  private static final class CaptureReader {
    private final Path directory;
    private final long run;
    private final TargetProfile profile;
    private final ObservationLayout layout;
    private final Set<String> exercised = new HashSet<>();
    private final List<String> unmet = new ArrayList<>(), errors = new ArrayList<>();

    CaptureReader(Path directory, long run, TargetProfile profile, ObservationLayout layout) {
      this.directory = directory;
      this.run = run;
      this.profile = profile;
      this.layout = layout;
    }

    SnapshotProjector.Projection read(long sequence, Set<Integer> declarations) {
      try {
        var capture = snapshot(directory, run, sequence);
        compilationCoverage(capture, profile, unmet);
        return projection(
            directory,
            capture,
            layout,
            ObservationProfile.parse(profile.observation()),
            declarations,
            exercised);
      } catch (IOException | RuntimeException failure) {
        String problem = "Checkpoint " + sequence + " extraction failed: " + failure;
        errors.add(problem);
        return new SnapshotProjector.Projection(Optional.empty(), List.of(problem));
      }
    }

    boolean failed() {
      return !errors.isEmpty();
    }

    void merge(Set<String> exercised, List<String> unmet, List<String> problems) {
      exercised.addAll(this.exercised);
      for (String gap : this.unmet) if (!unmet.contains(gap)) unmet.add(gap);
      for (String error : errors) if (!problems.contains(error)) problems.add(error);
    }
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

      var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
      var trace = ExecutionTrace.read(directory.resolve("traces/execution.gtrace"), run);
      var recorded = CheckpointPlan.recorded(program, trace);
      prefixControls(directory, run, plan, recorded);
      var observations = new ArrayList<HistoryOracle.Observation>();
      for (int index = 1;
          Files.exists(directory.resolve("snapshots/" + index + ".gsnap"));
          index++) {
        if (index > recorded.size())
          throw new IOException("Capture has no recorded checkpoint marker");
        var visit = recorded.get(index - 1);
        if (!plan.permits(visit.operation(), visit.occurrence()))
          throw new IOException("Recorded checkpoint exceeds syntax bounds");
        var expectedBindings = plan.declarations(visit.operation());
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
              trace,
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

  /** Failure diagnosis needs the complete checkpoint prefix, without an invented final capture. */
  private static void prefixControls(
      Path directory, long run, CheckpointPlan plan, List<CheckpointPlan.Visit> recorded)
      throws IOException {
    var messages = controlMessages(directory, run);
    int checkpoints = recorded.size() - 1;
    if (messages.length != checkpoints + 2
        || messages[0].kind() != ControlMessage.Kind.HELLO
        || messages[0].sequence() != 0
        || messages[messages.length - 1].kind() != ControlMessage.Kind.FAILED
        || messages[messages.length - 1].sequence() != checkpoints)
      throw new IOException("Failure control prefix is incomplete");
    for (int index = 0; index < checkpoints; index++) {
      var message = messages[index + 1];
      var visit = recorded.get(index);
      if (message.kind() != ControlMessage.Kind.CHECKPOINT
          || message.sequence() != index + 1L
          || message.operation() != visit.operation()
          || message.occurrence() != visit.occurrence()
          || !plan.permits(visit.operation(), visit.occurrence()))
        throw new IOException("Failure checkpoint identity differs from recorded markers");
    }
  }

  private static ControlMessage[] controlMessages(Path directory, long run) throws IOException {
    var transcript = JsonFiles.read(directory.resolve("traces/control.json"));
    if (JsonFiles.number(JsonFiles.required(transcript, "version")) != 1
        || JsonFiles.number(JsonFiles.required(transcript, "run")) != run)
      throw new IOException("Control transcript identity mismatch");
    var messages =
        JsonFiles.GSON.fromJson(JsonFiles.required(transcript, "messages"), ControlMessage[].class);
    if (messages == null) throw new IOException("Control transcript omits messages");
    for (var message : messages)
      if (message == null || message.run() != run)
        throw new IOException("Control transcript contains another run");
    return messages;
  }

  /** Cross-checks capture identities without accepting the trace's branch choices as legal. */
  private static ControlMessage[] controls(
      Path directory,
      long run,
      OracleResult oracle,
      GirProgram program,
      ExecutionTrace trace,
      boolean bounded)
      throws IOException {
    var messages = controlMessages(directory, run);
    var plan = new CheckpointPlan(oracle);
    var recorded = CheckpointPlan.recorded(program, trace);
    if (messages.length != recorded.size() + 2
        || messages.length > plan.visits().size() + 2
        || messages[0].kind() != ControlMessage.Kind.HELLO
        || messages[0].sequence() != 0
        || messages[messages.length - 1].kind() != ControlMessage.Kind.COMPLETE
        || messages[messages.length - 1].sequence() != recorded.size())
      throw new IOException("Control transcript is incomplete");
    for (int index = 0; index < recorded.size(); index++) {
      var visit = recorded.get(index);
      var message = messages[index + 1];
      if (message.kind() != ControlMessage.Kind.CHECKPOINT
          || message.sequence() != index + 1L
          || message.operation() != visit.operation()
          || message.occurrence() != visit.occurrence()
          || !plan.permits(visit.operation(), visit.occurrence()))
        throw new IOException("Control checkpoint identity differs from recorded markers");
      if (!bounded && (index >= plan.visits().size() || !plan.visits().get(index).equals(visit)))
        throw new IOException("Control checkpoint identity differs from the exact oracle");
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
