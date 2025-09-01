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

package io.connglli.gorrect.smith;

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.OracleHeap;
import io.connglli.gorrect.oracle.OracleObject;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Formats;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.Rand;
import java.util.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** GraphSmith crafts a random object graph GGraph */
@RequiredArgsConstructor
public class GraphSmith {
  private final Logger log = Logger.getLogger(GraphSmith.class);

  @Builder
  @Getter
  public static class Config {
    /// The IdSmith used to generate unique IDs for objects.
    @NonNull private IdSmith objectIdSmith;

    /// The IdSmith used to generate unique IDs for references.
    @NonNull private IdSmith referenceIdSmith;

    /// The minimum number of root objects in the graph (inclusive).
    @Builder.Default private int minRoots = 1;

    /// The maximum number of root objects in the graph (inclusive).
    @Builder.Default private int maxRoots = 10;

    /// The minimum number of non-root objects in the graph (inclusive).
    @Builder.Default private int minNonRoots = 5;

    /// The maximum number of non-root objects in the graph (inclusive).
    @Builder.Default private int maxNonRoots = 20;

    /// The minimum number of references in the graph (inclusive).
    @Builder.Default private int minRefs = 5;

    /// The maximum number of references in the graph (inclusive).
    /// This is a soft limit, as we may add more references to ensure reachability.
    @Builder.Default private int maxRefs = 30;

    /// The weight of adding a strong reference.
    @Builder.Default private int strongRefWeight = 50;

    /// The weight of adding a soft reference.
    @Builder.Default private int softRefWeight = 20;

    /// The weight of adding a weak reference.
    @Builder.Default private int weakRefWeight = 30;

    /// The minimum size (inclusive) of an object's padding in bytes.
    /// This is a soft limit as we may further decrease the object size to ensure allocation.
    @Builder.Default private long minObjSizeBytes = 1; // 1B

    /// The maximum size (inclusive) of an object's padding in bytes.
    @Builder.Default private long maxObjSizeBytes = 128_000; // 128KB
  }

  private final Rand random = Rand.getInstance();

  @NonNull private final Config config;

  @NonNull
  public GGraph craft(LangVM vm) {
    vm.reset(); // Reset the VM to a clean state

    OracleHeap heap = new OracleHeap(vm);

    List<OracleObject> objects = new ArrayList<>();
    int numRoots = random.nextInt(config.minRoots, config.maxRoots + 1);
    for (int i = 0; i < numRoots; i++) {
      objects.add(nextObject(heap, GObject.Type.GCROOT));
    }

    int numObjs = random.nextInt(config.minNonRoots, config.maxNonRoots + 1);
    for (int i = 0; i < numObjs; i++) {
      objects.add(nextObject(heap, GObject.Type.ORDINARY));
    }

    int numRefs = random.nextInt(config.minRefs, config.maxRefs + 1);
    numRefs = Math.min(numRefs, numObjs * (numObjs - 1));
    for (int i = 0; i < numRefs; i++) {
      for (int attempts = 0; attempts < 100; attempts++) {
        if (nextReference(heap, objects)) {
          break; // Successfully added a reference
        }
      }
    }

    // Ensure that all objects are reachable from the roots
    // This will increase the number of references, perhaps exceeding numRefs.
    enforceReachabilityFromRoots(heap);

    return heap.getState();
  }

  private OracleObject nextObject(OracleHeap heap, GObject.Type type) {
    LangVM vm = heap.getLvm();
    GID id = config.objectIdSmith.next();
    long paddingSize = random.nextLong(config.minObjSizeBytes, config.maxObjSizeBytes + 1);
    long objectSize = vm.estimateNewObjectHeapSize(id, paddingSize);
    while (paddingSize > 0 && objectSize > vm.getHeapUnusedBytes()) {
      paddingSize /= 2;
      objectSize = vm.estimateNewObjectHeapSize(id, paddingSize);
    }
    if (paddingSize < config.minObjSizeBytes) {
      log.warning(
          "Adjusting object padding (%s bytes) to be smaller than the minimum size (%s bytes)",
          Formats.toCommaString(paddingSize), Formats.toCommaString(config.minObjSizeBytes));
    }
    if (paddingSize < 0 || objectSize > vm.getHeapUnusedBytes()) {
      log.error(
          "Too many objects allocated, making heap out of memory. Try increasing the heap size or decreasing the number of objects.");
    }
    if (paddingSize < 0) {
      Assert.panic(
          "Too many objects allocated, making heap out of memory. Try increasing the heap size or decreasing the number of objects.");
    }
    if (type == GObject.Type.GCROOT) {
      return heap.allocRootObject(id, paddingSize);
    } else {
      return heap.allocNonRootObject(id, paddingSize);
    }
  }

  private boolean nextReference(OracleHeap heap, List<OracleObject> objects) {
    if (heap.getLvm().getHeapUnusedBytes() == 0) {
      log.error(
          "Cannot add any references, heap is out of memory. Try increasing the heap size or decreasing the number of objects.");
      return false;
    }

    int u = random.nextInt(objects.size());
    int v = random.nextInt(objects.size());
    if (u == v) {
      return false; // We do not allow self-references
    }

    OracleObject referer = objects.get(u);
    OracleObject referent = objects.get(v);
    if (referer.hasReferent(referent)) {
      return false; // We do not allow duplicate references
    }

    GID refId = config.referenceIdSmith.next(referer.getId(), referent.getId());
    GReference.Type refType =
        random.nextItem(
            new GReference.Type[] {
              GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK
            },
            new int[] {config.strongRefWeight, config.softRefWeight, config.weakRefWeight});

    switch (refType) {
      case STRONG -> referer.strongReference(refId, referent);
      case SOFT -> referer.softReference(refId, referent);
      case WEAK -> referer.weakReference(refId, referent);
      default -> Assert.panic("Cannot create a reference of type " + refType);
    }

    return true;
  }

  private void enforceReachabilityFromRoots(OracleHeap heap) {
    if (heap.getLvm().getHeapUnusedBytes() == 0) {
      log.error(
          "Cannot add any references, heap is out of memory. Try increasing the heap size or decreasing the number of objects.");
    }

    GGraph graph = heap.getState(false);
    List<GID> reachable = new ArrayList<>(graph.getReachableObjects());
    for (GObject rawReferent : graph.getObjects()) {
      GID referentId = rawReferent.getId();
      if (reachable.contains(referentId)) {
        continue;
      }
      // Let's randomly select an object and add a strong reference to it.
      GID refererId = reachable.get(random.nextInt(0, reachable.size()));
      GID referenceId = config.referenceIdSmith.next(refererId, referentId);
      OracleObject referer = heap.getObject(refererId);
      OracleObject referent = heap.getObject(referentId);
      try {
        // Since the object is not reachable, there can't be any references to it from any reachable
        // object. So this is safe when no new references are added. However, after we add any
        // reference to make some previously unreachable objects reachable, some other previously
        // unreachable referent might also become reachable, causing this method to fail.
        referer.strongReference(referenceId, referent);
        reachable.add(referentId);
      } catch (AssertionError e) {
        // pass
      }
    }
    GGraph newGraph = heap.getState(false);
    Assert.set(
        newGraph.getObjects().size() == newGraph.getReachableObjects().size(),
        "Not all objects are reachable even though we have enforced " + "reachability");
  }
}
