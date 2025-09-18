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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NonNull;

/**
 * The OracleInterpreter is a GIR program {@link GProgram} interpreter. Different from GIR's, the
 * interpreter should carefully handle the size of the heap. Therefore, it wraps object graph into
 * {@link OracleHeap} and uses it to manage the heap memory. Indeed, methods provided by the {@link
 * OracleHeap} are similar to those in GIR, but they are more memory-aware and more convenient. The
 * interpreter is used to execute a GIR program and simulate the change of the {@link GGraph}.
 */
@SuppressWarnings("FieldCanBeLocal")
public class OracleInterpreter {

  @NonNull private final OracleHeap heap;
  @NonNull private final Impl impl;

  public OracleInterpreter(@NonNull LangVM lvm) {
    this.heap = new OracleHeap(lvm);
    this.impl = new Impl();
  }

  public LangVM getLangVM() {
    return heap.getLvm();
  }

  public void interpret(@NonNull GProgram program) {
    this.impl.interpret(program);
  }

  public void interpret(@NonNull GInstruction instruction) {
    this.impl.interpret(instruction);
  }

  public @NonNull GGraph getState() {
    return heap.getState();
  }

  /**
   * AnonymousID represents an anonymous ID that is guaranteed to be unique within the program,
   * defined based on instruction index. See GAddAnonymRootInstruction.
   */
  public static class AnonymousID extends GID {

    @Getter private final int index;
    @Getter private final int subindex;

    AnonymousID(int index, int subindex) {
      super("_anonym_id_" + index + "_x_" + subindex);
      Assert.set(index >= 0, "The index of an anonymous ID must be non-negative, got " + index);
      Assert.set(
          subindex >= 0, "The subindex of an anonymous ID must be non-negative, got " + subindex);
      this.index = index;
      this.subindex = subindex;
    }
  }

  public static @NonNull GID computeAnonymousId(@NonNull GInstruction instruction) {
    return computeAnonymousId(instruction.getIndex());
  }

  public static @NonNull GID computeAnonymousId(@NonNull GInstruction instruction, int subindex) {
    return computeAnonymousId(instruction.getIndex(), subindex);
  }

  public static @NonNull GID computeAnonymousId(int index) {
    return new AnonymousID(index, 0);
  }

  public static @NonNull GID computeAnonymousId(int index, int subindex) {
    return new AnonymousID(index, subindex);
  }

  protected class Impl implements GProgramVisitor<Void> {
    private final Map<GID, Integer> frozenObjects = new HashMap<>();

    public void interpret(@NonNull GProgram program) {
      program.accept(this);
      heap.clean();
    }

    public void interpret(@NonNull GInstruction instruction) {
      instruction.accept(this);
      updateFrozonObjects();
    }

    public void updateFrozonObjects() {
      List<GID> toUnfreeze = new ArrayList<>();
      for (Map.Entry<GID, Integer> kv : frozenObjects.entrySet()) {
        if (kv.getValue() < 0) {
          toUnfreeze.add(kv.getKey());
        } else {
          kv.setValue(kv.getValue() - 1);
        }
      }
      for (GID id : toUnfreeze) {
        frozenObjects.remove(id);
      }
    }

    @Override
    public Void visit(GProgram program) {
      interpret(program.getConfiguration());
      for (GInstruction instruction : program.getInstructions()) {
        interpret(instruction);
      }
      return null;
    }

    @Override
    public Void visit(GSetHeapInstruction instruction) {
      heap.initialize(instruction.getInitState());
      return null;
    }

    @Override
    public Void visit(GForceAnyGCInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      heap.compact();
      return null;
    }

    @Override
    public Void visit(GRunOutOfMemoryInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      // An OOM does not add new objects to our heap, merely triggers
      // a GC to remove all soft references should be cleared.
      heap.stressCompact();
      return null;
    }

    @Override
    public Void visit(GFragmentHeapInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      List<Long> objectSizes = instruction.getObjectSizes();
      List<GID> objectIDs = new ArrayList<>(objectSizes.size());
      for (int i = 0; i < instruction.getCount(); i++) {
        objectIDs.add(computeAnonymousId(instruction, i));
      }
      for (int i = 0; i < instruction.getCount(); i++) {
        heap.allocRootObject(objectIDs.get(i), objectSizes.get(i));
      }
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
          instruction.getReferenceId(),
          referentObject,
          instruction.getReferenceType(),
          instruction.getIsVolatile());
      return null;
    }

    @Override
    public Void visit(GAddAnonymRootInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      heap.allocRootObject(
          OracleInterpreter.computeAnonymousId(instruction), instruction.getObjectSize());
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
    public Void visit(GFreezeObjInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      frozenObjects.put(instruction.getObjectId(), instruction.getNStep());
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
                    reference.getType(),
                    reference.isVolatile());
              });
      object
          .getReferers()
          .forEach(
              referer -> {
                OracleReference reference = heap.getReference(referer, object);
                referer.reference(
                    new GID(reference.getId() + instruction.getReferenceSuffix()),
                    clone,
                    reference.getType(),
                    reference.isVolatile());
              });
      return null;
    }

    @Override
    public Void visit(GRemoveObjInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      if (frozenObjects.containsKey(instruction.getObjectId())) {
        throw new IllegalStateException(
            "Trying to remove a frozen object with id="
                + instruction.getObjectId()
                + ", steps="
                + frozenObjects.get(instruction.getObjectId()));
      }
      heap.getObject(instruction.getObjectId()).free();
      heap.clean();
      return null;
    }

    @Override
    public Void visit(GAddStrongRefInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      heap.getObject(instruction.getRefererId())
          .strongReference(
              instruction.getReferenceId(),
              heap.getObject(instruction.getReferentId()),
              instruction.getIsVolatile());
      return null;
    }

    @Override
    public Void visit(GAddSoftRefInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      heap.getObject(instruction.getRefererId())
          .softReference(
              instruction.getReferenceId(),
              heap.getObject(instruction.getReferentId()),
              instruction.getIsVolatile());
      return null;
    }

    @Override
    public Void visit(GAddWeakRefInstruction instruction) {
      heap.getObject(instruction.getRefererId())
          .weakReference(
              instruction.getReferenceId(),
              heap.getObject(instruction.getReferentId()),
              instruction.getIsVolatile());
      return null;
    }

    @Override
    public Void visit(GAddFaninRefsInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      OracleObject referent = heap.getObject(instruction.getReferentId());
      List<GID> refererIds = instruction.getRefererIds();
      List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
      List<Boolean> isVolatile = instruction.getIsVolatile();
      List<GID> referenceIds = instruction.getReferenceIds();
      for (int i = 0; i < refererIds.size(); i++) {
        heap.getObject(refererIds.get(i))
            .reference(referenceIds.get(i), referent, referenceTypes.get(i), isVolatile.get(i));
      }
      return null;
    }

    @Override
    public Void visit(GAddFanoutRefsInstruction instruction) {
      instruction.checkValidity(heap.getGraph());
      OracleObject referer = heap.getObject(instruction.getRefererId());
      List<GID> referentIds = instruction.getReferentIds();
      List<GReference.Type> referenceTypes = instruction.getReferenceTypes();
      List<Boolean> isVolatile = instruction.getIsVolatile();
      List<GID> referenceIds = instruction.getReferenceIds();
      for (int i = 0; i < referentIds.size(); i++) {
        referer.reference(
            referenceIds.get(i),
            heap.getObject(referentIds.get(i)),
            referenceTypes.get(i),
            isVolatile.get(i));
      }
      return null;
    }

    @Override
    public Void visit(GStrengthenRefInstruction instruction) {
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
      OracleReference ref1 = heap.getReference(instruction.getReferenceId1());
      OracleReference ref2 = heap.getReference(instruction.getReferenceId2());
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
}
