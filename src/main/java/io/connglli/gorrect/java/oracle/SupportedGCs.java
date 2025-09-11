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

import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.MemorySize;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;

/**
 * SupportedGCs enumerates the supported garbage collectors in different JVMs. Each garbage
 * collector may have different performance characteristics and trade-offs. Users can choose a
 * suitable garbage collector based on their application requirements and workload patterns.
 */
public enum SupportedGCs {
  /*
   * HotSpot's garbage collectors: https://github.com/openjdk/jdk/blob/master/src/hotspot/share/gc/../.._globals.hpp
   * We follow the most recent updates as of commit: 3355a9d3fa3e57d489f716ebc1c885c1391274ea
   */
  SERIAL(HotSpotVM.BRAND_NAME, false, false, "-XX:+UseSerialGC"),

  PARALLEL(HotSpotVM.BRAND_NAME, false, false, "-XX:+UseParallelGC"),

  G1(HotSpotVM.BRAND_NAME, true, true, "-XX:+UseG1GC") {
    private static final ConcurrentHashMap<Long, long[]> regionSizeMap = new ConcurrentHashMap<>();

    // See gc/g1/g1HeapRegionBounds.hpp
    private static final long MIN_REGION_SIZE = MemorySize.of(1, MemorySize.Unit.MB);
    private static final long MAX_ERGONOMICS_SIZE = MemorySize.of(32, MemorySize.Unit.MB);
    private static final long MAX_REGION_SIZE = MemorySize.of(512, MemorySize.Unit.MB);
    private static final long TARGET_REGION_NUMBER = 2048L; // 2048 regions

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return regionSizeMap.computeIfAbsent(
          heapCapacity,
          cap -> {
            // G1 leverages a fixed region size, determined at runtime.
            // See gc/g1/g1HeapRegion.cpp:HeapRegion::setup_heap_region_size()
            long regionSize =
                Math.clamp(cap / TARGET_REGION_NUMBER, MIN_REGION_SIZE, MAX_ERGONOMICS_SIZE);
            // Make sure the region size is a power of two
            regionSize = Long.highestOneBit(regionSize);
            regionSize = Math.clamp(regionSize, MIN_REGION_SIZE, MAX_REGION_SIZE);
            return new long[] {regionSize};
          });
    }

    @Override
    public double getHumongousThreshold() {
      // See gc/g1/g1CollectedHeap.hpp:G1CollectedHeap::humongous_threshold_for()
      return 0.50; // Humongous objects are >= 50% of region size
    }
  },

  Z(HotSpotVM.BRAND_NAME, true, false, "-XX:+UseZGC") {
    private static final ConcurrentHashMap<Long, long[]> regionSizeMap = new ConcurrentHashMap<>();

    // See gc/z/zGlobals.hpp
    private static final long GRANULE_SIZE = MemorySize.of(2, MemorySize.Unit.MB);
    private static final long PAGE_SMALL_SIZE = GRANULE_SIZE; // 2 MB

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return regionSizeMap.computeIfAbsent(
          heapCapacity,
          unused -> {
            // Z leverages variable region sizes, determined at runtime.
            // See gc/z/zHeuristics.cpp:ZHeuristics::set_medium_page_size()
            long pageMediumSizeMax =
                Long.highestOneBit(
                    Math.clamp((long) (heapCapacity * 0.03125), GRANULE_SIZE, GRANULE_SIZE * 16));
            if (pageMediumSizeMax > PAGE_SMALL_SIZE) {
              // Any other sizes larger than medium size will be treated as large sizes.
              // Let's merely add one more.
              long pageLargeSize = pageMediumSizeMax * 8;
              return new long[] {PAGE_SMALL_SIZE, pageMediumSizeMax, pageLargeSize};
            } else {
              return new long[] {PAGE_SMALL_SIZE};
            }
          });
    }

    @Override
    public double getHumongousThreshold() {
      // See gc/z/zGlobals.hpp and gc/z/zHeuristics.cpp:ZHeuristics::set_medium_page_size()
      return 0.125; // Humongous objects are >= 12.5% of region size
    }
  },

  // Generational Z is deprecated from JDK 23
  Z_GEN(HotSpotVM.BRAND_NAME, true, true, "-XX:+UseZGC", "-XX:+ZGenerational") {
    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return Z.getRegionSizes(heapCapacity);
    }

    @Override
    public double getHumongousThreshold() {
      return Z.getHumongousThreshold();
    }
  },

  SHENANDOAH(HotSpotVM.BRAND_NAME, true, false, "-XX:+UseShenandoahGC") {
    private static final ConcurrentHashMap<Long, long[]> regionSizeMap = new ConcurrentHashMap<>();

    // See gc/shenandoah/shenandoah_globals.hpp
    private static final long SHENANDOAH_TARGET_NUM_REGIONS = 2048L; // 2048 regions
    private static final long SHENANDOAH_MIN_REGION_SIZE = MemorySize.of(256, MemorySize.Unit.KB);
    private static final long SHENANDOAH_MAX_REGION_SIZE = MemorySize.of(32, MemorySize.Unit.MB);

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return regionSizeMap.computeIfAbsent(
          heapCapacity,
          unused -> {
            // Shenandoah leverages a fixed region size, determined at runtime.
            // Shenandoah region size must be a power of two.
            // See gc/shenandoah/shenandoahHeapRegion.cpp:shenandoahHeapRegion::setup_sizes()
            // FIXME: Account for heap alignment and region alignment here
            return new long[] {
              Long.highestOneBit(
                  Math.clamp(
                      heapCapacity / SHENANDOAH_TARGET_NUM_REGIONS,
                      SHENANDOAH_MIN_REGION_SIZE,
                      SHENANDOAH_MAX_REGION_SIZE))
            };
          });
    }

    @Override
    public double getHumongousThreshold() {
      // See gc/shenandoah/shenandoahHeapRegion.hpp:ShenandoahHeapRegion::requires_humongous()
      return 1.0; // Humongous objects are 100% of region size
    }
  },

  // Generational Shenandoah is available since JDK 24
  SHENANDOAH_GEN(
      HotSpotVM.BRAND_NAME,
      true,
      true,
      "-XX:+UseShenandoahGC",
      "-XX:+ShenandoahGCMode=generational") {

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return SHENANDOAH.getRegionSizes(heapCapacity);
    }

    @Override
    public double getHumongousThreshold() {
      return SHENANDOAH.getHumongousThreshold();
    }
  },

  /*
   * Zing's garbage collectors: https://docs.azul.com/prime/c4-garbage-collection.html
   */
  C4(ZingVM.BRAND_NAME, true, true, "-XX:+UseGenPauselessGC") {},

  /*
   * OpenJ9's garbage collectors: https://eclipse.dev/openj9/docs/allocation/
   */
  GENCON(OpenJ9VM.BRAND_NAME, false, true, "-Xgcpolicy:gencon"),

  BALANCED(OpenJ9VM.BRAND_NAME, true, true, "-Xgcpolicy:balanced") {
    private static final ConcurrentHashMap<Long, long[]> regionSizeMap = new ConcurrentHashMap<>();

    private static final long MAX_NUM_REGIONS = 2048L; // 2048 regions
    private static final long MIN_REGION_SIZE = MemorySize.of(512, MemorySize.Unit.KB);
    private static final long SMALL_HEAP_THRESHOLD = MemorySize.of(512, MemorySize.Unit.MB);
    private static final long SMALL_HEAP_MIN_NUM_REGIONS = 1024L; // 1024 regions
    private static final long SMALL_HEAP_MAX_NUM_REGIONS = 2047L; // 2047 regions

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return regionSizeMap.computeIfAbsent(
          heapCapacity,
          cap -> {
            // The region size is selected at startup based on the maximum heap size. The collector
            // chooses the smallest power of two which will result in less than 2048 regions, with a
            // minimum region size of 512 KB. Except for small heaps (less than about 512 MB) the VM
            // aims to have between 1024 and 2047 regions.
            // See: https://eclipse.dev/openj9/docs/gc/#balanced-policy
            long regionSize;
            if (cap < SMALL_HEAP_THRESHOLD) {
              regionSize = 1;
              while (cap / regionSize < SMALL_HEAP_MIN_NUM_REGIONS
                  || cap / regionSize > SMALL_HEAP_MAX_NUM_REGIONS) {
                regionSize <<= 1;
              }
            } else {
              regionSize = MIN_REGION_SIZE;
              while (cap / regionSize > MAX_NUM_REGIONS) {
                regionSize <<= 1;
              }
            }
            return new long[] {regionSize};
          });
    }

    @Override
    public double getHumongousThreshold() {
      /*
       * From: https://assets.ctfassets.net/oxjq45e8ilak/3d22xuJ2HeQkoAesWkKic0/1ee2d740edcc5ebaf1602ae16a680267/CharlieGracie_DeepDiveIntoTheEclipseOpenJ9Technologies.pdf
       * We know that:
       *
       * -Xgcpolicy:balanced heap
       *  + No non-array object can be larger than region size
       *    + Object size > region size == OutOfMemoryError
       *  + Large arrays are allocated  as arraylets
       *    + Arrays less than region size are allocated as normal arrays
       *
       * So let's assume a humongous object is an object larger than 75% of region size.
       */
      return 0.75; // Humongous objects are >= 75% of region size
    }
  },

  OPTAVGPAUSE(OpenJ9VM.BRAND_NAME, false, false, "-Xgcpolicy:optavgpause"),

  OPTTHRUPUT(OpenJ9VM.BRAND_NAME, false, false, "-Xgcpolicy:optthruput"),

  METRONOME(OpenJ9VM.BRAND_NAME, true, false, "-Xgcpolicy:metronome") {
    // The Java heap is allocated as a contiguous range of memory, partitioned into small regions
    // of equal size (~64 KB).
    // See: https://eclipse.dev/openj9/docs/gc/#metronome-policy
    private static final long[] REGION_SIZES = {MemorySize.of(64, MemorySize.Unit.KB)};
    private static final long LARGE_OBJECT_SIZE = MemorySize.of(2048, MemorySize.Unit.KB);

    @Override
    public long[] getRegionSizes(long heapCapacity) {
      return REGION_SIZES;
    }

    @Override
    public double getHumongousThreshold() {
      /*
       * From: https://assets.ctfassets.net/oxjq45e8ilak/3d22xuJ2HeQkoAesWkKic0/1ee2d740edcc5ebaf1602ae16a680267/CharlieGracie_DeepDiveIntoTheEclipseOpenJ9Technologies.pdf
       * We know that:
       *
       * -Xgcpolicy:metronome heap
       *  + Objects larger than 2048 bytes consume contiguous regions
       *    + If no contiguous regions a full STW GC is completed before OOM
       *  + Large arrays are allocated as arraylets
       *    + Arraylet leaves are 2048 bytes
       *    + Each arraylet leaf region contains 32 arraylet leaves
       *
       * So we compute the ratio according to that material.
       */
      return ((double) LARGE_OBJECT_SIZE) / REGION_SIZES[0];
    }
  };

  // Which VM this GC is supported on
  @NonNull public final String vmBrand;
  // Whether this GC is region-based
  @NonNull public final Boolean isRegionBased;
  // Whether this GC is generational
  @NonNull public final Boolean isGenerational;
  // The command line option to enable the GC
  @NonNull public final String[] enableOptions;

  SupportedGCs(
      @NonNull String vmBrand, boolean region, boolean generation, @NonNull String... enable) {
    this.vmBrand = vmBrand;
    this.isRegionBased = region;
    this.isGenerational = generation;
    this.enableOptions = enable;
  }

  /**
   * Get the recommended memory region sizes for this garbage collector in bytes. This is a rough
   * estimation and may not be accurate for all scenarios. Enum items should try their best to
   * provide proper region sizes based on their characteristics and typical usage pattern.
   *
   * <p>Region-based garbage collectors divide the heap into a list of regions with fixed/variable
   * sizes. A region can be assigned a role such as Eden, Survivor, Old, Humongous, etc., or nursery
   * and tenured. Region-based garbage collectors can perform garbage collection on a subset of
   * regions, which can reduce pause times and improve throughput. Examples of region-based garbage
   * collectors include G1, Z, Shenandoah, C4, and balanced. Considering collectors typically have
   * different strategies to determine the region sizes, we let each enum item decide the region
   * size based on the given heap capacity. If a garbage collector is not region-based, calling this
   * method will result in a panic.
   */
  public long[] getRegionSizes(long heapCapacity) {
    if (isRegionBased) {
      return new long[] {MemorySize.of(1, MemorySize.Unit.MB)};
    } else {
      Assert.panic("The garbage collector is not region-based: " + this.name());
      return null; // Unreachable but required by the compiler
    }
  }

  /**
   * Get the humongous object threshold for this garbage collector as a fraction of the region size.
   * Humongous objects are objects that are larger than a certain threshold and are allocated
   * exclusively in one or multiple regions. The reason of treating Humongous objects specially is
   * that due to the large overhead when moving a humongous objects.
   *
   * <p>The return value is a percentage (0-1). For example, if the return value is 50, it means
   * that an object is considered humongous if its size is greater than or equal to 50% of the
   * region size.
   */
  public double getHumongousThreshold() {
    if (isRegionBased) {
      return 1.0; // Default to 100% of region size
    } else {
      return 0.75; // Default to 75% of small page size
    }
  }
}
