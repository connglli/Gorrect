package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.protocol.QueuePlan;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Joins actual producer files through the host decoder; no expected member supplies a physical tag.
 */
@Tag("observer")
class AutomaticQueueCaptureIntegrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"automatic", "automatic-retained"})
  void actualDeclaredLocksAndCallerCertifyTheSameWalkForBothProviders(String mode)
      throws Exception {
    Path root = Path.of(System.getProperty("gorrect.automaticQueue.artifacts")).resolve(mode);
    long caller = Long.parseLong(Files.readString(root.resolve("caller.txt")));
    var shape = new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0);
    var layout =
        new ObservationLayout(
            List.of(),
            List.of(),
            new ObservationLayout.Roots(
                "LCase$AutomaticRoots;", Map.of("first", "first", "second", "second")),
            List.of(
                new ObservationLayout.ReferenceType(
                    "LCase$W;",
                    Value.ReferenceKind.WEAK,
                    "idWorker",
                    "idSite",
                    "idOccurrence",
                    "idSlot",
                    "referent",
                    shape)),
            List.of(),
            ObservationLayout.REQUIRED,
            mode.equals("automatic-retained") ? "retained-class" : "jvmti",
            new QueuePlan(
                List.of(new QueuePlan.Resource(1, "a", "W"), new QueuePlan.Resource(2, "b", "W")),
                true));
    boolean arrival = false, partial = false;
    try (var files = Files.list(root)) {
      for (var file :
          files.filter(path -> path.getFileName().toString().endsWith(".gqlock")).toList()) {
        long cut = Long.parseLong(file.getFileName().toString().replace(".gqlock", ""));
        var graph = SnapshotCodec.read(Files.readAllBytes(root.resolve(cut + ".gsnap")), 31, cut);
        var queues = QueueCapture.read(root.resolve(cut + ".gqueue"), 31, cut);
        var custody = QueueConsistency.read(file, 31, cut);
        var result = QueueProjector.project(graph, queues, layout, "hotspot", custody, caller);
        if (mode.equals("automatic-retained")) {
          var frames = FrameEvidence.read(root.resolve(cut + ".gframe"), 31, cut);
          assertEquals(caller, frames.participants().getFirst().thread());
          assertEquals(FrameEvidence.Role.MAIN, frames.participants().getFirst().role());
        }
        if (graph.status() != HeapSnapshot.Status.COMPLETE) {
          assertFalse(result.complete());
          partial = true;
          continue;
        }
        assertTrue(result.complete(), result.problems().toString());
        // These are genuine same-origin JNI locals, but neither is the actual caller Thread.
        for (long alias :
            new long[] {
              custody.locks().getFirst().queueTag(), custody.locks().getFirst().lockTag()
            }) {
          var rows =
              custody.locks().stream()
                  .map(
                      row ->
                          new QueueConsistency.Lock(
                              row.resource(),
                              row.rootsClass(),
                              row.rootField(),
                              row.kind(),
                              row.queueTag(),
                              row.lockTag(),
                              alias,
                              alias,
                              row.entriesBefore(),
                              row.entriesAfter(),
                              row.cleanup()))
                  .toList();
          var forged =
              new QueueConsistency(
                  custody.run(),
                  custody.checkpoint(),
                  custody.thread(),
                  alias,
                  custody.startsBefore(),
                  custody.finishesBefore(),
                  custody.startsAfter(),
                  custody.finishesAfter(),
                  rows);
          var rejected = QueueProjector.project(graph, queues, layout, "hotspot", forged, caller);
          assertFalse(rejected.complete(), "queue/lock JNI local impersonated actual caller");
          assertTrue(
              rejected.queues().values().stream().allMatch(queue -> queue.members() == null));
        }
        assertEquals(1, result.queues().get("a").identity());
        assertEquals(2, result.queues().get("b").identity());
        arrival |=
            result.queues().get("a").members().size() == 1
                && result.queues().get("b").members().size() == 1;
        var graphProjection = SnapshotProjector.project(graph, layout);
        assertTrue(graphProjection.graph().isPresent(), graphProjection.problems().toString());
      }
    }
    assertTrue(arrival, "actual handler activity, with no explicit insertion into these queues");
    assertTrue(partial, "actual quota cut remains incomplete despite lock custody");
  }
}
