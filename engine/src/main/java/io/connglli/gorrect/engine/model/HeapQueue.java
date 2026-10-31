package io.connglli.gorrect.engine.model;

import java.util.Set;

/**
 * Independently covered queue facts. Null components mean missing coverage; an absent map key means
 * neither fact is covered. A certified null static root has identity zero and an empty member set,
 * distinct from a present empty resource. Expected model components are always non-null, with
 * positive declaration identities. Other observed IDs remain contradictions.
 */
public record HeapQueue(Integer identity, Set<WrapperId> members) {
  public HeapQueue {
    if (members != null) members = Set.copyOf(members);
  }

  public boolean complete() {
    return identity != null && members != null;
  }
}
