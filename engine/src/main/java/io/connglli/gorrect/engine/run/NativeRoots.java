package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.NativeRootProgram;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.CheckpointPlan;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.NativeRootPlan;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Source selection, actual JNI qualification and retained resource lifecycle checks. */
final class NativeRoots {
  private NativeRoots() {}

  static boolean selected(Path directory) {
    return Files.exists(directory.resolve("generated/native-roots.properties"));
  }

  /** Source names select slots only; expected targets never enter the native plan. */
  static void source(GirProgram program, ObservationLayout layout) throws IOException {
    var names = NativeRootProgram.roots(program);
    var expected = names.isEmpty() ? null : new NativeRootPlan(names);
    if (!Objects.equals(expected, layout.nativeRootPlan()))
      throw new IOException("Native root layout differs from source declarations");
  }

  static void selection(Path directory, ObservationLayout layout) throws IOException {
    if (selected(directory) != (layout.nativeRootPlan() != null))
      throw new IOException("Native root plan/layout selection differs");
    if (layout.nativeRootPlan() != null
        && !NativeRootPlan.read(directory.resolve("generated/native-roots.properties"))
            .equals(layout.nativeRootPlan()))
      throw new IOException("Native root resource plan differs from layout");
  }

  static List<String> configuration(TargetProfile profile) {
    var problems = new ArrayList<>(ControlledLaunch.configuration(profile, true));
    if (!ObservationProfile.parse(profile.observation()).controlled())
      problems.add("Native roots require controlled observation");
    return List.copyOf(problems);
  }

  /** Uses recorded launch inputs and exercised startup qualification, never current VM files. */
  static void admission(
      TargetProfile profile, JsonObject manifest, Properties facts, Path directory)
      throws IOException {
    if (!selected(directory)) throw new IOException("Missing selected native root plan");
    ControlledLaunch.evidence(profile, manifest, facts, true);
    var problems = new ArrayList<String>();
    ControlledLaunch.effective(profile, facts, problems, true);
    if (!problems.isEmpty()) throw new IOException(String.join("; ", problems));
    if (!facts.getProperty("os.name", "").equals("Linux")
        || !facts.getProperty("os.arch", "").equals("amd64"))
      throw new IOException("Native root coordinates require qualified Linux amd64");
    String feature = facts.getProperty("java.feature", "");
    if (profile.vm().equals("hotspot")) {
      if (!Set.of("17", "21").contains(feature))
        throw new IOException("Native root qualification covers HotSpot JDK 17/21");
    } else if (!profile.vm().equals("openj9") || !feature.equals("21"))
      throw new IOException("Native root qualification covers OpenJ9 JDK 21");
    if (!facts.getProperty("observer.nativeRoots.qualified", "false").equals("true"))
      throw new IOException("Actual JNI root kind/tag/multiplicity qualification is missing");
  }

  /** Joins a slot certificate to the independently checked actual checkpoint visit. */
  static NativeRootEvidence read(
      Path directory, HeapSnapshot snapshot, ObservationLayout layout, CheckpointPlan.Visit visit)
      throws IOException {
    if (layout.nativeRootPlan() == null) return null;
    var evidence =
        NativeRootEvidence.read(
            directory.resolve("snapshots/" + snapshot.checkpoint() + ".gnative"),
            snapshot.run(),
            snapshot.checkpoint());
    context(snapshot, layout, evidence, false);
    if (visit == null
        || evidence.site() != visit.operation()
        || evidence.visit() != visit.occurrence())
      throw new IOException("Native root evidence belongs to another source visit");
    return evidence;
  }

  /** Post-delete evidence proves empty native storage, not object reclamation. */
  static NativeRootEvidence cleanup(
      Path directory, long run, long lastSequence, ObservationLayout layout) throws IOException {
    if (layout.nativeRootPlan() == null) return null;
    long sequence;
    try {
      sequence = Math.addExact(lastSequence, 1);
    } catch (ArithmeticException excessive) {
      throw new IOException("Native cleanup sequence overflow", excessive);
    }
    Path graph = directory.resolve("traces/native-cleanup.gsnap");
    if (Files.size(graph) > SnapshotCodec.MAX_BYTES)
      throw new IOException("Native cleanup snapshot exceeds byte limit");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(graph), run, sequence);
    var evidence =
        NativeRootEvidence.read(directory.resolve("traces/native-cleanup.gnative"), run, sequence);
    context(snapshot, layout, evidence, true);
    if (evidence.site() != 0 || evidence.visit() != 1)
      throw new IOException("Native cleanup has an invalid completion visit");
    var projection = SnapshotProjector.projectNativeCleanup(snapshot, layout, evidence);
    if (projection.graph().isEmpty())
      throw new IOException("Native cleanup extraction failed: " + projection.problems());
    return evidence;
  }

  private static void context(
      HeapSnapshot snapshot, ObservationLayout layout, NativeRootEvidence evidence, boolean cleanup)
      throws IOException {
    if (evidence.cleanup() != cleanup
        || !evidence.complete()
        || evidence.generationLimit() != NativeRootPlan.MAX_GENERATIONS
        || evidence.before().size() != layout.nativeRootPlan().roots().size()
        || evidence.startsBefore() != snapshot.gcStartsBefore()
        || evidence.finishesBefore() != snapshot.gcFinishesBefore()
        || evidence.startsAfter() != snapshot.gcStartsAfter()
        || evidence.finishesAfter() != snapshot.gcFinishesAfter())
      throw new IOException("Native root table coverage, phase or walk counters differ");
  }

  /** Failed chronology checks leave the last accepted certificate unchanged. */
  static final class Tracker {
    private NativeRootEvidence previous;
    private long maximum;
    private boolean exercised;

    void accept(NativeRootEvidence evidence) throws IOException {
      if (evidence == null) return;
      if (!evidence.complete()) throw new IOException("Incomplete native slot brackets");
      if (previous != null
          && (previous.run() != evidence.run()
              || previous.checkpoint() >= evidence.checkpoint()
              || previous.after().size() != evidence.after().size()
              || previous.cleanup())) throw new IOException("Native root capture sequence differs");

      long nextMaximum = maximum;
      var generations = new HashSet<Long>();
      for (var slot : evidence.after()) {
        if (slot.generation() != 0 && !generations.add(slot.generation()))
          throw new IOException("Native acquisition generation is shared by slots");
        if (previous != null) {
          var before = previous.after().get(slot.slot());
          // A failed prefix may acquire globals after its last checkpoint. Only the actual
          // terminal cut (site zero) excludes further workload acquisitions before cleanup.
          if (slot.generation() < before.generation()
              || evidence.cleanup()
                  && previous.site() == 0
                  && slot.generation() != before.generation())
            throw new IOException("Native acquisition generation moved backwards or at cleanup");
          if (slot.generation() == before.generation()) {
            if (slot.handle() != 0
                && (before.handle() == 0
                    || slot.handle() != before.handle()
                    || slot.tag() != before.tag()
                    || slot.kind() != before.kind()))
              throw new IOException("Native target changed without a fresh acquisition");
          } else if (slot.generation() <= maximum)
            throw new IOException("Native acquisition generation was reused");
        }
        nextMaximum = Math.max(nextMaximum, slot.generation());
      }

      maximum = nextMaximum;
      exercised |= nextMaximum != 0;
      previous = evidence;
    }

    boolean exercised() {
      return exercised;
    }
  }
}
