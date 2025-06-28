/*
 * MIT License
 *
 * Copyright (c) 2025-2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect.gir.inst;

import io.connglli.gorrect.utility.GAssert;
import io.connglli.gorrect.utility.Reflections;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.List;

/**
 * GInstruction represents an instruction of the Gorrect IR (GIR).
 * All instructions of GIR operate closely over an object graph (and nothing
 * else) assuming unlimited heap memory, specifically the operations are on
 * objects and references in the graph.
 *
 * Each instruction has an opcode, which is defined by the {@link GIROpCode
 * annotation.
 *
 * The inputs and outputs of the instruction are annotated with {@link GIRInput}
 * and {@link GIROutput}, respectively.
 */
public abstract class GInstruction {
  private String opCode;

  public String getOpCode() {
    if (opCode == null) {
      GIROpCode opCodeAnnot = getClass().getAnnotation(GIROpCode.class);
      GAssert.set(opCodeAnnot != null, "The GIROpCode annotation is missing");
      GAssert.set(opCodeAnnot.value() != null,
                  "GIROpCode is assigned a nullptr");
      opCode = opCodeAnnot.value();
    }
    return opCode;
  }

  public Object[] getInputs() {
    List<Field> fields = Reflections.getAnnotatedFields(this, GIRInput.class);
    Object[] inputs = new Object[fields.size()];
    for (Field field : fields) {
      GIRInput annot = field.getAnnotation(GIRInput.class);
      GAssert.set(
          annot != null,
          "The GIRInput annotation is missing in the field %s".formatted(
              field.getName()));
      GAssert.set(annot.value() >= 0,
                  "GIRInput value must be non-negative, got %d".formatted(
                      annot.value()));
      GAssert.set(annot.value() < fields.size(),
                  "GIRInput value must be less than the number of inputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        GAssert.panic("Failed to access field %s: %s".formatted(
            field.getName(), e.getMessage()));
      }
      // Ensure the input is of the expected type
      GAssert.set(value instanceof Object,
                  "GIRInput field value must be an Object");
      // Add the value to the inputs list at the index specified by the
      // annotation
      GAssert.set(
          annot.value() < inputs.length,
          ("GIRInput(%d) is out of bounds for the inputs list of size %d. "
           + "This is perhaps your indices are not continuous.")
              .formatted(annot.value(), inputs.length));
      int index = annot.value();
      GAssert.set(inputs[index] != null,
                  "GIRInput has conflict indicies, got multiple GIRInput(%d)"
                      .formatted(index));
      inputs[index] = value;
    }
    return inputs;
  }

  public Object[] getOutputs() {
    List<Field> fields = Reflections.getAnnotatedFields(this, GIROutput.class);
    Object[] outputs = new Object[fields.size()];
    for (Field field : fields) {
      GIROutput annot = field.getAnnotation(GIROutput.class);
      GAssert.set(
          annot != null,
          "The GIROutput annotation is missing in the field %s".formatted(
              field.getName()));
      GAssert.set(annot.value() >= 0,
                  "GIROutput value must be non-negative, got %d".formatted(
                      annot.value()));
      GAssert.set(annot.value() < fields.size(),
                  "GIROutput value must be less than the number of inputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        GAssert.panic("Failed to access field %s: %s".formatted(
            field.getName(), e.getMessage()));
      }
      // Ensure the input is of the expected type
      GAssert.set(value instanceof Object,
                  "GIROutput field value must be an Object");
      // Add the value to the inputs list at the index specified by the
      // annotation
      GAssert.set(
          annot.value() < outputs.length,
          ("GIROutput(%d) is out of bounds for the inputs list of size %d. "
           + "This is perhaps your indices are not continuous.")
              .formatted(annot.value(), outputs.length));
      int index = annot.value();
      GAssert.set(
          outputs[index] != null,
          "GIROutput has conflict indicies, got multiple GIROutput(%d)"
              .formatted(index));
      outputs[index] = value;
    }
    return outputs;
  }

  /**
   * Accept a visitor for this instruction. This method enables the visitor
   * pattern by dispatching to the appropriate visit method based on the
   * runtime type of the instruction.
   *
   * @param <T> The return type of the visitor
   * @param visitor The visitor to accept
   * @return The result of the visitor's visit method
   */
  public abstract <T> T accept(GInstructionVisitor<T> visitor);
}

/// GIRInput represents an input of a GIR instruction
@Retention(RetentionPolicy.RUNTIME)
@interface GIRInput {
  int value(); // The index of the input in the instruction
}

/// GIRInput represents an output of a GIR instruction
@Retention(RetentionPolicy.RUNTIME)
@interface GIROutput {
  int value(); // The index of the output in the instruction
}

/// GIROpCode injects the opcode into the instruction
@Retention(RetentionPolicy.RUNTIME)
@interface GIROpCode {
  String value(); // The opcode of the instruction
}
