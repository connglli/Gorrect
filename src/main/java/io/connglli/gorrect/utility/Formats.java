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
import java.util.stream.Collectors;

public final class Formats {

  public static String toSnakeCase(String name) {
    return name.chars()
        .mapToObj(
            c ->
                Character.isUpperCase(c)
                    ? "_" + ((char) Character.toLowerCase(c))
                    : "" + ((char) c))
        .collect(Collectors.joining())
        .toLowerCase();
  }

  public static String toCamelCase(String name, boolean capitalize) {
    String[] parts = name.split("_");
    String camelCase =
        Arrays.stream(parts)
            .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1).toLowerCase())
            .collect(Collectors.joining());
    return capitalize
        ? camelCase
        : Character.toLowerCase(camelCase.charAt(0)) + camelCase.substring(1);
  }

  public static String toCamelCase(String name) {
    return toCamelCase(name, false);
  }

  public static String toCommaString(int number) {
    return String.format("%,d", number);
  }

  public static String toCommaString(long number) {
    return String.format("%,d", number);
  }

  private Formats() {}
}
