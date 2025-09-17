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

package io.connglli.gorrect.gir;

import io.connglli.gorrect.gir.inst.*;

/**
 * GInstructionVisitor provides a visitor pattern interface for processing GIR instructions. This
 * interface allows for type-safe dispatching of operations on different instruction types without
 * requiring modification of the instruction classes themselves.
 */
public interface GProgramVisitor<T> {

  // -=======================================================
  // The entry point for visiting a program
  // -=======================================================

  T visit(GProgram program);

  // -=======================================================
  // Setup instructions
  // -=======================================================

  T visit(GSetHeapInstruction instruction);

  // -=======================================================
  // Garbage collector and heap manipulation instructions
  // -=======================================================

  T visit(GForceAnyGCInstruction instruction);

  T visit(GRunOutOfMemoryInstruction instruction);

  T visit(GFragmentHeapInstruction instruction);

  // -=======================================================
  // Object management instructions
  // -=======================================================

  T visit(GAddRootObjInstruction instruction);

  T visit(GAddNonRootObjInstruction instruction);

  T visit(GAddAnonymRootInstruction instruction);

  T visit(GMakeRootInstruction instruction);

  T visit(GMakeNonRootInstruction instruction);

  T visit(GFreezeObjInstruction instruction);

  T visit(GCloneObjInstruction instruction);

  T visit(GRemoveObjInstruction instruction);

  // -=======================================================
  // Reference management instructions
  // -=======================================================

  T visit(GAddStrongRefInstruction instruction);

  T visit(GAddSoftRefInstruction instruction);

  T visit(GAddWeakRefInstruction instruction);

  T visit(GAddFaninRefsInstruction instruction);

  T visit(GAddFanoutRefsInstruction instruction);

  T visit(GReplaceReferentInstruction instruction);

  T visit(GSwapReferentsInstruction instruction);

  T visit(GSoftenRefInstruction instruction);

  T visit(GStrengthenRefInstruction instruction);

  T visit(GRemoveRefInstruction instruction);

  T visit(GRemoveAllReferentsInstruction instruction);
}
