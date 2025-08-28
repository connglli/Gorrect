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

import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import java.util.List;
import lombok.NonNull;

/**
 * A NoOpVM is a virtual machine that does not perform any actual memory management assuming
 * unlimited memory. It is used for testing purposes or when no memory management is required.
 */
public class NoOpVM extends LangVM {

  public NoOpVM() {
    super(Long.MAX_VALUE);
  }

  @Override
  public NoOpVM clone() {
    return new NoOpVM();
  }

  @Override
  public long estimateNewObjectHeapSize(@NonNull GID objectId, long requestedSize) {
    return requestedSize;
  }

  @Override
  public long estimateObjectHeapSize(OracleObject object) {
    return object.getSize();
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
      GReference.Type type) {}

  @Override
  public void onOracleUnlink(@NonNull OracleObject referer, @NonNull OracleObject referent) {}

  @Override
  public void onOracleClean(@NonNull List<OracleObject> removedObjects) {}

  @Override
  public void onOracleCompact(@NonNull List<OracleObject> removedObjects) {}

  @Override
  public void onOracleStressCompact(@NonNull List<OracleObject> removedObjects) {}
}
