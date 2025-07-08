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

/**
 * GInstructionSimpleVisitorBase provides default implementations for all visitor methods. This
 * class can be extended to implement specific visitor behavior by overriding only the methods of
 * interest.
 */
public abstract class GInstructionSimpleVisitorBase<T> implements GInstructionVisitor<T> {

  /** Default implementation that can be overridden by subclasses. */
  protected abstract T defaultVisit(GInstruction instruction);

  @Override
  public T visit(GProgram program) {
    return defaultVisit(program);
  }

  @Override
  public T visit(GSetHeapInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddRootObjInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddNonRootObjInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GMakeRootInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GMakeNonRootInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GRemoveObjInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GCloneObjInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddStrongRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddSoftRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddWeakRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddFaninRefsInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GAddFanoutRefsInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GReplaceReferentInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GSoftenRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GStrengthRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GSwapReferentsInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GRemoveRefInstruction instruction) {
    return defaultVisit(instruction);
  }

  @Override
  public T visit(GRemoveAllReferentsInstruction instruction) {
    return defaultVisit(instruction);
  }
}
