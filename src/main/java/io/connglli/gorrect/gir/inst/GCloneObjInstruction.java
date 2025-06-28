package io.connglli.gorrect.gir.inst;

import io.connglli.gorrect.gir.model.GID;
import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * CloneObjInstruction clones an object in the graph, including all its
 * referents.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("clone-obj")
public class GCloneObjInstruction extends GInstruction {

  /** The object to be cloned. */
  @GIRInput(0) GID objectId;

  /** The cloned object. */
  @GIROutput(1) GID cloneId;

  @Override
  public <T> T accept(GInstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
