package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.gir.ArrayCopyProgram;
import io.connglli.gorrect.engine.gir.CloneProgram;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.IdentityHashProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.MethodProgram;
import io.connglli.gorrect.engine.gir.MonitorProgram;
import io.connglli.gorrect.engine.gir.NativeRootProgram;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Concrete observation contracts and supported combinations, shared by preflight and reporting. */
public final class ObservationCapabilities {
  /** Availability is preflight evidence; exercised and verified describe this particular run. */
  public record Capability(
      String mechanism,
      String profile,
      boolean available,
      boolean exercised,
      boolean verified,
      List<String> unsupportedReasons,
      String preconditions,
      String effects,
      String memoryFootprint,
      List<String> requiredEvidence,
      List<String> observerEffects) {
    public Capability {
      unsupportedReasons = List.copyOf(unsupportedReasons);
      requiredEvidence = List.copyOf(requiredEvidence);
      observerEffects = List.copyOf(observerEffects);
    }
  }

  private ObservationCapabilities() {}

  /** Reasons the selected profile cannot faithfully execute/check this combination. */
  public static List<String> unsupported(GirProgram program, ObservationProfile profile) {
    var reasons = new ArrayList<String>(CloneProgram.unsupported(program));
    reasons.addAll(MethodProgram.unsupported(program));
    if (MethodProgram.selected(program) && !profile.controlled())
      reasons.add("Methods require controlled observation");
    reasons.addAll(NativeRootProgram.unsupported(program));
    if (NativeRootProgram.selected(program) && !profile.controlled())
      reasons.add("Native roots require controlled observation");
    reasons.addAll(MonitorProgram.unsupported(program));
    if (MonitorProgram.usesMonitors(program) && !profile.controlled())
      reasons.add("Monitors require controlled observation");
    reasons.addAll(IdentityHashProgram.unsupported(program));
    if (IdentityHashProgram.usesIdentityHash(program) && !profile.controlled())
      reasons.add("Identity hashes require controlled observation");
    if (CloneProgram.usesClones(program) && !profile.controlled())
      reasons.add("Cloning requires controlled observation");
    reasons.addAll(ArrayCopyProgram.unsupported(program));
    if (ArrayCopyProgram.usesCopies(program) && !profile.controlled())
      reasons.add("Array copying requires controlled observation");
    if (ReferenceProgram.usesFirstClass(program)) {
      reasons.addAll(ReferenceProgram.firstClassGaps(program));
      if (!profile.controlled()) reasons.add("First-class wrappers require controlled observation");
      if (ReferenceProgram.hasParallel(program))
        reasons.add("First-class wrapper concurrency is unsupported");
    }
    boolean history =
        ReferenceProgram.usesReferences(program) || HistoryProgram.usesConcurrentHistory(program);
    if (HistoryProgram.hasWorkerCheckpoints(program) && !profile.bindings())
      reasons.add("Worker checkpoints require controlled local-binding observation");
    if (!profile.controlled() && history)
      reasons.add("Natural reference/contended histories require path-sensitive liveness rules");
    if (!profile.controlled()) {
      var arrays =
          program.shapes().stream()
              .filter(shape -> shape.array())
              .map(Shape::name)
              .collect(Collectors.toSet());
      if (program.objects().stream().anyMatch(object -> arrays.contains(object.shape()))
          || program
              .instructions()
              .anyMatch(
                  instruction ->
                      instruction instanceof Instruction.Allocate allocation
                          && arrays.contains(allocation.shape())))
        reasons.add("Natural liveness excludes diagnostic array identity binding");
    }
    return reasons;
  }

  /** Derived report only; verifiers never treat this report as proof of its own claims. */
  public static List<Capability> describe(
      GirProgram program,
      ObservationProfile profile,
      Properties target,
      Set<String> exercised,
      Set<String> verified) {
    long bits = Long.parseLong(target.getProperty("observer.capabilities", "0"));
    var combination = unsupported(program, profile);
    boolean nativeRoots = NativeRootProgram.selected(program);
    var result = new ArrayList<Capability>();
    for (String mechanism :
        List.of("named-roots", "local-bindings", "semantic-reads", "alias-checks")) {
      var reasons = new ArrayList<>(combination);
      if ((bits & HeapSnapshot.TAGGING) == 0) reasons.add("Object tagging unavailable");
      if (mechanism.equals("local-bindings")) {
        if (!profile.bindings()) reasons.add("Profile does not map local bindings");
        boolean retained =
            target.getProperty("observer.bindingMetadata", "jvmti").equals("retained-class");
        if (retained
            ? !target.getProperty("observer.retained.ready", "false").equals("true")
                || (bits & HeapSnapshot.LOCAL_VARIABLES) != 0
            : (bits & HeapSnapshot.LOCAL_VARIABLES) == 0)
          reasons.add("Local-variable metadata unavailable");
      }
      boolean local = mechanism.equals("local-bindings");
      result.add(
          new Capability(
              mechanism,
              profile.profileName(),
              reasons.isEmpty(),
              exercised.contains(mechanism),
              verified.contains(mechanism) && exercised.contains(mechanism) && reasons.isEmpty(),
              reasons,
              local
                  ? "Quiescent checkpoint; complete participants, unique activations and local"
                      + " mapping"
                  : "Validated GIR and supported profile combination",
              local
                  ? "Observe bindings without acquiring their objects"
                  : mechanism.equals("named-roots")
                      ? "Observe static roots and accessible contents"
                      : "Record the result of an explicit GIR read or identity comparison",
              local
                  ? "Read stack slot and reachable graph; no workload writes"
                  : mechanism.equals("named-roots")
                      ? nativeRoots
                          ? "Read declared roots and reachable graph"
                          : "Read named static slots and reachable graph"
                      : mechanism.equals("semantic-reads")
                          ? "Read selected payload slot"
                          : "Read two private strong operands; no shared-memory access",
              local
                  ? List.of(
                      "binding presence",
                      "thread/activation",
                      "local-variable scope",
                      "raw stack root for non-null bindings",
                      "complete heap capture")
                  : mechanism.equals("named-roots")
                      ? List.of("class visit", "field metadata", "complete heap capture")
                      : List.of(
                          "complete per-worker scalar trace", "independent GIR interpretation"),
              List.of(
                  profile.controlled()
                      ? "reachability fences"
                      : "future-use obligations; no lifetime fences",
                  "primitive recording",
                  "JVMTI tagging and heap-walk optimization/timing effects",
                  target.getProperty("observer.bindingMetadata", "jvmti").equals("retained-class")
                      ? "retained-class metadata and bounded stack inspection; no local access"
                      : profile.bindings()
                          ? "local-variable metadata capability"
                          : "no local-variable access capability",
                  profile.controlled()
                      ? "diagnostic array binding and identity reads when used"
                      : "no diagnostic array binding")));
    }
    if (CloneProgram.usesObjectClones(program)) {
      var reasons = new ArrayList<>(combination);
      if (!target.getProperty("observer.objectClones.qualified", "false").equals("true"))
        reasons.add("Generated object clone qualification unavailable");
      boolean used = exercised.contains("object-clones");
      result.add(
          new Capability(
              "object-clones",
              profile.profileName(),
              reasons.isEmpty(),
              used,
              verified.contains("object-clones") && used && reasons.isEmpty(),
              reasons,
              "Strong sequential plain execution; source-selected clone identity roles",
              "Real Object.clone shallow copy and fresh logical allocation identity",
              "Copied object plus two diagnostic identity stores; no identity registry",
              List.of(
                  "generated-class startup qualification",
                  "complete exact trace",
                  "source-checked layout and actual diagnostic fields",
                  "complete heap capture"),
              List.of(
                  "clone allocation and copying",
                  "diagnostic identity stores",
                  "startup qualification",
                  "capture optimization and timing effects")));
    }
    if (MethodProgram.selected(program)) {
      var reasons = new ArrayList<>(combination);
      if (!target.getProperty("observer.bindingMetadata", "jvmti").equals("retained-class"))
        reasons.add("Methods require retained-class binding metadata");
      if (!target.getProperty("observer.retained.ready", "false").equals("true"))
        reasons.add("Loaded-class certification unavailable");
      result.add(
          new Capability(
              "method-calls",
              profile.profileName(),
              reasons.isEmpty(),
              exercised.contains("method-calls"),
              verified.contains("method-calls")
                  && exercised.contains("method-calls")
                  && reasons.isEmpty(),
              reasons,
              "Acyclic strong sequential calls; captures after helpers return",
              "Direct typed static calls with caller, parameter and local lifetimes",
              "Java frames and primitive call markers; no workload-reference registry",
              List.of(
                  "complete exact trace",
                  "certified helper absence at captures",
                  "loaded-byte provenance"),
              List.of("reachability fences", "primitive entry/exit recording")));
    }
    if (nativeRoots) {
      var reasons = new ArrayList<>(combination);
      if ((bits & HeapSnapshot.TAGGING) == 0) reasons.add("Object tagging unavailable");
      if (!target.getProperty("observer.nativeRoots.qualified", "false").equals("true"))
        reasons.add("Actual JNI root qualification unavailable");
      boolean used = exercised.contains("native-roots");
      result.add(
          new Capability(
              "native-roots",
              profile.profileName(),
              reasons.isEmpty(),
              used,
              verified.contains("native-roots") && used && reasons.isEmpty(),
              reasons,
              "Strong sequential controlled source; qualified JNI root callbacks",
              "Retain targets in workload-owned global slots until deletion or cleanup",
              "Declared globals plus one temporary replacement; borrowed JNI locals",
              List.of(
                  "source-selected slot plan",
                  "startup reference-kind/tag/multiplicity qualification",
                  "same-walk slot brackets and raw global-root multiset",
                  "post-delete empty table and complete walk"),
              List.of(
                  "JNI transitions and temporary local handles",
                  "startup qualification and checkpoint heap walks")));
    }
    return List.copyOf(result);
  }
}
