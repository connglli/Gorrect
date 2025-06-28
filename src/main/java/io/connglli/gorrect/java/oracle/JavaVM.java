package io.connglli.gorrect.java.oracle;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.OracleObject;
import lombok.NonNull;

public class JavaVM extends LangVM {
  public JavaVM(long heapCapacity) { super(heapCapacity); }

  @Override
  public void onOracleAllocate(@NonNull GID objectId, long size,
                               GObject.Type type) {
    if (heapUnusedBytes < estimateNewObjectSize(size)) {
      throw new OutOfMemError(
          "Heap out of memory: " + heapUnusedBytes + " bytes left, " + size +
          " bytes needed for object of type " + type.name());
    }
    heapUnusedBytes -= estimateNewObjectSize(size);
  }

  public void onOracleFree(@NonNull OracleObject object) {
    heapUnusedBytes += estimateObjectSize(object);
  }

  @Override
  public void onOracleClean() {}

  @Override
  public void onOracleCompact() {}

  @Override
  public void onOracleStressCompact() {}

  @Override
  public void
  onOracleReference(@NonNull GID referenceId, @NonNull OracleObject referer,
                    @NonNull OracleObject referent, GReference.Type type) {}

  @Override
  public void onOracleUnlink(@NonNull OracleObject referer,
                             @NonNull OracleObject referent) {}

  @Override
  public long estimateNewObjectSize(long requestedSize) {
    System.out.println(
        "WARNING: OracleHeap.estimateNewObjectSize() is current NOT reliable");
    // TODO: Consider metadata, vtable, type, etc.
    // We know that class have a base class GGeneratedClassBase.
    return requestedSize;
  }

  @Override
  public long estimateObjectSize(OracleObject object) {
    System.out.println(
        "WARNING: OracleHeap.estimateObjectSize() is current NOT reliable");
    // TODO: Consider referents, etc.
    // We know that each reference <u,v,s> has a  field in
    // GGeneratedClassU.s_ref_to_v and the class has a super class called
    // GGeneratedClassBase. No other super classes, no other subclasses, etc.
    return object.getSize();
  }
}
