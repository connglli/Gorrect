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

package org.openjdk.jol.vm;

import org.openjdk.jol.datamodel.DataModelExt;

public class ModelVMExt implements DataModelExt {

  @Override
  public int markHeaderSize() {
    return VMExt.current().addressSize();
  }

  @Override
  public int classHeaderSize() {
    return VMExt.current().classPointerSize();
  }

  @Override
  public int arrayLengthHeaderSize() {
    return 4;
  }

  @Override
  public int headerSize() {
    return markHeaderSize() + classHeaderSize();
  }

  @Override
  public int arrayHeaderSize() {
    return headerSize() + arrayLengthHeaderSize();
  }

  @Override
  public int sizeOf(String klass) {
    return (int) VMExt.current().sizeOfField(klass);
  }

  @Override
  public int objectAlignment() {
    return VMExt.current().objectAlignment();
  }

  @Override
  public String vmDetails() {
    return VMExt.current().details();
  }

  @Override
  public String toString() {
    return "Current VMExt";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else {
      return o != null && getClass() == o.getClass();
    }
  }

  @Override
  public int hashCode() {
    return 0;
  }
}
