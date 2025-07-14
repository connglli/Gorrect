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

package io.connglli.gorrect.oracle;

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.GProgramVisitor;
import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.utility.Assert;
import java.util.List;
import lombok.NonNull;

/**
 * The OracleInterpreter is a GIR program {@link GProgram} interpreter. Different from GIR's, the
 * interpreter should carefully handle the size of the heap. Therefore, it wraps object graph into
 * {@link OracleHeap} and uses it to manage the heap memory. Indeed, methods provided by the {@link
 * OracleHeap} are similar to those in GIR, but they are more memory-aware and more convenient. The
 * interpreter is used to execute a GIR program and simulate the change of the {@link GGraph}.
 */
public class OracleInterpreter implements GProgramVisitor<Void> {

  @SuppressWarnings("FieldCanBeLocal")
  @NonNull
  private final LangVM lvm;

  @NonNull private final GProgram program;

  @NonNull private final OracleHeap heap;

  public OracleInterpreter(@NonNull LangVM lvm, @NonNull GProgram program) {
    this.lvm = lvm;
    this.program = program;
    this.heap = new OracleHeap(lvm);
  }

  public void interpret() {
    program.accept(this);
    heap.clean();
  }

  public void interpret(GInstruction... instructions) {
    for (GInstruction instruction : instructions) {
      instruction.accept(this);
    }
    heap.clean();
  }

  public GGraph getState() {
    return heap.getGraph();
  }

  @Override
  public Void visit(GProgram program) {
    program.getConfiguration().accept(this);
    for (GInstruction instruction : program.getInstructions()) {
      instruction.accept(this);
    }
    return null;
  }

  @Override
  public Void visit(GSetHeapInstruction instruction) {
    heap.initialize(instruction.getInitState());
    return null;
  }

  @Override
  public Void visit(GAddRootObjInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.allocRootObject(instruction.getObjectId(), instruction.getObjectSize());
    return null;
  }

  @Override
  public Void visit(GAddNonRootObjInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    OracleObject referentObject =
        heap.allocNonRootObject(instruction.getObjectId(), instruction.getObjectSize());
    OracleObject refererObject = heap.getObject(instruction.getRefererId());
    refererObject.reference(
        instruction.getReferenceId(), referentObject, instruction.getReferenceType());
    return null;
  }

  @Override
  public Void visit(GMakeRootInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getNonRootObject(instruction.getObjectId()).toRoot();
    return null;
  }

  @Override
  public Void visit(GMakeNonRootInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getRootObject(instruction.getObjectId()).toNonRoot();
    heap.clean();
    return null;
  }

  @Override
  public Void visit(GCloneObjInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    OracleObject object = heap.getObject(instruction.getObjectId());
    OracleObject clone =
        heap.allocateObject(instruction.getCloneId(), object.getSize(), object.getType());
    object
        .getReferents()
        .forEach(
            referent -> {
              OracleReference reference = heap.getReference(object, referent);
              clone.reference(
                  new GID(reference.getId() + instruction.getReferenceSuffix()),
                  referent,
                  reference.getType());
            });
    object
        .getReferers()
        .forEach(
            referer -> {
              OracleReference reference = heap.getReference(referer, object);
              referer.reference(
                  new GID(reference.getId() + instruction.getReferenceSuffix()),
                  clone,
                  reference.getType());
            });
    return null;
  }

  @Override
  public Void visit(GRemoveObjInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getObject(instruction.getObjectId()).free();
    heap.clean();
    return null;
  }

  @Override
  public Void visit(GAddStrongRefInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getObject(instruction.getRefererId())
        .strongReference(instruction.getReferenceId(), heap.getObject(instruction.getReferentId()));
    return null;
  }

  @Override
  public Void visit(GAddSoftRefInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getObject(instruction.getRefererId())
        .softReference(instruction.getReferenceId(), heap.getObject(instruction.getReferentId()));
    return null;
  }

  @Override
  public Void visit(GAddWeakRefInstruction instruction) {
    heap.getObject(instruction.getRefererId())
        .weakReference(instruction.getReferenceId(), heap.getObject(instruction.getReferentId()));
    return null;
  }

  @Override
  public Void visit(GAddFaninRefsInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    OracleObject referent = heap.getObject(instruction.getReferentId());
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

  @Override
  public Void visit(GAddFanoutRefsInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    OracleObject referer = heap.getObject(instruction.getRefererId());
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

  @Override
  public Void visit(GStrengthRefInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getReference(instruction.getReferenceId()).strengthen();
    return null;
  }

  @Override
  public Void visit(GSoftenRefInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getReference(instruction.getReferenceId()).soften();
    return null;
  }

  @Override
  public Void visit(GReplaceReferentInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getReference(instruction.getReferenceId())
        .redirect(heap.getObject(instruction.getNewReferentId()));
    heap.clean();
    return null;
  }

  @Override
  public Void visit(GSwapReferentsInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    Assert.set(
        !instruction.getReferenceId1().equals(instruction.getReferenceId2()),
        "Cannot swap the referents of the same reference");
    OracleReference ref1 = heap.getReference(instruction.getReferenceId1());
    OracleReference ref2 = heap.getReference(instruction.getReferenceId2());
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
    ref1.redirect(ref2.getReferent());
    ref2.redirect(ref1.getReferent());
    return null;
  }

  @Override
  public Void visit(GRemoveRefInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getReference(instruction.getReferenceId()).unlink();
    heap.clean();
    return null;
  }

  @Override
  public Void visit(GRemoveAllReferentsInstruction instruction) {
    instruction.checkValidity(heap.getGraph());
    heap.getObject(instruction.getRefererId()).getReferences().forEach(OracleReference::unlink);
    heap.clean();
    return null;
  }
}
