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

package io.connglli.gorrect.java;

/** Do NOT use any imports in this class. Use the full qualified class name instead. */
class ForceGC {

  public static void tryGC() {
    System.gc();
  }

  public static boolean forceGC() {
    return forceGC(-1L);
  }

  public static boolean forceGC(long milliseconds) {
    java.lang.ref.ReferenceQueue<Object> queue = new java.lang.ref.ReferenceQueue<>();
    java.lang.ref.WeakReference<Object> expectedRef =
        new java.lang.ref.WeakReference<>(new Object(), queue);

    long period = 5; // milliseconds
    long retries = milliseconds < 0 ? Long.MAX_VALUE : (milliseconds / period);
    for (; retries >= 0; retries--) {
      System.gc(); // Try to trigger GC
      try {
        // When GC happens, the reference will be enqueued
        java.lang.ref.Reference<?> actualRef = queue.remove(period);
        if (actualRef == null) {
          continue; // Timeout, retry
        } else if (!actualRef.equals(expectedRef)) {
          // This should never happen
          throw new IllegalStateException("Unexpected reference from the queue");
        } else {
          return true; // Success
        }
      } catch (InterruptedException e) {
        return false;
      }
    }

    return false;
  }

  public static boolean forceGCInMemoryPressure() {
    java.util.Random random = new java.util.Random(0);
    java.util.LinkedList<java.lang.ref.SoftReference<byte[]>> softRefs =
        new java.util.LinkedList<>();
    memoryPressureHolder = softRefs;
    java.util.LinkedList<Object> oomTrigs = new java.util.LinkedList<>();
    memoryPressureHolder = oomTrigs;

    long totalMemory = Runtime.getRuntime().totalMemory();

    long minObjSize = totalMemory / 250;
    long maxObjSize = minObjSize * 16;
    long factor = maxObjSize - minObjSize;

    int trigSize = (int) Math.min(totalMemory, 256_000_000); // 256MB

    try {
      long numSoftRefs = (long) (0.95 * totalMemory / minObjSize);

      while (numSoftRefs-- > 0) {
        int allocationSize = (int) minObjSize + (int) (random.nextDouble() * factor);
        softRefs.add(new java.lang.ref.SoftReference<>(new byte[allocationSize]));
      }

      while (true) {
        oomTrigs.add(new byte[trigSize]);
      }
    } catch (OutOfMemoryError e) {
      memoryPressureHolder = null;
      oomTrigs = null;

      long oomSoftRefSize = softRefs.size();
      long numNonNull = 0;

      for (java.lang.ref.SoftReference<byte[]> ref : softRefs) {
        if (ref.get() != null) {
          numNonNull++;
        }
      }

      softRefs = null;
      assert numNonNull == 0
          : "Soft references should be cleared under memory pressure, but found %d non-null soft references among %d soft references"
              .formatted(numNonNull, oomSoftRefSize);
    } finally {
      assert oomTrigs == null
          : "Should have cleared all objects in OOM triggers array, i.e., oomTrigs";
      assert softRefs == null
          : "Should have cleared all objects in soft references array, i.e., softRefs";
    }
    return true;
  }

  private static Object memoryPressureHolder = null; // To prevent compiler optimization
}
