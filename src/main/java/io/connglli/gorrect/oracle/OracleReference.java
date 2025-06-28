package io.connglli.gorrect.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.GAssert;
import lombok.AllArgsConstructor;
import lombok.NonNull;

@AllArgsConstructor
public class OracleReference {

  @NonNull private final OracleHeap heap;

  @NonNull private final GReference reference;

  public GID getId() { return reference.getId(); }

  public GReference.Type getType() { return reference.getType(); }

  public OracleObject getReferer() {
    return heap.getObject(reference.getReferer());
  }

  public OracleObject getReferent() {
    return heap.getObject(reference.getReferent());
  }

  public void strengthen() {
    switch (getType()) {
    case GReference.Type.STRONG:
      GAssert.panic(
          "Reference with id %d is already a strong reference".formatted(
              getId()));
      break;

    case GReference.Type.SOFT:
      reference.setType(GReference.Type.STRONG);
      break;

    case GReference.Type.WEAK:
      reference.setType(GReference.Type.SOFT);
      break;

    default:
      GAssert.panic("Cannot reach here");
      break;
    }
  }

  public void soften() {
    switch (getType()) {
    case GReference.Type.WEAK:
      GAssert.panic(
          "Reference with id %d is already a weak reference".formatted(
              getId()));
      break;

    case GReference.Type.SOFT:
      reference.setType(GReference.Type.WEAK);
      break;

    case GReference.Type.STRONG:
      reference.setType(GReference.Type.SOFT);
      break;

    default:
      GAssert.panic("Cannot reach here");
      break;
    }
  }

  public void unlink() { heap.unlink(getReferer(), getReferent()); }

  public void redirect(@NonNull OracleObject newReferent) {
    GAssert.set(newReferent.getId() != getReferent().getId(),
                "Cannot redirect reference %d to the same referent".formatted(
                    getId()));
    heap.unlink(getReferer(), getReferent());
    heap.reference(getId(), getReferer(), newReferent, getType());
  }
}
