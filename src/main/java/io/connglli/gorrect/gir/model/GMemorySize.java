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

package io.connglli.gorrect.gir.model;

import java.util.LinkedHashMap;
import java.util.Map;

import io.connglli.gorrect.utility.GAssert;

/**
 * Represents a memory size with different units (bytes, KB, MB, GB).
 */
public class GMemorySize {
    // @formatter:off
    private static final Map<String, Long> MULTIPLIERS = new LinkedHashMap<>();
    static {
      MULTIPLIERS.put("B", 1L);
      MULTIPLIERS.put("KB", 1024L);
      MULTIPLIERS.put("MB", 1024L * 1024L);
      MULTIPLIERS.put("GB", 1024L * 1024L * 1024L);
      MULTIPLIERS.put("TB", 1024L * 1024L * 1024L * 1024L);
      MULTIPLIERS.put("PB", 1024L * 1024L * 1024L * 1024L * 1024L);
      MULTIPLIERS.put("EB", 1024L * 1024L * 1024L * 1024L * 1024L * 1024L);
      // MULTIPLIERS.put("ZB", 1024L * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L);
      // MULTIPLIERS.put("YB", 1024L * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L);
    }
    // @formatter:on

    /**
     * Parse a size string like "30m", "25k", "1g", "512" (bytes)
     */
    public static long parse(String sizeStr) {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            GAssert.panic("Empty size string");
        }

        String trimmed = sizeStr.trim().toLowerCase();
        String number = trimmed.replaceAll("[^\\d]", "");
        String unit = trimmed.replaceAll("[\\d]", "").toUpperCase();

        if (number.isEmpty()) {
            GAssert.panic("Invalid size format: " + sizeStr);
        }
        if (!MULTIPLIERS.containsKey(unit)) {
            GAssert.panic("Unsupported unit: " + unit);
        }

        return Long.parseLong(number) * MULTIPLIERS.get(unit);
    }

    public static String toString(long bytes) {
        return toString(bytes, 2);
    }

    public static String toString(long bytes, int decimals) {
        if (bytes == 0)
            return "0B";

        String[] units = MULTIPLIERS.keySet().toArray(new String[0]);
        int k = 1024, i = 0;
        double size = bytes;

        while (size >= k && i < units.length - 1) {
            size /= k;
            i++;
        }

        // noinspection MalformedFormatString
        return String.format("%." + decimals + "f%s", size, units[i]);
    }
}
