package io.connglli.gorrect.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.GAssert;
import java.util.Set;
import lombok.NonNull;

public class OracleObject {

  @NonNull private final OracleHeap heap;

  @NonNull private final GObject object;

  public OracleObject(@NonNull OracleHeap heap, @NonNull GObject object) {
    this.heap = heap;
    this.object = object;
  }

  public GID getId() { return object.getId(); }

  public GObject.Type getType() { return object.getType(); }

  public void toRoot() {
    GAssert.set(
        object.getType() == GObject.Type.ORDINARY,
        "Object with id %d is not a non-root object".formatted(getId()));
    object.setType(GObject.Type.GCROOT);
  }

  public void toNonRoot() {
    GAssert.set(object.getType() == GObject.Type.GCROOT,
                "Object with id %d is not a root object".formatted(getId()));
    object.setType(GObject.Type.ORDINARY);
  }

  public long getSize() { return OracleHeap.estimateObjectSize(this); }

  public Set<OracleReference> getReferences() {
    return heap.getReferences(this);
  }

  public Set<OracleObject> getReferents() { return heap.getReferents(this); }

  public Set<OracleObject> getStrongReferents() {
    return heap.getStrongReferents(this);
  }

  public Set<OracleObject> getSoftReferents() {
    return heap.getSoftReferents(this);
  }

  public Set<OracleObject> getWeakReferents() {
    return heap.getWeakReferents(this);
  }

  public void strongReference(@NonNull GID referenceId,
                              @NonNull OracleObject referent) {
    heap.strongReference(referenceId, this, referent);
  }

  public void softReference(@NonNull GID referenceId,
                            @NonNull OracleObject referent) {
    heap.softReference(referenceId, this, referent);
  }

  public void weakReference(@NonNull GID referenceId,
                            @NonNull OracleObject referent) {
    heap.weakReference(referenceId, this, referent);
  }

  /*package*/ void reference(@NonNull GID referenceId,
                             @NonNull OracleObject referent,
                             @NonNull GReference.Type type) {
    heap.reference(referenceId, this, referent, type);
  }

  public void unlink(@NonNull OracleObject referent) {
    heap.unlink(this, referent);
  }

  public void free() { heap.freeObject(this); }
}
