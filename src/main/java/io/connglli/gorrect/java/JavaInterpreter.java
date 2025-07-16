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

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.GProgramVisitor;
import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;

public class JavaInterpreter implements GProgramVisitor<Void> {

  private static final Logger log = Logger.getLogger(JavaInterpreter.class);

  @Getter @NonNull private final GProgram program;

  @NonNull private final GeneratedHeap heap;

  public JavaInterpreter(@NonNull GProgram program) {
    this(program, true);
  }

  public JavaInterpreter(@NonNull GProgram program, boolean strict) {
    this.program = program;
    this.heap = new GeneratedHeap(strict);
  }

  public void interpret() {
    program.accept(this);
    heap.clean();
  }

  public GGraph getState() {
    return heap.getState();
  }

  public String toJavaCode(String directory) {
    String file = heap.toJavaCode(directory);
    log.info("Java code generated: " + file);
    return file;
  }

  public Void visit(GProgram program) {
    log.fine("--------------");
    log.fine(">> Executing: " + program.getConfiguration());
    heap.appendComment(program.getConfiguration().toString());
    heap.appendComment("Initial State:");
    heap.appendComment(program.getConfiguration().getInitState().formatToText());
    program.getConfiguration().accept(this);
    log.fine("   State:");
    log.fine(heap.formatGraphStateToText().indent(5).stripTrailing());
    log.fine(heap.formatObjectStateToText().indent(5).stripTrailing());
    for (var instruction : program.getInstructions()) {
      log.fine("--------------");
      log.fine(">> Executing: " + instruction);
      heap.appendComment(instruction.toString());
      instruction.accept(this);
      log.fine("   State:");
      log.fine(heap.formatGraphStateToText().indent(5).stripTrailing());
      log.fine(heap.formatObjectStateToText().indent(5).stripTrailing());
    }
    heap.insertDumpHeapHook();
    return null;
  }

  public Void visit(GSetHeapInstruction instruction) {
    instruction.checkValidity(getState());
    // TODO: Save HEAP configuration to use when running the generation program
    log.warning(
        "WARNING(JavaInterpreter.GSetHeapInstruction): The heap configurations (size, type, etc.) has not been written or saved");
    heap.initialize(instruction.getInitState());
    return null;
  }

  public Void visit(GAddRootObjInstruction instruction) {
    instruction.checkValidity(getState());
    heap.allocRootWithoutInitializer(instruction.getObjectId(), instruction.getObjectSize());
    return null;
  }

  public Void visit(GAddNonRootObjInstruction instruction) {
    instruction.checkValidity(getState());
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
    instruction.checkValidity(getState());
    heap.getObject(instruction.getObjectId()).toRoot();
    return null;
  }

  public Void visit(GMakeNonRootInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getObject(instruction.getObjectId()).toNonRoot();
    heap.clean();
    return null;
  }

  public Void visit(GCloneObjInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject object = heap.getObject(instruction.getObjectId());

    try (GeneratedHeap.NonRootInPoolAllocator alloctor = heap.getNonRootInPoolAllocator()) {
      GeneratedObject clone = alloctor.alloc(instruction.getCloneId(), object.getSize());
      object
          .getReferers()
          .forEach(
              referer -> {
                GeneratedReference reference = heap.getReference(referer, object);
                referer.reference(
                    new GID(reference.getId() + instruction.getReferenceSuffix()),
                    clone,
                    reference.getType());
              });
      object
          .getReferents()
          .forEach(
              referent -> {
                GeneratedReference reference = heap.getReference(object, referent);
                clone.reference(
                    new GID(reference.getId() + instruction.getReferenceSuffix()),
                    referent,
                    reference.getType());
              });
    }

    return null;
  }

  public Void visit(GRemoveObjInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getObject(instruction.getObjectId()).free();
    heap.clean();
    return null;
  }

  public Void visit(GAddStrongRefInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.strongReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddSoftRefInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.softReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddWeakRefInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    referer.weakReference(instruction.getReferenceId(), referent);
    return null;
  }

  public Void visit(GAddFaninRefsInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject referent = heap.getObject(instruction.getReferentId());
    List<GID> refererIds = instruction.getRefererIds();
    List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
    List<GID> referenceIds = instruction.getReferenceIds();
    Assert.set(
        refererIds.size() == referenceTypes.size(),
        "The number of referers must match the number of reference types");
    Assert.set(
        refererIds.size() == referenceIds.size(),
        "The number of referers must match the number of references");
    for (int i = 0; i < refererIds.size(); i++) {
      heap.getObject(refererIds.get(i))
          .reference(referenceIds.get(i), referent, referenceTypes.get(i));
    }
    return null;
  }

  public Void visit(GAddFanoutRefsInstruction instruction) {
    instruction.checkValidity(getState());
    GeneratedObject referer = heap.getObject(instruction.getRefererId());
    List<GID> referentIds = instruction.getReferentIds();
    List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
    List<GID> referenceIds = instruction.getReferenceIds();
    Assert.set(
        referentIds.size() == referenceTypes.size(),
        "The number of referers must match the number of reference types");
    Assert.set(
        referentIds.size() == referenceIds.size(),
        "The number of referers must match the number of references");
    for (int i = 0; i < referentIds.size(); i++) {
      referer.reference(
          referenceIds.get(i), heap.getObject(referentIds.get(i)), referenceTypes.get(i));
    }
    return null;
  }

  public Void visit(GReplaceReferentInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getReference(instruction.getReferenceId())
        .redirect(heap.getObject(instruction.getNewReferentId()));
    heap.clean();
    return null;
  }

  public Void visit(GSwapReferentsInstruction instruction) {
    instruction.checkValidity(getState());
    Assert.set(
        !instruction.getReferenceId1().equals(instruction.getReferenceId2()),
        "Cannot swap the referents of the same reference");
    GeneratedReference ref1 = heap.getReference(instruction.getReferenceId1());
    GeneratedReference ref2 = heap.getReference(instruction.getReferenceId2());
    Assert.set(
        ref1.getType() == ref2.getType(),
        "Cannot swap referents of different reference types: %s and %s",
        ref1.getType(),
        ref2.getType());
    Assert.set(
        !ref1.getReferer().getId().equals(ref2.getReferer().getId()),
        "Reference %s and reference %s have the same referer %s",
        ref1.getId(),
        ref2.getId(),
        ref1.getReferer().getId());
    Assert.set(
        !ref1.getReferent().getId().equals(ref2.getReferent().getId()),
        "Reference %s and reference %s have the same referent %s",
        ref1.getId(),
        ref2.getId(),
        ref1.getReferent().getId());
    GeneratedObject ref1Referent = ref1.getReferent();
    boolean isRef1Root = ref1Referent.isRoot();
    if (!isRef1Root) {
      heap.makeRoot(ref1Referent); // Temporarily make the referent a root object to avoid GC
    }
    ref1.redirect(ref2.getReferent());
    ref2.redirect(ref1Referent);
    if (!isRef1Root) {
      heap.makeNonRoot(ref1Referent); // Remove it from the root set
    }
    return null;
  }

  public Void visit(GSoftenRefInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getReference(instruction.getReferenceId()).soften();
    return null;
  }

  public Void visit(GStrengthRefInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getReference(instruction.getReferenceId()).strengthen();
    return null;
  }

  public Void visit(GRemoveRefInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getReference(instruction.getReferenceId()).unlink();
    heap.clean();
    return null;
  }

  public Void visit(GRemoveAllReferentsInstruction instruction) {
    instruction.checkValidity(getState());
    heap.getObject(instruction.getRefererId()).getReferences().forEach(GeneratedReference::unlink);
    heap.clean();
    return null;
  }
}
