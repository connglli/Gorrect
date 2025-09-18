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
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.utility.*;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

public class JavaTranslator {
  private final Logger log = Logger.getLogger(JavaTranslator.class);

  public static final String HEAP_DUMP_SIGNAL = "<GORRECT_HEAP_DUMP_SIGNAL>";

  @NonNull private final GeneratedHeap heap;
  @NonNull private final Impl impl;

  public JavaTranslator() {
    this(true);
  }

  public JavaTranslator(boolean strict) {
    this.heap = new GeneratedHeap(strict);
    this.impl = new Impl();
  }

  public void translate(@NonNull GProgram program) {
    program.accept(this.impl);
    heap.clean(/* appGCStmt= */ true);
  }

  /// Get the UUID of the generated Java class.
  public String getUUID() {
    return heap.getUUID();
  }

  /// Get the cleaned state of the heap.
  public GGraph getState() {
    return heap.getState();
  }

  /// Generate the Java code and return the relative file path to the workdir parameter.
  /// The generated code will include soft assertions to validate the runtime state against the
  /// oracle. If heapDump is true, the generated code will leave a prompt at the end of the main
  /// method to wait for the user to trigger a heap dump using the HprofDumper utility.
  public String exportToJava(String workdir, GGraph oracleState, boolean heapDump) {
    // Insert oracle validation at the end of the main method
    heap.appendComment("Validate the runtime state against the oracle state");
    heap.appendOracleCheck(oracleState);
    if (heapDump) {
      // Insert a dump heap instruction at the end of the main method
      heap.appendComment("Wait until the heap state is dumped");
      // HprofDumper will wait until the process starts to read from its, so we print
      // something to let it know. See HprofDumper.java for more details.
      heap.appendStatement("System.out.println($S)", HEAP_DUMP_SIGNAL);
      heap.appendStatement("System.in.read()");
    }
    String file = heap.exportToJava(workdir);
    log.info("Java code generated into " + file);
    return file;
  }

  private class Impl implements GProgramVisitor<Void> {
    public Void visit(GProgram program) {
      log.debug("--------------");
      log.debug(">> Executing: " + program.getConfiguration());
      heap.appendComment(program.getConfiguration().toString());
      heap.appendComment("Initial State:");
      heap.appendComment(program.getConfiguration().getInitState().formatToText());
      program.getConfiguration().accept(this);
      log.debug("   State:");
      log.debug(heap.formatGraphStateToText().indent(5).stripTrailing());
      log.debug(heap.formatObjectStateToText().indent(5).stripTrailing());
      for (var instruction : program.getInstructions()) {
        log.debug("--------------");
        log.debug(">> Executing: " + instruction);
        heap.appendComment(instruction.toString());
        instruction.accept(this);
        log.debug("   State:");
        log.debug(heap.formatGraphStateToText().indent(5).stripTrailing());
        log.debug(heap.formatObjectStateToText().indent(5).stripTrailing());
      }
      heap.appendComment("Current State:");
      heap.appendComment(heap.getState().formatToText());
      return null;
    }

    public Void visit(GSetHeapInstruction instruction) {
      instruction.checkValidity(getState());
      heap.initialize(instruction.getInitState());
      return null;
    }

    @Override
    public Void visit(GForceAnyGCInstruction instruction) {
      instruction.checkValidity(getState());
      heap.compact(/* appGCStmt= */ true);
      return null;
    }

    @Override
    public Void visit(GRunOutOfMemoryInstruction instruction) {
      instruction.checkValidity(getState());
      // When an OOM happens, all soft references are cleared
      heap.stressCompact(/* appGCStmt= */ true);
      return null;
    }

    @Override
    public Void visit(GFragmentHeapInstruction instruction) {
      instruction.checkValidity(getState());
      List<Long> objectSizes = instruction.getObjectSizes();
      // We follow OracleInterpreter to compute the anonymous ID
      List<GID> objectIDs = new ArrayList<>(objectSizes.size());
      for (int i = 0; i < instruction.getCount(); i++) {
        objectIDs.add(OracleInterpreter.computeAnonymousId(instruction, i));
      }
      for (int i = 0; i < instruction.getCount(); i++) {
        heap.allocRootWithoutInitializer(objectIDs.get(i), objectSizes.get(i));
      }
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
          instruction.getReferenceId(),
          instruction.getIsVolatile());
      return null;
    }

    @Override
    public Void visit(GAddAnonymRootInstruction instruction) {
      instruction.checkValidity(getState());
      // We follow OracleInterpreter to compute the anonymous ID
      heap.allocRootWithoutInitializer(
          OracleInterpreter.computeAnonymousId(instruction), instruction.getObjectSize());
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
      heap.clean(/* appGCStmt= */ false);
      return null;
    }

    @Override
    public Void visit(GFreezeObjInstruction instruction) {
      instruction.checkValidity(getState());
      // Do nothing. Frozen objects do not influence the generation of the heap.
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
                  alloctor.reference(
                      new GID(reference.getId() + instruction.getReferenceSuffix()),
                      referer,
                      clone,
                      reference.getType(),
                      reference.getIsVolatile());
                });
        object
            .getReferents()
            .forEach(
                referent -> {
                  GeneratedReference reference = heap.getReference(object, referent);
                  alloctor.reference(
                      new GID(reference.getId() + instruction.getReferenceSuffix()),
                      clone,
                      referent,
                      reference.getType(),
                      reference.getIsVolatile());
                });
      }

      return null;
    }

    public Void visit(GRemoveObjInstruction instruction) {
      instruction.checkValidity(getState());
      heap.getObject(instruction.getObjectId()).free();
      heap.clean(/* appGCStmt= */ false);
      return null;
    }

    public Void visit(GAddStrongRefInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedObject referer = heap.getObject(instruction.getRefererId());
      GeneratedObject referent = heap.getObject(instruction.getReferentId());
      referer.strongReference(instruction.getReferenceId(), referent, instruction.getIsVolatile());
      return null;
    }

    public Void visit(GAddSoftRefInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedObject referer = heap.getObject(instruction.getRefererId());
      GeneratedObject referent = heap.getObject(instruction.getReferentId());
      referer.softReference(instruction.getReferenceId(), referent, instruction.getIsVolatile());
      return null;
    }

    public Void visit(GAddWeakRefInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedObject referer = heap.getObject(instruction.getRefererId());
      GeneratedObject referent = heap.getObject(instruction.getReferentId());
      referer.weakReference(instruction.getReferenceId(), referent, instruction.getIsVolatile());
      return null;
    }

    public Void visit(GAddFaninRefsInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedObject referent = heap.getObject(instruction.getReferentId());
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

    public Void visit(GAddFanoutRefsInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedObject referer = heap.getObject(instruction.getRefererId());
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

    public Void visit(GReplaceReferentInstruction instruction) {
      instruction.checkValidity(getState());
      heap.getReference(instruction.getReferenceId())
          .redirect(heap.getObject(instruction.getNewReferentId()));
      heap.clean(/* appGCStmt= */ false);
      return null;
    }

    public Void visit(GSwapReferentsInstruction instruction) {
      instruction.checkValidity(getState());
      GeneratedReference ref1 = heap.getReference(instruction.getReferenceId1());
      GeneratedReference ref2 = heap.getReference(instruction.getReferenceId2());
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

    public Void visit(GStrengthenRefInstruction instruction) {
      instruction.checkValidity(getState());
      heap.getReference(instruction.getReferenceId()).strengthen();
      return null;
    }

    public Void visit(GRemoveRefInstruction instruction) {
      instruction.checkValidity(getState());
      heap.getReference(instruction.getReferenceId()).unlink();
      heap.clean(/* appGCStmt= */ false);
      return null;
    }

    public Void visit(GRemoveAllReferentsInstruction instruction) {
      instruction.checkValidity(getState());
      heap.getObject(instruction.getRefererId())
          .getReferences()
          .forEach(GeneratedReference::unlink);
      heap.clean(/* appGCStmt= */ false);
      return null;
    }
  }
}
