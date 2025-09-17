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
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Formats;
import io.connglli.gorrect.utility.Reflections;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

/**
 * GInstruction represents an instruction of the Gorrect IR (GIR). All instructions of GIR operate
 * closely over an object graph (and nothing else), specifically on its objects and references.
 *
 * <p>Each instruction has an opcode, which is defined by the {@link GIROpCode} annotation.
 *
 * <p>The inputs and outputs of the instruction are annotated with {@link GIRInput} and {@link
 * GIROutput}, respectively.
 *
 * <p>The interpreter of a {@link io.connglli.gorrect.gir.GProgram} can assume limited or unlimited
 * heap memory. For unlimited memory, each instruction will definitely success as long as the
 * inputs/outputs of the instruction are valid. If the instruction is not valid, an {@link
 * InvalidInputException} or {@link InvalidOutputException} will be thrown. For limited memory, some
 * instruction may fail due to for example weakly/softly reachable objects are garbage collected. In
 * these cases, an exception will be thrown.
 */
public abstract class GInstruction {

  public static class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
      super(message);
    }
  }

  public static class InvalidOutputException extends RuntimeException {
    public InvalidOutputException(String message) {
      super(message);
    }
  }

  // The index of the instruction in the program.
  // The index should be set by the program when the instruction is added.
  // Note that:
  // - The index is -2 for an instruction that is not added to any program.
  // - The index is -1 for the configuration instruction.
  // - The non-negative index is for normal instructions, starting from 0.
  @Getter @Setter private int index = -2;

  // The opcode of the instruction, computed at runtime by @GIROpCode
  private String opCode = null;

  /**
   * Returns the opcode of the instruction. The opcode is defined by the {@link GIROpCode}
   * annotation on the class.
   *
   * @return The opcode of the instruction
   */
  public String getOpCode() {
    if (opCode == null) {
      GIROpCode opCodeAnnot = getClass().getAnnotation(GIROpCode.class);
      Assert.set(opCodeAnnot != null, "The GIROpCode annotation is missing");
      Assert.set(opCodeAnnot.value() != null, "GIROpCode is assigned a nullptr");
      opCode = opCodeAnnot.value();
    }
    return opCode;
  }

  /**
   * Returns a map of input names to their values. The inputs are annotated with {@link GIRInput}.
   * The keys in the map are the snake case names of the fields, and the values are the field
   * values. The map is ordered by the indices specified in the {@link GIRInput}.
   *
   * @return A map of input names to their values
   */
  public Map<String, Object> getInputMap() {
    Field[] fields = Reflections.getAnnotatedFields(this, GIRInput.class);
    String[] names = new String[fields.length];
    Object[] inputs = new Object[fields.length];
    for (Field field : fields) {
      GIRInput annot = field.getAnnotation(GIRInput.class);
      Assert.set(
          annot != null, "The GIRInput annotation is missing in the field %s", field.getName());
      Assert.set(annot.value() >= 0, "GIRInput value must be non-negative, got %d", annot.value());
      Assert.set(
          annot.value() < inputs.length, "GIRInput value must be less than the number of inputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        Assert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
      // Ensure the input is of the expected type
      Assert.set(value != null, "GIRInput field value must be non-null");
      // Add the value to the inputs list at the index specified by the
      // annotation
      Assert.set(
          annot.value() < inputs.length,
          ("GIRInput(%d) is out of bounds for the inputs list of size %d. "
              + "This is perhaps your indices are not continuous."),
          annot.value(),
          inputs.length);
      int index = annot.value();
      Assert.set(
          inputs[index] == null, "GIRInput has conflict indices, got multiple GIRInput(%d)", index);
      inputs[index] = value;
      names[index] = Formats.toSnakeCase(field.getName());
    }
    Map<String, Object> inputMap = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) {
      Assert.set(
          inputs[i] != null && names[i] != null,
          "GIRInput fields are not continuous, GIRInput(%d) is missing",
          i);
      inputMap.put(names[i], inputs[i]);
    }
    return inputMap;
  }

  /**
   * Returns a map of output names to their values. The outputs are annotated with {@link
   * GIROutput}. The keys in the map are the snake case names of the fields, and the values are the
   * field values. The map is ordered by the indices specified in the {@link GIROutput}.
   *
   * @return A map of output names to their values
   */
  public Map<String, Object> getOutputMap() {
    Field[] fields = Reflections.getAnnotatedFields(this, GIROutput.class);
    String[] names = new String[fields.length];
    Object[] outputs = new Object[fields.length];
    for (Field field : fields) {
      GIROutput annot = field.getAnnotation(GIROutput.class);
      Assert.set(
          annot != null, "The GIROutput annotation is missing in the field %s", field.getName());
      Assert.set(annot.value() >= 0, "GIROutput value must be non-negative, got %d", annot.value());
      Assert.set(
          annot.value() < outputs.length,
          "GIROutput value must be less than the number of outputs");
      field.setAccessible(true); // Make private fields accessible
      Object value = null;
      try {
        value = field.get(this);
      } catch (IllegalAccessException e) {
        Assert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
      // Ensure the output is of the expected type
      Assert.set(value != null, "GIROutput field value must be non-null");
      // Add the value to the outputs list at the index specified by the
      // annotation
      Assert.set(
          annot.value() < outputs.length,
          ("GIROutput(%d) is out of bounds for the outputs list of size %d. "
              + "This is perhaps your indices are not continuous."),
          annot.value(),
          outputs.length);
      int index = annot.value();
      Assert.set(
          outputs[index] == null,
          "GIROutput has conflict indices, got multiple GIROutput(%d)",
          index);
      outputs[index] = value;
      names[index] = Formats.toSnakeCase(field.getName());
    }
    Map<String, Object> outputMap = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) {
      Assert.set(
          outputs[i] != null && names[i] != null,
          "GIROutput fields are not continuous, GIROutput(%d) is missing",
          i);
      outputMap.put(names[i], outputs[i]);
    }
    return outputMap;
  }

  /**
   * Returns the inputs of the instruction as an array. The inputs are ordered by the indices
   * specified in the {@link GIRInput} annotations.
   *
   * @return An array of input values
   */
  public Object[] getInputs() {
    return getInputMap().values().toArray();
  }

  /**
   * Returns the outputs of the instruction as an array. The outputs are ordered by the indices
   * specified in the {@link GIROutput} annotations.
   *
   * @return An array of output values
   */
  public Object[] getOutputs() {
    return getOutputMap().values().toArray();
  }

  /**
   * Check the validity of the instruction on the given graph. This method should be called before
   * the instruction is executed to ensure that the inputs and outputs are valid.
   *
   * @param graph The graph on which the instruction is executed
   */
  public void checkValidity(GGraph graph) {
    if (index < -1) {
      throw new InvalidInputException(
          "Instruction index must be set immediately after being added into a program. (instruction: "
              + this
              + ")");
    }

    Set<GID> reachableObjects = graph.getReachableObjects();

    // Check all objects marked with @GIRReachable are indeed reachable
    for (Field field : Reflections.getAnnotatedFields(this, GIRReachable.class)) {
      field.setAccessible(true); // Make private fields accessible
      String fieldName = field.getName();
      try {
        Object value = field.get(this);
        if (value instanceof GID gid) {
          if (!reachableObjects.contains(gid)) {
            throw new InvalidInputException(
                "Object %s is not reachable (field: %s, instruction: %s)"
                    .formatted(gid, fieldName, this));
          }
        } else if (value instanceof Collection<?> gids) {
          for (Object gid : gids) {
            Assert.set(
                gid instanceof GID,
                "@GIRReachable field %s is not of type Collection<? extends GID>, got %s for value %s (instruction: %s)",
                fieldName,
                gid.getClass().getName(),
                gid,
                this);
            if (!reachableObjects.contains((GID) gid)) {
              throw new InvalidInputException(
                  "Object %s is not reachable (field: %s, instruction: %s)"
                      .formatted(gid, fieldName, this));
            }
          }
        } else {
          Assert.panic(
              "@GIRReachable can only be applied to GID or Collection<? extends GID>, got %s (field: %s, instruction: %s)",
              value.getClass().getName(), fieldName, this);
        }
      } catch (IllegalAccessException e) {
        Assert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
    }

    // Check all inputs marked with @GIRNotExist do (not) exist
    for (Field field : Reflections.getAnnotatedFields(this, GIRNotExist.class)) {
      field.setAccessible(true); // Make private fields accessible
      String fieldName = field.getName();
      try {
        Object value = field.get(this);
        GIRNotExist annot = field.getAnnotation(GIRNotExist.class);
        Assert.set(
            annot != null, "The @GIRNotExist annotation is missing in the field %s", fieldName);
        if (annot.value() == GIRNotExist.Type.OBJECT) {
          if (value instanceof GID gid) {
            if (annot.reverse() && !graph.hasObject(gid)) {
              throw new InvalidInputException(
                  "Object %s does not exist (field: %s, instruction: %s)"
                      .formatted(gid, fieldName, this));
            } else if (!annot.reverse() && graph.hasObject(gid)) {
              throw new InvalidInputException(
                  "Object %s already exists (field: %s, instruction: %s)"
                      .formatted(gid, fieldName, this));
            }
          } else if (value instanceof Collection<?> gids) {
            for (Object gid : gids) {
              Assert.set(
                  gid instanceof GID,
                  "@GIRNotExist field %s is not of type Collection<? extends GID>, got %s for value %s (instruction: %s)",
                  fieldName,
                  gid.getClass().getName(),
                  gid,
                  this);
              if (annot.reverse() && !graph.hasObject((GID) gid)) {
                throw new InvalidInputException(
                    "Object %s does not exist (field: %s, instruction: %s)"
                        .formatted(gid, fieldName, this));
              } else if (!annot.reverse() && graph.hasObject((GID) gid)) {
                throw new InvalidInputException(
                    "Object %s already exists (field: %s, instruction: %s)"
                        .formatted(gid, fieldName, this));
              }
            }
          } else {
            Assert.panic(
                "@GIRNotExist can only be applied to GID or Collection<? extends GID>, got %s (field: %s, instruction: %s)",
                value.getClass().getName(), fieldName, this);
          }
        } else if (annot.value() == GIRNotExist.Type.REFERENCE) {
          if (value instanceof GID gid) {
            if (annot.reverse() && !graph.hasReference(gid)) {
              throw new InvalidInputException(
                  "Reference %s does not exist (field: %s, instruction: %s)"
                      .formatted(gid, fieldName, this));
            } else if (!annot.reverse() && graph.hasReference(gid)) {
              throw new InvalidInputException(
                  "Reference %s already exists (field: %s, instruction: %s)"
                      .formatted(gid, fieldName, this));
            }
          } else if (value instanceof Collection<?> gids) {
            for (Object gid : gids) {
              Assert.set(
                  gid instanceof GID,
                  "@GIRNotExist field %s is not of type Collection<? extends GID>, got %s for value %s (instruction: %s)",
                  fieldName,
                  gid.getClass().getName(),
                  gid,
                  this);
              if (annot.reverse() && !graph.hasReference((GID) gid)) {
                throw new InvalidInputException(
                    "Reference %s does not exist (field: %s, instruction: %s)"
                        .formatted(gid, fieldName, this));
              } else if (!annot.reverse() && graph.hasReference((GID) gid)) {
                throw new InvalidInputException(
                    "Reference %s already exists (field: %s, instruction: %s)"
                        .formatted(gid, fieldName, this));
              }
            }
          } else {
            Assert.panic(
                "@GIRNotExist can only be applied to GID or Collection<? extends GID>, got %s (field: %s, instruction: %s)",
                value.getClass().getName(), fieldName, this);
          }
        } else {
          Assert.panic("Unknown @GIRNotExist.Type: %s", annot.value());
        }

      } catch (IllegalAccessException e) {
        Assert.panic("Failed to access field %s: %s", field.getName(), e.getMessage());
      }
    }
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

/// GIRReachable indicates that some objects (a {@link io.connglli.gorrect.gir.model.GID} or a list
/// of {@link io.connglli.gorrect.gir.model.GID}) must be reachable
@Retention(RetentionPolicy.RUNTIME)
@interface GIRReachable {}

/// GIRNotExist indicates that some input (a {@link io.connglli.gorrect.gir.model.GID} or a list
/// of {@link io.connglli.gorrect.gir.model.GID}) cannot exist
@Retention(RetentionPolicy.RUNTIME)
@interface GIRNotExist {
  enum Type {
    OBJECT, // The object cannot be a root object
    REFERENCE // The object cannot be a non-root object
  }

  Type value(); // The type of the input that cannot exist

  boolean reverse() default false; // If true, the input must exist, otherwise it must not exist.
}
