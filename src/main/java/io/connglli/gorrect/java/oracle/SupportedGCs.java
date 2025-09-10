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

import lombok.NonNull;

/**
 * SupportedGCs enumerates the supported garbage collectors in different JVMs. Each garbage
 * collector may have different performance characteristics and trade-offs. Users can choose a
 * suitable garbage collector based on their application requirements and workload patterns.
 */
public enum SupportedGCs {
  /*
   * HotSpot's garbage collectors: https://github.com/openjdk/jdk21u-dev/blob/master/src/hotspot/share/gc/../.._globals.hpp
   */
  SERIAL(HotSpotVM.BRAND_NAME, "-XX:+UseSerialGC"), // HotSpot's Serial Garbage Collector
  PARALLEL(HotSpotVM.BRAND_NAME, "-XX:+UseParallelGC"), // HotSpot's Parallel Garbage Collector
  G1(HotSpotVM.BRAND_NAME, "-XX:+UseG1GC"), // HotSpot's Garbage-First Garbage Collector
  Z(HotSpotVM.BRAND_NAME, "-XX:+UseZGC"), // HotSpot's Z Garbage Collector
  X(
      HotSpotVM.BRAND_NAME,
      "-XX:+UseZGC",
      "-XX:+ZGenerational"), // HotSpot's Z Garbage Collector with Generation Support
  SHENANDOAH(
      HotSpotVM.BRAND_NAME, "-XX:+UseShenandoahGC"), // HotSpot's Shenandoah Garbage Collector
  /*
   * Zing's garbage collectors: https://docs.azul.com/prime/c4-garbage-collection.html
   */
  C4(ZingVM.BRAND_NAME, "-XX:+UseGenPauselessGC"), // Zing's C4 Garbage Collector
  /*
   * OpenJ9's garbage collectors: https://eclipse.dev/openj9/docs/allocation/
   */
  GENCON(
      OpenJ9VM.BRAND_NAME,
      "-Xgcpolicy:gencon"), // OpenJ9's Generational Concurrent Garbage Collection Policy
  BALANCED(
      OpenJ9VM.BRAND_NAME, "-Xgcpolicy:balanced"), // OpenJ9's Balanced Garbage Collection Policy
  METRONOME(
      OpenJ9VM.BRAND_NAME,
      "-Xgcpolicy:metronome"), // OpenJ9's Metronome Real-time Garbage Collection Policy
  OPTAVGPA(
      OpenJ9VM.BRAND_NAME,
      "-Xgcpolicy:optavgpa"), // OpenJ9's Optimized Average Pause Garbage Collection Policy
  OPTTHRUPUT(
      OpenJ9VM.BRAND_NAME,
      "-Xgcpolicy:optthruput"), // OpenJ9's Optimized Throughput Garbage Collection Policy
  /*
   * The default garbage collectors for all JVMs
   */
  DEFAULT("", ""); // Use the default garbage collector

  // Which VM this GC is supported on
  @NonNull public final String vmBrand;
  // The command line option to enable the GC
  @NonNull public final String[] enableOptions;

  SupportedGCs(@NonNull String vmBrand, @NonNull String... enable) {
    this.vmBrand = vmBrand;
    this.enableOptions = enable;
  }
}
