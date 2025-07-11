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
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
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

import io.connglli.gorrect.gir.GProgramVisitor;
import io.connglli.gorrect.utility.Formats;
import io.connglli.gorrect.utility.GAssert;
import io.connglli.gorrect.utility.Reflections;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GInstruction represents an instruction of the Gorrect IR (GIR). All instructions of GIR operate
 * closely over an object graph (and nothing else) assuming unlimited heap memory, specifically the
 * operations are on objects and references in the graph.
 *
 * <p>Each instruction has an opcode, which is defined by the {@link GIROpCode} annotation.
 *
 * <p>The inputs and outputs of the instruction are annotated with {@link GIRInput} and {@link
 * GIROutput}, respectively.
 */
public abstract class GInstruction {
  private String opCode;

  public String getOpCode() {
    if (opCode == null) {
      GIROpCode opCodeAnnot = getClass().getAnnotation(GIROpCode.class);
      GAssert.set(opCodeAnnot != null, "The GIROpCode annotation is missing");
      GAssert.set(opCodeAnnot.value() != null, "GIROpCode is assigned a nullptr");
      opCode = opCodeAnnot.value();
    }
    return opCode;
  }

  public Map<String, Object> getInputMap() {
    Field[] fields = Reflections.getAnnotatedFields(this, GIRInput.class);
    String[] names = new String[fields.length];
    Object[] inputs = new Object[fields.length];
    for (Field field : fields) {
      GIRInput annot = field.getAnnotation(GIRInput.class);
      GAssert.set(
          annot != null, "The GIRInput annotation is missing in the field %s", field.getName());
      GAssert.set(annot.value() >= 0, "GIRInput value must be non-negative, got %d", annot.value());
      GAssert.set(
          annot.value() < inputs.length, "GIRInput value must be less than the number of inputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        GAssert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
      // Ensure the input is of the expected type
      GAssert.set(value != null, "GIRInput field value must be non-null");
      // Add the value to the inputs list at the index specified by the
      // annotation
      GAssert.set(
          annot.value() < inputs.length,
          ("GIRInput(%d) is out of bounds for the inputs list of size %d. "
              + "This is perhaps your indices are not continuous."),
          annot.value(),
          inputs.length);
      int index = annot.value();
      GAssert.set(
          inputs[index] == null, "GIRInput has conflict indices, got multiple GIRInput(%d)", index);
      inputs[index] = value;
      names[index] = Formats.toSnakeCase(field.getName());
    }
    Map<String, Object> inputMap = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) {
      GAssert.set(
          inputs[i] != null && names[i] != null,
          "GIRInput fields are not continuous, GIRInput(%d) is missing",
          i);
      inputMap.put(names[i], inputs[i]);
    }
    return inputMap;
  }

  public Map<String, Object> getOutputMap() {
    Field[] fields = Reflections.getAnnotatedFields(this, GIROutput.class);
    String[] names = new String[fields.length];
    Object[] outputs = new Object[fields.length];
    for (Field field : fields) {
      GIROutput annot = field.getAnnotation(GIROutput.class);
      GAssert.set(
          annot != null, "The GIROutput annotation is missing in the field %s", field.getName());
      GAssert.set(
          annot.value() >= 0, "GIROutput value must be non-negative, got %d", annot.value());
      GAssert.set(
          annot.value() < outputs.length,
          "GIROutput value must be less than the number of outputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        GAssert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
      // Ensure the output is of the expected type
      GAssert.set(value != null, "GIROutput field value must be non-null");
      // Add the value to the outputs list at the index specified by the
      // annotation
      GAssert.set(
          annot.value() < outputs.length,
          ("GIROutput(%d) is out of bounds for the outputs list of size %d. "
              + "This is perhaps your indices are not continuous."),
          annot.value(),
          outputs.length);
      int index = annot.value();
      GAssert.set(
          outputs[index] == null,
          "GIROutput has conflict indices, got multiple GIROutput(%d)",
          index);
      outputs[index] = value;
      names[index] = Formats.toSnakeCase(field.getName());
    }
    Map<String, Object> outputMap = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) {
      GAssert.set(
          outputs[i] != null && names[i] != null,
          "GIROutput fields are not continuous, GIROutput(%d) is missing",
          i);
      outputMap.put(names[i], outputs[i]);
    }
    return outputMap;
  }

  public Object[] getInputs() {
    return getInputMap().values().toArray();
  }

  public Object[] getOutputs() {
    return getOutputMap().values().toArray();
  }

  /**
   * Accept a visitor for this instruction. This method enables the visitor pattern by dispatching
   * to the appropriate visit method based on the runtime type of the instruction.
   *
   * @param <T> The return type of the visitor
   * @param visitor The visitor to accept
   * @return The result of the visitor's visit method
   */
  public abstract <T> T accept(GProgramVisitor<T> visitor);

  /**
   * Returns a string representation of the instruction, which includes the opcode and the inputs
   * and outputs.
   *
   * @return A string representation of the instruction
   */
  public String toString() {
    Map<String, Object> inputs = getInputMap();
    Map<String, Object> outputs = getOutputMap();
    if (!outputs.isEmpty()) {
      return String.format(
          "%s <- %s %s",
          outputs.values().stream().map(Object::toString).collect(Collectors.joining(", ")),
          getOpCode(),
          inputs.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(", ")));
    } else {
      return String.format(
          "_ <- %s %s",
          getOpCode(),
          inputs.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(", ")));
    }
  }
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
