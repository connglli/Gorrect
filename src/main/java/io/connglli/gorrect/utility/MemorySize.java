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

package io.connglli.gorrect.utility;

import java.util.Arrays;
import lombok.NonNull;

/** Represents a memory size with different units (bytes, KB, MB, GB). */
public class MemorySize {
  public enum Unit {
    B(1L),
    KB(1024L),
    MB(1024L * 1024L),
    GB(1024L * 1024L * 1024L),
    TB(1024L * 1024L * 1024L * 1024L),
    PB(1024L * 1024L * 1024L * 1024L * 1024L),
    EB(1024L * 1024L * 1024L * 1024L * 1024L * 1024L);

    public static String[] names() {
      return Arrays.stream(Unit.values())
          .map(Unit::name)
          .map(String::toUpperCase)
          .toArray(String[]::new);
    }

    private final long multiplier;

    Unit(long multiplier) {
      this.multiplier = multiplier;
    }
  }

  public static long of(long num, @NonNull final Unit unit) {
    return num * unit.multiplier;
  }

  /** Parse a size string like "30m", "25k", "1g", "512" (bytes) */
  public static long parse(String size) {
    if (size == null || size.trim().isEmpty()) {
      Assert.panic("Empty size string");
    }

    String trimmed = size.trim().toLowerCase();
    String number = trimmed.replaceAll("[^\\d]", "");
    String unit = trimmed.replaceAll("[\\d]", "").toUpperCase();

    if (number.isEmpty()) {
      Assert.panic("Invalid size format: " + size);
    }
    try {
      return of(Long.parseLong(number), Unit.valueOf(unit));
    } catch (NumberFormatException e) {
      Assert.panic("Invalid number format in size string: " + size);
      return 0; // Unreachable, but needed to satisfy the compiler
    } catch (IllegalAccessError e) {
      Assert.panic("Unsupported unit: " + unit);
      return 0; // Unreachable, but needed to satisfy the compiler
    }
  }

  public static String toString(long bytes) {
    return toString(bytes, 2);
  }

  public static String toString(long bytes, int decimals, boolean noB) {
    if (noB) {
      return toString(bytes, decimals, false).replace("B", "");
    } else {
      return toString(bytes, decimals);
    }
  }

  public static String toString(long bytes, int decimals) {
    if (bytes == 0) return "0B";

    int k = 1024, i = 0;
    double size = bytes;

    String[] units = Unit.names();
    while (size >= k && i < units.length - 1) {
      size /= k;
      i++;
    }

    // noinspection MalformedFormatString
    return String.format("%." + decimals + "f%s", size, units[i]);
  }
}
