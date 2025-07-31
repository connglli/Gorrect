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

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.OracleObject;
import java.io.File;
import java.util.List;
import lombok.NonNull;

/**
 * EpsilonVM (or no-op jvm) is a virtual machine that does not perform any actual memory management.
 * It is used for testing purposes or when no memory management is required. The name "Epsilon" is
 * derived from the epsilon GC in HotSpot.
 */
public class EpsilonVM extends JavaVM {

  public EpsilonVM(long heapCapacity) {
    // We use  the native Java home directory as the home directory.
    super(new File(System.getProperty("java.home")), heapCapacity);
  }

  @Override
  public long getReferenceSize() {
    return 0;
  }

  @Override
  public long estimateNewObjectHeapSize(@NonNull GID objectId, long requestedSize) {
    return 0;
  }

  @Override
  public long estimateObjectHeapSize(@NonNull OracleObject object) {
    return 0;
  }

  @Override
  public void onOracleAllocate(@NonNull GID objectId, long size, GObject.Type type) {}

  @Override
  public void onOracleFree(@NonNull OracleObject object) {}

  @Override
  public void onOracleReference(
      @NonNull GID referenceId,
      @NonNull OracleObject referer,
      @NonNull OracleObject referent,
      GReference.@NonNull Type type) {}

  @Override
  public void onOracleUnlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {}

  @Override
  public void onOracleClean(@NonNull List<OracleObject> removedObjects) {}

  @Override
  public void onOracleCompact(@NonNull List<OracleObject> removedObjects) {}

  @Override
  public void onOracleStressCompact(@NonNull List<OracleObject> removedObjects) {}
}
