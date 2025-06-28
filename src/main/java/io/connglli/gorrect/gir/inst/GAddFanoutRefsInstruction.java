package io.connglli.gorrect.gir.inst;

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * GAddFanoutRefsInstruction adds a lot of references to a single referer. The
 * purpose of this instruction is to efficiently add multiple references at the
 * same time to change the reference relation of the object graph.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("add-fanin-refs")
public class GAddFanoutRefsInstruction extends GInstruction {
  /** The source object of the new references */
  @GIRInput(0) @NonNull GID refererId;

  /** The target objects being referenced */
  @GIRInput(1) @NonNull List<GID> referentIds;

  /** The target objects being referenced by which strength */
  List<GReference.Type> referenceTypes;

  /** The newly created references */
  @GIROutput(0) @NonNull List<GID> referenceIds;

  @Override
  public <T> T accept(GInstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
