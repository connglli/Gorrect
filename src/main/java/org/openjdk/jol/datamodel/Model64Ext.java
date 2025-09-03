/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
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

package org.openjdk.jol.datamodel;

import lombok.NonNull;

public class Model64Ext implements DataModelExt {

  @NonNull private final Model64 model;

  public Model64Ext(boolean compressedRefs, boolean compressedClasses, int align) {
    model = new Model64(compressedRefs, compressedClasses, align);
  }

  public Model64Ext(boolean compressedRefs, boolean compressedClasses) {
    model = new Model64(compressedRefs, compressedClasses);
  }

  @Override
  public int objectAlignment() {
    return model.objectAlignment();
  }

  @Override
  public int sizeOf(String klass) {
    return model.sizeOf(klass);
  }

  @Override
  public int arrayHeaderSize() {
    return model.arrayHeaderSize();
  }

  @Override
  public int headerSize() {
    return model.headerSize();
  }

  @Override
  public int arrayLengthHeaderSize() {
    return model.arrayLengthHeaderSize();
  }

  @Override
  public int classHeaderSize() {
    return model.classHeaderSize();
  }

  @Override
  public int markHeaderSize() {
    return model.markHeaderSize();
  }

  @Override
  public String toString() {
    return model.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof Model64Ext m) {
      return model.equals(m.model);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return model.hashCode();
  }
}
