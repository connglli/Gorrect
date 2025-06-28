package io.connglli.gorrect.gir.inst;

import io.connglli.gorrect.gir.model.GID;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

/**
 * GRemoveAllReferentsInstruction removes all referents of a specified object
 * This instruction is used to clear all references to an object, effectively
 * removing its connections to other objects in the graph.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@GIROpCode("remove-all-referents")
public class GRemoveAllReferentsInstruction extends GInstruction {

  @GIRInput(0) @NonNull GID refererId;

  @Override
  public <T> T accept(GInstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
