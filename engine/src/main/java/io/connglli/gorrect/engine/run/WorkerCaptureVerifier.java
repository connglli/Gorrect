package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.Artifacts.target;

import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CaptureEvidence;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks primitive participant context before independent physical-root projection. */
final class WorkerCaptureVerifier {
  private WorkerCaptureVerifier() {}

  static SnapshotProjector.Projection project(
      Path directory,
      HeapSnapshot snapshot,
      ObservationLayout layout,
      ObservationProfile profile,
      Set<Integer> expectedBindings,
      Set<String> exercised,
      CheckpointPlan.Cut cut)
      throws IOException {
    if (!profile.controlled() || !profile.bindings())
      throw new IOException("Worker capture requires controlled binding observation");
    var evidence =
        CaptureEvidence.read(
            directory.resolve("snapshots/" + snapshot.checkpoint() + ".gcut"),
            snapshot.run(),
            snapshot.checkpoint());
    if (evidence.group() != cut.group()
        || evidence.round() != cut.round()
        || evidence.cut() != cut.ordinal()
        || evidence.phase() != cut.phase())
      throw new IOException("Coordinated capture context differs from recorded arrivals");
    if (!evidence.participants().stream()
        .map(CaptureEvidence.Participant::worker)
        .toList()
        .equals(cut.participants().keySet().stream().sorted().toList()))
      throw new IOException("Coordinated capture participant order or coverage mismatch");
    var execution =
        ExecutionEvidence.read(directory.resolve("traces/workers.gevidence"), snapshot.run());
    var expectedByWorker = new HashMap<Integer, Set<Integer>>();
    for (var binding : layout.bindings())
      if (expectedBindings.contains(binding.declaration()))
        expectedByWorker
            .computeIfAbsent(binding.worker(), ignored -> new HashSet<>())
            .add(binding.declaration());
    var participants = new ArrayList<BindingEvidence>();
    var workers = new HashSet<Integer>();
    var declarations = new HashSet<Integer>();
    for (var participant : evidence.participants()) {
      var expected = cut.participants().get(participant.worker());
      if (!workers.add(participant.worker())
          || expected == null
          || expected.operation() != participant.operation()
          || expected.occurrence() != participant.occurrence()
          || participant.activation() != (participant.worker() == 0 ? 1 : cut.round())
          || participant.worker() == 0
              && participant.thread()
                  != Long.parseLong(target(directory).getProperty("main.thread")))
        throw new IOException("Coordinated capture participant identity mismatch");
      if (participant.worker() != 0) {
        var windows =
            execution.windows().stream()
                .filter(
                    window ->
                        window.worker() == participant.worker()
                            && window.group() == cut.group()
                            && window.round() == cut.round()
                            && window.phase() == cut.phase())
                .toList();
        if (windows.size() != 1 || windows.getFirst().thread() != participant.thread())
          throw new IOException("Coordinated capture thread differs from worker execution");
      }
      var ownDeclarations = expectedByWorker.getOrDefault(participant.worker(), Set.of());
      var actualDeclarations =
          participant.bindings().stream()
              .map(BindingEvidence.Binding::declaration)
              .collect(Collectors.toSet());
      if (!actualDeclarations.equals(ownDeclarations))
        return new SnapshotProjector.Projection(
            Optional.empty(), List.of("Incomplete coordinated binding declaration coverage"));
      declarations.addAll(actualDeclarations);
      participants.add(
          new BindingEvidence(
              snapshot.run(),
              snapshot.checkpoint(),
              participant.thread(),
              participant.worker(),
              participant.activation(),
              participant.bindings()));
    }
    if (!workers.equals(cut.participants().keySet()))
      throw new IOException("Incomplete coordinated capture participant coverage");
    if (!declarations.equals(expectedBindings))
      return new SnapshotProjector.Projection(
          Optional.empty(), List.of("Incomplete coordinated binding declaration coverage"));
    if (!declarations.isEmpty()) exercised.add("local-bindings");
    return SnapshotProjector.projectParticipants(snapshot, layout, participants);
  }
}
