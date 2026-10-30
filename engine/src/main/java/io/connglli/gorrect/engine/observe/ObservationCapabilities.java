package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.Instruction;
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
    var reasons = new ArrayList<String>();
    boolean history =
        ReferenceProgram.usesReferences(program) || HistoryProgram.usesConcurrentHistory(program);
    if (!profile.controlled() && history)
      reasons.add("Natural reference/contended histories require path-sensitive liveness rules");
    if (!profile.controlled()) {
      var arrays =
          program.shapes().stream()
              .filter(shape -> shape.kind() != Shape.Kind.OBJECT)
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
      if (program
          .instructions()
          .anyMatch(
              instruction ->
                  instruction instanceof Instruction.IfPresent
                      || instruction instanceof Instruction.Acquire))
        reasons.add("Natural liveness requires unconditional control flow");
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
    var result = new ArrayList<Capability>();
    for (String mechanism :
        List.of("named-roots", "local-bindings", "semantic-reads", "alias-checks")) {
      var reasons = new ArrayList<>(combination);
      if ((bits & HeapSnapshot.TAGGING) == 0) reasons.add("Object tagging unavailable");
      if (mechanism.equals("local-bindings")) {
        if (!profile.bindings()) reasons.add("Profile does not map local bindings");
        if ((bits & HeapSnapshot.LOCAL_VARIABLES) == 0)
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
                  ? "Quiescent main-thread checkpoint; unique activation and local mapping"
                  : "Validated GIR and supported profile combination",
              local
                  ? "Observe bindings without acquiring their objects"
                  : mechanism.equals("named-roots")
                      ? "Observe static roots and accessible contents"
                      : "Record the result of an explicit GIR read or identity comparison",
              local
                  ? "Read stack slot and reachable graph; no workload writes"
                  : mechanism.equals("named-roots")
                      ? "Read named static slots and reachable graph"
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
                  profile.bindings()
                      ? "local-variable metadata capability"
                      : "no local-variable access capability",
                  profile.controlled()
                      ? "diagnostic array binding and identity reads when used"
                      : "no diagnostic array binding")));
    }
    return List.copyOf(result);
  }
}
