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
import io.connglli.gorrect.engine.gir.IdentityHashProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.MonitorProgram;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.observe.NaturalLiveness;
import io.connglli.gorrect.engine.observe.ObservationCapabilities;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.engine.oracle.ConcurrentHistory;
import io.connglli.gorrect.engine.oracle.ExactTraceVerifier;
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
import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
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
      var execution = execution(directory, manifest);
      if (execution.execution() != RunReport.Outcome.COMPLETED) {
        // Failed-execution prefix diagnosis currently covers reference histories only.
        report = diagnoseReferenceFailure(directory, manifest, execution);
        JsonFiles.write(directory.resolve("result.json"), report);
        return report;
      }

      Artifacts.check(directory, manifest);
      var profile = TargetProfile.parse(manifest.getAsJsonObject("profile"), directory);
      var program = GirParser.parse(Files.readString(directory.resolve("case.gir")));
      var observation = ObservationProfile.parse(profile.observation());
      requireSupported(program, profile, observation);
      long run = JsonFiles.number(JsonFiles.required(manifest, "run"));
      boolean independentEvidence =
          IdentityHashProgram.usesIdentityHash(program) || MonitorProgram.usesMonitors(program);
      boolean references = ReferenceProgram.usesReferences(program);
      boolean concurrent = HistoryProgram.usesConcurrentHistory(program);
      var oracle =
          (references || concurrent)
              ? HistoryPlan.plan(program, OracleLimits.DEFAULT)
              : Oracle.interpret(program);

      var layout =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("generated/layout.json")), ObservationLayout.class);
      QueueResources.source(program, layout);
      boolean lexicalNatural = !observation.controlled() && layout.lexicalCandidates();
      if (observation.controlled() && layout.lexicalCandidates())
        throw new IOException("Lexical candidate selection requires natural observation");
      if (!observation.controlled()
          && !layout.lexicalCandidates()
          && program
              .instructions()
              .anyMatch(
                  instruction ->
                      instruction instanceof Instruction.IfPresent
                          || instruction instanceof Instruction.IfNonzero))
        throw new IOException("Conditional natural capture requires lexical candidate selection");
      var problems = new ArrayList<String>();
      var unmet = new ArrayList<String>();
      var captures = new CaptureReader(directory, run, profile, layout, manifest);
      var facts = new Properties();
      boolean capabilityEvidenceUsable = false;
      try {
        facts =
            QueueResources.controlled(profile, directory)
                ? ControlledLaunch.targetFacts(directory)
                : target(directory);
        unmet.addAll(VmDriver.requirements(profile, facts));
        capabilityEvidenceUsable = true;
      } catch (IOException | RuntimeException failure) {
        captures.error("Target capability evidence failed: " + failure);
        // Unknown target facts supply no capability proof, but readable graphs can still refute.
        var requirements = new ArrayList<>(profile.requirements());
        requirements.addAll(program.requirements().stream().sorted().toList());
        for (String requirement : requirements)
          if (!unmet.contains(requirement)) unmet.add(requirement);
      }

      ExecutionTrace trace;
      try {
        trace = ExecutionTrace.read(directory.resolve("traces/execution.gtrace"), run);
      } catch (IOException | RuntimeException failure) {
        if (!lexicalNatural && !independentEvidence) throw failure;
        captures.error("Scalar trace extraction failed: " + failure);
        trace = new ExecutionTrace(run, false, List.of());
      }
      if (!trace.complete()) {
        if (!lexicalNatural && !independentEvidence)
          throw new IOException("Execution trace is incomplete");
        captures.error("Execution trace is incomplete");
      }
      boolean workerEvidenceFailed =
          !capabilityEvidenceUsable || profile.retainedMetadata() && !captures.trustedLaunch();
      try {
        StressVerifier.checkEnvelope(directory, run, program, trace);
      } catch (IOException | RuntimeException failure) {
        // Envelope failure must not hide a refutation from independent graph evidence.
        workerEvidenceFailed = true;
        problems.add("Worker synchronization evidence extraction failed: " + failure);
      }
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
        var cuts = CheckpointPlan.recordedCuts(program, trace);
        for (int index = 1; index < messages.length - 1; index++) {
          var expectedBindings = checkpoints.declarations(messages[index].operation());
          var projection =
              captures.read(
                  index,
                  expectedBindings,
                  cuts.get(
                      new CheckpointPlan.Visit(
                          messages[index].operation(), messages[index].occurrence())),
                  messages[index].operation() == 0);
          observations.add(
              new HistoryOracle.Observation(
                  projection.covered(), projection.problems(), projection.graph().isPresent()));
        }

        var history = referenceHistory(directory, run, program);
        if (!history.complete()) throw new IOException("Reference history is incomplete");

        // Coverage checks below may use the path only after its choices and observations match.
        var result =
            concurrent
                ? ConcurrentHistory.check(
                    program, trace, history, observations, historyLimits(profile))
                : HistoryOracle.check(
                    program,
                    trace,
                    history,
                    ReferenceProgram.usesQueues(program)
                        ? QueueHistory.read(directory.resolve("traces/queues.qhistory"), run)
                        : null,
                    observations,
                    historyLimits(profile));

        problems.addAll(result.problems());
        captures.merge(exercised, unmet, problems);
        if (result.execution() != null && !workerEvidenceFailed)
          workerEvidenceFailed =
              !stressCoverage(
                  directory, run, program, result.execution(), profile, unmet, problems);
        var completion =
            result.execution() != null && !workerEvidenceFailed
                ? CollectionVerifier.verify(
                    directory, run, program, result.execution(), profile, facts)
                : CollectionVerifier.withoutExecution(directory, run, profile, facts);
        for (String requirement : completion.unmet())
          if (!unmet.contains(requirement)) unmet.add(requirement);
        problems.addAll(completion.problems());

        report =
            new RunReport(
                captures.failed() || workerEvidenceFailed
                    ? RunReport.Outcome.EXTRACTION_ERROR
                    : result.invalidWorkload()
                        ? RunReport.Outcome.INVALID_WORKLOAD
                        : RunReport.Outcome.COMPLETED,
                (captures.failed() || workerEvidenceFailed)
                        && result.verdict() != ExactVerifier.Verdict.VIOLATED
                    ? ExactVerifier.Verdict.INCONCLUSIVE
                    : result.verdict(),
                unmet,
                problems);
        if (report.verdict() == ExactVerifier.Verdict.SATISFIED) exercised.add("named-roots");
        var verified = new HashSet<String>();
        if (result.execution() != null) {
          verified.addAll(exercised);
          if (report.verdict() != ExactVerifier.Verdict.SATISFIED) {
            verified.remove("named-roots");
            verified.remove("local-bindings");
          }
        }
        requiredObservations(profile, exercised, unmet, problems);
        report = new RunReport(report.execution(), report.verdict(), unmet, problems);
        observationReport(
            directory, program, observation, facts, exercised, verified, capabilityEvidenceUsable);
        JsonFiles.write(directory.resolve("result.json"), report);
        return report;
      }

      var traceVerdict = ExactTraceVerifier.verify(trace, oracle);
      boolean violated = traceVerdict == ExactVerifier.Verdict.VIOLATED;
      boolean matchedPath = trace.complete() && !violated;
      var controlCoverage =
          lexicalNatural || independentEvidence ? exactControls(directory, run, oracle) : null;
      if (controlCoverage != null && !controlCoverage.complete()) {
        captures.error(controlCoverage.problem());
        matchedPath = false;
      }
      boolean trustedCoverage =
          trace.complete()
              && (controlCoverage == null || controlCoverage.complete())
              && (!lexicalNatural || matchedPath);
      if (trustedCoverage && !workerEvidenceFailed)
        workerEvidenceFailed =
            !stressCoverage(directory, run, program, oracle, profile, unmet, problems);
      var completion =
          trustedCoverage && !workerEvidenceFailed
              ? CollectionVerifier.verify(directory, run, program, oracle, profile, facts)
              : CollectionVerifier.withoutExecution(directory, run, profile, facts);
      for (String requirement : completion.unmet())
        if (!unmet.contains(requirement)) unmet.add(requirement);
      problems.addAll(completion.problems());

      if (violated) problems.add("Operation order or scalar-read trace differs from GIR");
      var verified = new HashSet<String>();
      if (!violated) verified.addAll(exercised);

      if (!lexicalNatural && !independentEvidence)
        controls(directory, run, oracle, program, trace, false);
      boolean inconclusive = workerEvidenceFailed || !trace.complete();
      if (controlCoverage != null && !controlCoverage.complete()) inconclusive = true;
      var natural =
          observation.controlled()
              ? null
              : lexicalNatural
                  ? NaturalLiveness.analyze(program, oracle)
                  : NaturalLiveness.analyze(program);
      for (int i = 0; i <= oracle.checkpoints().size(); i++) {
        if (controlCoverage != null && i >= controlCoverage.captures()) break;
        var visit =
            i < oracle.checkpoints().size()
                ? oracle.checkpoints().get(i)
                : new OracleResult.Checkpoint(0, 1, oracle.finalGraph());
        var expected =
            natural == null
                ? visit.graph()
                : lexicalNatural && !matchedPath
                    ? new Graph(visit.graph().objects(), visit.graph().roots(), Map.of())
                    : natural.obligations(visit.graph(), visit.operation(), visit.occurrence());
        if (lexicalNatural && !matchedPath && !visit.graph().handles().isEmpty()) {
          inconclusive = true;
          problems.add(
              "Checkpoint "
                  + (i + 1)
                  + ": natural local obligations require complete scalar/control correspondence");
        }
        SnapshotProjector.Projection projection;
        if (lexicalNatural) {
          var capture =
              captures.natural(
                  i + 1L, visit.graph().handles().keySet(), expected.handles().keySet());
          projection = capture.projection();
          for (var presence : capture.presence().entrySet())
            if (presence.getValue()
                != (visit.graph().handles().get(presence.getKey()).target() != null)) {
              violated = true;
              problems.add(
                  "Checkpoint "
                      + (i + 1)
                      + ": binding "
                      + presence.getKey()
                      + " nullness differs from the modeled value");
            }
        } else
          projection =
              captures.read(i + 1L, expected.handles().keySet(), null, visit.operation() == 0);
        if (projection.covered().isPresent()) exercised.add("named-roots");
        var result = projection.verify(expected);
        if (result.verdict() == ExactVerifier.Verdict.INCONCLUSIVE) inconclusive = true;
        if (result.verdict() == ExactVerifier.Verdict.VIOLATED) violated = true;
        for (String problem : result.differences())
          problems.add("Checkpoint " + (i + 1) + ": " + problem);
      }

      captures.merge(exercised, unmet, problems);

      inconclusive |= captures.failed();

      // Extraction gaps cannot become a pass, even when another checkpoint is fully covered.
      var verdict =
          violated
              ? ExactVerifier.Verdict.VIOLATED
              : inconclusive ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.SATISFIED;
      if (verdict == ExactVerifier.Verdict.SATISFIED) verified.addAll(exercised);
      requiredObservations(profile, exercised, unmet, problems);
      report =
          new RunReport(
              captures.failed() || workerEvidenceFailed
                  ? RunReport.Outcome.EXTRACTION_ERROR
                  : RunReport.Outcome.COMPLETED,
              verdict,
              unmet,
              problems);
      observationReport(
          directory, program, observation, facts, exercised, verified, capabilityEvidenceUsable);
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

  /** Only authenticated, valid monitor source can refine a missing execution report. */
  private static RunReport execution(Path directory, JsonObject manifest) throws IOException {
    try {
      var report =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("execution.json")), RunReport.class);
      if (report == null) throw new IOException("Missing execution report");
      return report;
    } catch (IOException | RuntimeException failure) {
      Artifacts.check(directory, manifest);
      try {
        var profile = TargetProfile.parse(manifest.getAsJsonObject("profile"), directory);
        var program = GirParser.parse(Files.readString(directory.resolve("case.gir")));
        if (MonitorProgram.usesMonitors(program))
          requireSupported(program, profile, ObservationProfile.parse(profile.observation()));
      } catch (UnsupportedHistoryException unsupported) {
        throw unsupported;
      } catch (IOException | RuntimeException invalidSource) {
        // Invalid or unavailable source cannot manufacture a workload verdict from missing
        // evidence.
      }
      throw failure;
    }
  }

  private static void requireSupported(
      GirProgram program, TargetProfile profile, ObservationProfile observation) {
    var reasons = new ArrayList<>(ObservationCapabilities.unsupported(program, observation));
    reasons.addAll(VmDriver.unsupported(profile));
    if (!reasons.isEmpty()) throw new UnsupportedHistoryException(String.join("; ", reasons));
  }

  /** Keeps malformed worker structure from bypassing independent graph comparisons. */
  private static boolean stressCoverage(
      Path directory,
      long run,
      GirProgram program,
      OracleResult oracle,
      TargetProfile profile,
      List<String> unmet,
      List<String> problems) {
    try {
      var coverage = StressVerifier.verify(directory, run, program, oracle, profile);
      for (String requirement : coverage.unmet())
        if (!unmet.contains(requirement)) unmet.add(requirement);
      problems.addAll(coverage.problems());
      return true;
    } catch (IOException | RuntimeException failure) {
      problems.add("Worker synchronization evidence extraction failed: " + failure);
      return false;
    }
  }

  private record NaturalCapture(
      SnapshotProjector.Projection projection, Map<Integer, Boolean> presence) {}

  private record ControlCoverage(int captures, boolean complete, String problem) {}

  /** A bad suffix cannot revoke an earlier capture's independently checked site identity. */
  private static ControlCoverage exactControls(Path directory, long run, OracleResult oracle) {
    int trusted = 0;
    try {
      var transcript = JsonFiles.read(directory.resolve("traces/control.json"));
      if (JsonFiles.number(JsonFiles.required(transcript, "version")) != 1
          || JsonFiles.number(JsonFiles.required(transcript, "run")) != run)
        throw new IOException("Control transcript identity mismatch");
      var messages = JsonFiles.required(transcript, "messages").getAsJsonArray();
      if (messages.isEmpty()) throw new IOException("Control transcript omits HELLO");
      var hello = JsonFiles.GSON.fromJson(messages.get(0), ControlMessage.class);
      if (hello == null
          || hello.run() != run
          || hello.kind() != ControlMessage.Kind.HELLO
          || hello.sequence() != 0) throw new IOException("Control transcript HELLO mismatch");
      var visits = new CheckpointPlan(oracle).visits();
      for (int index = 0; index < visits.size(); index++) {
        if (index + 1 >= messages.size())
          throw new IOException("Control checkpoint suffix is incomplete");
        var message = JsonFiles.GSON.fromJson(messages.get(index + 1), ControlMessage.class);
        var visit = visits.get(index);
        if (message == null
            || message.run() != run
            || message.kind() != ControlMessage.Kind.CHECKPOINT
            || message.sequence() != index + 1L
            || message.operation() != visit.operation()
            || message.occurrence() != visit.occurrence())
          throw new IOException("Control checkpoint identity differs from the exact oracle");
        trusted++;
      }
      if (messages.size() != visits.size() + 2)
        throw new IOException("Control completion suffix is incomplete");
      var complete =
          JsonFiles.GSON.fromJson(messages.get(messages.size() - 1), ControlMessage.class);
      if (complete == null
          || complete.run() != run
          || complete.kind() != ControlMessage.Kind.COMPLETE
          || complete.sequence() != visits.size())
        throw new IOException("Control completion identity mismatch");
      return new ControlCoverage(trusted, true, "");
    } catch (IOException | RuntimeException failure) {
      return new ControlCoverage(
          trusted, false, "Control correspondence extraction failed: " + failure);
    }
  }

  /** Loads independent captures without letting a later gap erase an earlier refutation. */
  private static final class CaptureReader {
    private final Path directory;
    private final long run;
    private final TargetProfile profile;
    private final ObservationLayout layout;
    private final Set<String> exercised = new HashSet<>();
    private final List<String> unmet = new ArrayList<>(), errors = new ArrayList<>();
    private String launchProblem;

    CaptureReader(
        Path directory,
        long run,
        TargetProfile profile,
        ObservationLayout layout,
        JsonObject manifest) {
      this.directory = directory;
      this.run = run;
      this.profile = profile;
      this.layout = layout;
      if (profile.retainedMetadata() || layout.queuePlan() != null) {
        try {
          var facts = ControlledLaunch.targetFacts(directory);
          ControlledLaunch.evidence(profile, manifest, facts, layout.queuePlan() != null);
          if (layout.queuePlan() != null)
            QueueResources.admission(profile, manifest, facts, directory);
        } catch (IOException | RuntimeException failure) {
          launchProblem = "Controlled launch evidence failed: " + failure;
          errors.add(launchProblem);
        }
      }
      try {
        RetainedMetadata.selection(directory, profile, layout);
        QueueResources.selection(directory, layout);
        if (profile.retainedMetadata()) RetainedMetadata.completion(directory, run);
      } catch (IOException | RuntimeException failure) {
        errors.add("Metadata certification failed: " + failure);
      }
    }

    SnapshotProjector.Projection read(
        long sequence, Set<Integer> declarations, CheckpointPlan.Cut cut, boolean terminal) {
      HeapSnapshot capture = null;
      boolean contextTrusted = false;
      try {
        capture = snapshot(directory, run, sequence);
        compilationCoverage(capture, profile, unmet);
        var projection =
            projection(
                directory,
                capture,
                layout,
                ObservationProfile.parse(profile.observation()),
                declarations,
                exercised,
                cut,
                terminal);
        // Local gaps retain covered facts. An empty global projection cannot establish capture
        // context or justify recovering named facts after a later metadata/envelope error.
        contextTrusted = projection.covered().isPresent();
        if (launchProblem != null) throw new IOException(launchProblem);
        RetainedMetadata.selection(directory, profile, layout);
        return contextTrusted ? queueProjection(capture, projection) : projection;
      } catch (IOException | RuntimeException failure) {
        String problem = "Checkpoint " + sequence + " extraction failed: " + failure;
        errors.add(problem);
        var named =
            !contextTrusted || capture == null
                ? Optional.<Graph>empty()
                : SnapshotProjector.project(capture, layout).graph();
        var projection =
            new SnapshotProjector.Projection(Optional.empty(), List.of(problem), named);
        return contextTrusted && capture != null
            ? queueProjection(capture, projection)
            : projection;
      }
    }

    private SnapshotProjector.Projection queueProjection(
        HeapSnapshot capture, SnapshotProjector.Projection projection) {
      if (launchProblem != null) return projection;
      var result = QueueResources.project(directory, capture, layout, profile.vm(), projection);
      if (layout.queuePlan() != null && !result.problems().isEmpty())
        errors.addAll(result.problems());
      return result;
    }

    NaturalCapture natural(long sequence, Set<Integer> candidates, Set<Integer> required) {
      HeapSnapshot capture = null;
      try {
        capture = snapshot(directory, run, sequence);
        compilationCoverage(capture, profile, unmet);
        if (Files.exists(directory.resolve("snapshots/" + sequence + ".gcut")))
          throw new IOException("Unexpected coordinated capture envelope");
        var evidence =
            BindingEvidence.read(
                directory.resolve("snapshots/" + sequence + ".gbind"), run, sequence);
        if (evidence.thread() != Long.parseLong(target(directory).getProperty("main.thread"))
            || evidence.worker() != 0
            || evidence.activation() != 1)
          throw new IOException("Binding thread/activation mismatch");
        var presence =
            evidence.bindings().stream()
                .collect(
                    Collectors.toMap(
                        BindingEvidence.Binding::declaration, BindingEvidence.Binding::present));
        if (!presence.keySet().equals(candidates))
          throw new IOException("Incomplete lexical candidate declaration coverage");
        if (!required.isEmpty()) exercised.add("local-bindings");
        return new NaturalCapture(
            SnapshotProjector.projectCandidates(capture, layout, evidence, required), presence);
      } catch (IOException | RuntimeException failure) {
        String problem = "Checkpoint " + sequence + " extraction failed: " + failure;
        error(problem);
        var named =
            capture == null
                ? Optional.<Graph>empty()
                : SnapshotProjector.project(capture, layout).graph();
        return new NaturalCapture(
            new SnapshotProjector.Projection(Optional.empty(), List.of(problem), named), Map.of());
      }
    }

    void error(String problem) {
      errors.add(problem);
    }

    boolean trustedLaunch() {
      return launchProblem == null;
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
    return projection(
        directory, snapshot, layout, profile, expectedBindings, exercised, null, false);
  }

  private static SnapshotProjector.Projection projection(
      Path directory,
      HeapSnapshot snapshot,
      ObservationLayout layout,
      ObservationProfile profile,
      Set<Integer> expectedBindings,
      Set<String> exercised,
      CheckpointPlan.Cut cut,
      boolean terminal)
      throws IOException {
    if (cut != null)
      return WorkerCaptureVerifier.project(
          directory, snapshot, layout, profile, expectedBindings, exercised, cut);
    if (Files.exists(directory.resolve("snapshots/" + snapshot.checkpoint() + ".gcut")))
      throw new IOException("Unexpected coordinated capture envelope");
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
    if (layout.bindingMetadata().equals("retained-class")) {
      var participants = bindings == null ? List.<BindingEvidence>of() : List.of(bindings);
      return SnapshotProjector.projectRetained(
          snapshot,
          layout,
          participants,
          RetainedMetadata.capture(directory, snapshot, participants, terminal));
    }
    return SnapshotProjector.project(snapshot, layout, bindings);
  }

  /** Replaces stale availability claims when the target capability facts cannot be used. */
  private static void observationReport(
      Path directory,
      GirProgram program,
      ObservationProfile profile,
      Properties facts,
      Set<String> exercised,
      Set<String> verified,
      boolean capabilityEvidenceUsable)
      throws IOException {
    JsonFiles.write(
        directory.resolve("observation-capabilities.json"),
        capabilityEvidenceUsable
            ? ObservationCapabilities.describe(program, profile, facts, exercised, verified)
            : List.of());
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
      if (profile.retainedMetadata() || ReferenceProgram.usesQueues(program)) {
        var facts = ControlledLaunch.targetFacts(directory);
        ControlledLaunch.evidence(profile, manifest, facts, ReferenceProgram.usesQueues(program));
        if (ReferenceProgram.usesQueues(program))
          QueueResources.admission(profile, manifest, facts, directory);
      }
      var layout =
          JsonFiles.GSON.fromJson(
              JsonFiles.read(directory.resolve("generated/layout.json")), ObservationLayout.class);

      QueueResources.source(program, layout);
      QueueResources.selection(directory, layout);
      RetainedMetadata.selection(directory, profile, layout);
      var plan = new CheckpointPlan(HistoryPlan.plan(program, OracleLimits.DEFAULT));
      var trace = ExecutionTrace.read(directory.resolve("traces/execution.gtrace"), run);
      var recorded = CheckpointPlan.recorded(program, trace);
      prefixControls(directory, run, plan, recorded);
      var observations = new ArrayList<HistoryOracle.Observation>();
      var suffixProblems = new ArrayList<String>();
      for (int index = 1;
          Files.exists(directory.resolve("snapshots/" + index + ".gsnap"));
          index++) {
        // recorded includes a synthetic terminal visit; a failed prefix has no such capture.
        if (index >= recorded.size()) {
          suffixProblems.add("Unlinked capture suffix after the validated failure prefix");
          break;
        }
        var visit = recorded.get(index - 1);
        if (!plan.permits(visit.operation(), visit.occurrence())) {
          suffixProblems.add("Capture exceeds the validated failure-prefix syntax bounds");
          break;
        }
        var expectedBindings = plan.declarations(visit.operation());
        try {
          var projection =
              projection(
                  directory,
                  snapshot(directory, run, index),
                  layout,
                  ObservationProfile.parse(profile.observation()),
                  expectedBindings,
                  new HashSet<>());
          projection =
              QueueResources.project(
                  directory, snapshot(directory, run, index), layout, profile.vm(), projection);
          observations.add(
              new HistoryOracle.Observation(
                  projection.covered(), projection.problems(), projection.graph().isPresent()));
        } catch (IOException | RuntimeException unavailable) {
          // A rejected later cut supplies no raw fallback and cannot erase an earlier refutation.
          observations.add(
              new HistoryOracle.Observation(
                  Optional.empty(),
                  List.of("Checkpoint " + index + " prefix extraction failed: " + unavailable),
                  false));
        }
      }

      var result =
          HistoryOracle.checkFailure(
              program,
              trace,
              referenceHistory(directory, run, program),
              ReferenceProgram.usesQueues(program)
                  ? QueueHistory.read(directory.resolve("traces/queues.qhistory"), run)
                  : null,
              observations,
              new HistoryLimits(
                  profile.limits().historyStates(), profile.limits().historyTransitions()));
      var diagnostics = new ArrayList<>(execution.problems());
      diagnostics.addAll(result.problems());
      diagnostics.addAll(suffixProblems);
      if (result.verdict() == ExactVerifier.Verdict.VIOLATED)
        return new RunReport(
            execution.execution(),
            ExactVerifier.Verdict.VIOLATED,
            execution.unmetRequirements(),
            diagnostics);
      if (result.invalidWorkload())
        return new RunReport(
            RunReport.Outcome.INVALID_WORKLOAD,
            ExactVerifier.Verdict.INCONCLUSIVE,
            execution.unmetRequirements(),
            diagnostics);
      if (!suffixProblems.isEmpty())
        return new RunReport(
            execution.execution(), execution.verdict(), execution.unmetRequirements(), diagnostics);
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

  private static ReferenceHistory referenceHistory(Path directory, long run, GirProgram program)
      throws IOException {
    return ReferenceProgram.usesFirstClass(program)
        ? ReferenceHistory.readTyped(directory.resolve("traces/references.gtyped"), run)
        : ReferenceHistory.read(directory.resolve("traces/references.ghistory"), run);
  }

  static HistoryLimits historyLimits(TargetProfile profile) {
    return new HistoryLimits(
        profile.limits().historyStates(), profile.limits().historyTransitions());
  }
}
