package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.QueueProjector;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueuePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Source-selected queue admission. Library data is integration knowledge, never graph semantics.
 */
final class QueueResources {
  private QueueResources() {}

  static boolean selected(Path directory) {
    return Files.exists(directory.resolve("generated/queues.properties"));
  }

  static boolean controlled(TargetProfile profile, Path directory) {
    return profile.retainedMetadata() || selected(directory);
  }

  static void selection(Path directory, ObservationLayout layout) throws IOException {
    if (selected(directory) != (layout.queuePlan() != null))
      throw new IOException("Queue plan/layout selection mismatch");
    if (layout.queuePlan() != null
        && !QueuePlan.read(directory.resolve("generated/queues.properties"))
            .equals(layout.queuePlan()))
      throw new IOException("Queue resource plan differs from layout");
  }

  /** Reconstructs only source roles, not expected members or observed identities. */
  static void source(GirProgram program, ObservationLayout layout) throws IOException {
    if (ReferenceProgram.usesQueues(program) != (layout.queuePlan() != null))
      throw new IOException("Source/layout queue selection mismatch");
    if (layout.queuePlan() == null) return;
    var expected = new ArrayList<QueuePlan.Resource>();
    for (var queue : program.queues())
      expected.add(new QueuePlan.Resource(expected.size() + 1, queue.name(), queue.shape()));
    if (!layout.queuePlan().equals(new QueuePlan(expected)))
      throw new IOException("Queue resources differ from source declarations");
  }

  static List<String> configuration(TargetProfile profile) {
    var problems = new ArrayList<>(ControlledLaunch.configuration(profile, true));
    if (profile.vm().equals("openj9") && !profile.collector().equals("gencon"))
      problems.add("Named queue ownership qualification covers OpenJ9 gencon");
    return List.copyOf(problems);
  }

  /** Adds only observed ownership facts; any queue gap preserves usable ordinary graph evidence. */
  static SnapshotProjector.Projection project(
      Path directory,
      HeapSnapshot capture,
      ObservationLayout layout,
      String vm,
      SnapshotProjector.Projection projection) {
    if (layout.queuePlan() == null) return projection;
    var problems = new ArrayList<>(projection.problems());
    var usable = projection.covered();
    if (usable.isEmpty()) usable = SnapshotProjector.project(capture, layout).graph();
    if (usable.isEmpty()) return projection;
    var graph = usable.orElseThrow();
    try {
      var queues =
          QueueProjector.project(
              capture,
              QueueCapture.read(
                  directory.resolve("snapshots/" + capture.checkpoint() + ".gqueue"),
                  capture.run(),
                  capture.checkpoint()),
              layout,
              vm);
      problems.addAll(queues.problems());
      graph =
          new Graph(
              graph.objects(), graph.roots(), graph.handles(), graph.wrappers(), queues.queues());
    } catch (IOException | RuntimeException unavailable) {
      problems.add("Queue ownership extraction failed: " + unavailable);
    }
    return new SnapshotProjector.Projection(
        problems.isEmpty() && projection.graph().isPresent()
            ? Optional.of(graph)
            : Optional.empty(),
        problems,
        Optional.of(graph));
  }

  /**
   * Uses controlled launch plus known bootstrap bytes and actual API exercise before ACK/offline.
   */
  static void admission(TargetProfile profile, JsonObject manifest, Properties facts)
      throws IOException {
    ControlledLaunch.evidence(profile, manifest, facts, true);
    var unmet = new ArrayList<String>();
    ControlledLaunch.effective(profile, facts, unmet, true);
    if (!unmet.isEmpty()) throw new IOException(String.join("; ", unmet));
    if (!facts.getProperty("observer.queues.ready", "false").equals("true")
        || !facts.getProperty("observer.queues.bootstrap", "false").equals("true"))
      throw new IOException("Named queue setup/API exercise was not established");
    if (!facts.getProperty("os.name", "").equals("Linux")
        || !facts.getProperty("os.arch", "").equals("amd64"))
      throw new IOException("Queue capture coordinate qualification requires Linux amd64");
    String feature = facts.getProperty("java.feature", "");
    if (profile.vm().equals("openj9")) {
      if (!feature.equals("21")
          || !facts.getProperty("java.vm.version", "").equals("master-6e16eb1a4f"))
        throw new IOException("OpenJ9 queue layout needs qualification for this VM build");
    } else if (!Set.of("17", "21").contains(feature))
      throw new IOException("HotSpot queue layout qualification covers JDK 17/21");
    List<String> actual =
        List.of(
            facts.getProperty("queue.library.Reference", ""),
            facts.getProperty("queue.library.ReferenceQueue", ""),
            facts.getProperty("queue.library.ReferenceQueue$Null", ""));
    List<String> expected =
        profile.vm().equals("openj9") ? OPENJ9 : feature.equals("17") ? HOTSPOT17 : HOTSPOT21;
    if (!actual.equals(expected))
      throw new IOException("Unqualified bootstrap queue library bytes");
  }

  // Qualified class resources from the exercised libraries. Controlled launch excludes a later
  // transformer/alternate loader; these hashes alone are not loaded-definition certificates.
  private static final List<String> HOTSPOT17 =
      List.of(
          "46026f0a24279bef0b5247e072ce22020e916e9b297b73b090a7499037c20bb4",
          "036be43a9c8fcdfd9d7a0603a6d3d0111db0337fb4ab963c030ef886b06fa6a7",
          "fedf1dd3543d2ad276e6198a1aaf9cc002e9bf63d35553f08284c008cb6c9ea5");
  private static final List<String> HOTSPOT21 =
      List.of(
          "6a6e5bce752ff860988c05b28bb0b641e70c137f58170078cef8a9dc33304aef",
          "e68f9766800b3ae557ea020ab2ba337ada319366058e84ed3d3ae10df4f82911",
          "30c7305f924cc078fb11f28a3cbbacf7d529588c8849fe5f89eb816c11e98c19");
  private static final List<String> OPENJ9 =
      List.of(
          "a5466494ce46b59db42ccee426223f7f4a8587b62c329c74fd9e6df035e415c9",
          "f7d128ee2111bec5d6f72c1ce7ec458f0dc8179f1f966bfe277edbf0b4be655f",
          "missing");
}
