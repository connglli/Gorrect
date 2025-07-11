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

package io.connglli.gorrect.java;

import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.model.ObjectGraph;
import io.connglli.gorrect.utility.GAssert;
import io.connglli.gorrect.utility.Logger;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;

public class JavaInterpreter implements GInstructionVisitor<Void> {

  private static final Logger log = Logger.getLogger(JavaInterpreter.class);

  @Getter @NonNull private final GProgram program;

  @NonNull private final GeneratedHeap heap;

  public JavaInterpreter(@NonNull GProgram program) {
    this.program = program;
    this.heap = new GeneratedHeap();
  }

  public void interpret() {
    program.accept(this);
  }

  public ObjectGraph getState() {
    return heap.getState();
  }

  public String toJavaCode(String directory) {
    String file = heap.toJavaCode(directory);
    log.info("Java code generated: " + file);
    log.warning(
        "WARNING(JavaInterpreter.toJavaCode): The dump-heap operation has not been generated, the program has not been executed, only the instructions have been visited");
    return file;
  }

  public Void visit(GProgram program) {
    log.fine("--------------");
    log.fine(">> Executing: " + program.getConfiguration());
    program.getConfiguration().accept(this);
    log.fine("   State:");
    log.fine(heap.formatGraphStateToText().indent(5).stripTrailing());
    log.fine(heap.formatObjectStateToText().indent(5).stripTrailing());
    for (var instruction : program.getInstructions()) {
      log.fine("--------------");
      log.fine(">> Executing: " + instruction);
      instruction.accept(this);
      log.fine("   State:");
      log.fine(heap.formatGraphStateToText().indent(5).stripTrailing());
      log.fine(heap.formatObjectStateToText().indent(5).stripTrailing());
    }
    log.warning(
        "WARNING(JavaInterpreter.visit(GProgram)): The dump-heap operation has not been generated, the program has not been executed, only the instructions have been visited");
    return null;
  }

  public Void visit(GSetHeapInstruction instruction) {
    // TODO: Save HEAP configuration to use when running the generation program
    log.warning(
        "WARNING(JavaInterpreter.GSetHeapInstruction): The heap configurations (size, type, etc.) has not been written or saved");
    heap.initialize(instruction.getInitState());
    return null;
  }

  public Void visit(GAddRootObjInstruction instruction) {
    heap.allocRootWithoutInitializer(instruction.getObjectId(), instruction.getObjectSize());
    return null;
  }

  public Void visit(GAddNonRootObjInstruction instruction) {
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    heap.allocNonRootAndReference(
        instruction.getObjectId(),
        instruction.getObjectSize(),
        referer,
        instruction.getReferenceType(),
        instruction.getReferenceId());
    return null;
  }

  public Void visit(GMakeRootInstruction instruction) {
    heap.getObject(instruction.getObjectId()).toRoot();
    return null;
  }

  public Void visit(GMakeNonRootInstruction instruction) {
    heap.getObject(instruction.getObjectId()).toNonRoot();
    return null;
  }

  public Void visit(GCloneObjInstruction instruction) {
    GeneratedObject object = heap.getObject(instruction.getObjectId());

    try (GeneratedHeap.NonRootInPoolAllocator alloctor = heap.getNonRootInPoolAllocator()) {
      GeneratedObject clone = alloctor.alloc(instruction.getCloneId(), object.getSize());
      object
          .getReferers()
          .forEach(
              referer -> {
                GeneratedReference reference = heap.getReference(referer, object);
                referer.reference(
                    new GID(reference.getId() + "__cloned"), clone, reference.getType());
              });
      object
          .getReferents()
          .forEach(
              referent -> {
                GeneratedReference reference = heap.getReference(object, referent);
                clone.reference(
                    new GID(reference.getId() + "__cloned"), referent, reference.getType());
              });
    }

    return null;
  }

  public Void visit(GRemoveObjInstruction instruction) {
    heap.getObject(instruction.getObjectId()).free();
    return null;
  }

  public Void visit(GAddStrongRefInstruction instruction) {
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.strongReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddSoftRefInstruction instruction) {
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.softReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddWeakRefInstruction instruction) {
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.weakReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddFaninRefsInstruction instruction) {
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    List<GID> refererIds = instruction.getRefererIds();
    List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
    List<GID> referenceIds = instruction.getReferenceIds();
    GAssert.set(
        refererIds.size() == referenceTypes.size(),
        "The number of referers must match the number of reference types");
    GAssert.set(
        refererIds.size() == referenceIds.size(),
        "The number of referers must match the number of references");
    for (int i = 0; i < refererIds.size(); i++) {
      heap.getObject(refererIds.get(i))
          .reference(referenceIds.get(i), referent, referenceTypes.get(i));
    }
    return null;
  }

  public Void visit(GAddFanoutRefsInstruction instruction) {
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    List<GID> referentIds = instruction.getReferentIds();
    List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
    List<GID> referenceIds = instruction.getReferenceIds();
    GAssert.set(
        referentIds.size() == referenceTypes.size(),
        "The number of referers must match the number of reference types");
    GAssert.set(
        referentIds.size() == referenceIds.size(),
        "The number of referers must match the number of references");
    for (int i = 0; i < referentIds.size(); i++) {
      referer.reference(
          referenceIds.get(i), heap.getObject(referentIds.get(i)), referenceTypes.get(i));
    }
    return null;
  }

  public Void visit(GReplaceReferentInstruction instruction) {
    heap.getReference(instruction.getReferenceId())
        .redirect(heap.getObject(instruction.getNewReferentId()));
    return null;
  }

  public Void visit(GSwapReferentsInstruction instruction) {
    GAssert.set(
        !instruction.getReferenceId1().equals(instruction.getReferenceId2()),
        "Cannot swap the referents of the same reference");
    GeneratedReference ref1 = heap.getReference(instruction.getReferenceId1());
    GeneratedReference ref2 = heap.getReference(instruction.getReferenceId2());
    GAssert.set(
        ref1.getType() == ref2.getType(),
        "Cannot swap referents of different reference types: %s and %s",
        ref1.getType(),
        ref2.getType());
    GAssert.set(
        !ref1.getReferer().getId().equals(ref2.getReferer().getId()),
        "Reference %s and reference %s have the same referer %s",
        ref1.getId(),
        ref2.getId(),
        ref1.getReferer().getId());
    GAssert.set(
        !ref1.getReferent().getId().equals(ref2.getReferent().getId()),
        "Reference %s and reference %s have the same referent %s",
        ref1.getId(),
        ref2.getId(),
        ref1.getReferent().getId());
    // FIXME: Require a temporary object to mediate between the two references.
    // We can do this by adding two new pools for soft and weak references.
    log.warning(
        "WARNING(JavaInterpreter.visit(GSwapReferentsInstruction): Require a temporary object to mediate between the two references.");
    ref1.redirect(ref2.getReferent());
    ref2.redirect(ref1.getReferent());
    return null;
  }

  public Void visit(GSoftenRefInstruction instruction) {
    heap.getReference(instruction.getReferenceId()).soften();
    return null;
  }

  public Void visit(GStrengthRefInstruction instruction) {
    heap.getReference(instruction.getReferenceId()).strengthen();
    return null;
  }

  public Void visit(GRemoveRefInstruction instruction) {
    heap.getReference(instruction.getReferenceId()).unlink();
    return null;
  }

  public Void visit(GRemoveAllReferentsInstruction instruction) {
    heap.getObject(instruction.getRefererId()).getReferences().forEach(GeneratedReference::unlink);
    return null;
  }
}
