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

package io.connglli.gorrect.java.oracle;

import java.io.File;
import org.openjdk.jol.datamodel.DataModelExt;

/** ZingVM represents the Azul Zing JVM, which is based on HotSpot. */
public class ZingVM extends HotSpotVM {
  public static final String BRAND_NAME = "Zing/Azul";

  public ZingVM(
      File home,
      long heapCapacity,
      int version,
      boolean mode64,
      boolean enableCompressedOops,
      boolean enableCompressedClassPointers,
      int alignment) {
    super(
        home,
        heapCapacity,
        version,
        mode64,
        enableCompressedOops,
        enableCompressedClassPointers,
        alignment);
  }

  /*package*/ ZingVM(File home, long heapCapacity, int version, DataModelExt model) {
    super(home, heapCapacity, version, model);
  }

  /*package*/ ZingVM(File home, long heapCapacity, HotSpotObjects model) {
    super(home, heapCapacity, model);
  }

  @SuppressWarnings("MethodDoesntCallSuperMethod")
  @Override
  public ZingVM clone() {
    return new ZingVM(this.homeDirectory, this.getHeapCapacity(), this.model);
  }
}
