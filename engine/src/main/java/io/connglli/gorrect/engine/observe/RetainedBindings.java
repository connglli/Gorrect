package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.util.Map;

/**
 * Scopes and parked frames supplied by code/frame verification; raw heap evidence stays separate.
 */
public record RetainedBindings(
    Map<Long, HeapSnapshot.MethodInfo> methods, Map<Integer, Participant> participants) {
  /** One certified nonrecursive role activation; thread and slot still come from physical roots. */
  public record Participant(long thread, FrameEvidence.Frame frame, FrameEvidence.Role role) {}

  public RetainedBindings {
    methods = Map.copyOf(methods);
    participants = Map.copyOf(participants);
  }
}
