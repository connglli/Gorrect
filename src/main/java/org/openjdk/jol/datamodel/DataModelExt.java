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

package org.openjdk.jol.datamodel;

public interface DataModelExt extends DataModel {

  /**
   * Provide the implementation details about the VM
   *
   * @return the details string
   */
  default String vmDetails() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("Oop mark word size: %d bytes\n", markHeaderSize()));
    sb.append(String.format("Oop class pointer size: %d bytes\n", classHeaderSize()));
    sb.append(String.format("Object oop header size: %d bytes\n", headerSize()));
    sb.append(String.format("Array oop array header size: %d bytes\n", arrayHeaderSize()));
    sb.append(String.format("Object alignment: %d bytes\n", objectAlignment()));
    sb.append("Object size:\n");
    sb.append("- byte: ").append(sizeOf("byte")).append(" bytes\n");
    sb.append("- boolean: ").append(sizeOf("boolean")).append(" bytes\n");
    sb.append("- char: ").append(sizeOf("char")).append(" bytes\n");
    sb.append("- short: ").append(sizeOf("short")).append(" bytes\n");
    sb.append("- int: ").append(sizeOf("int")).append(" bytes\n");
    sb.append("- float: ").append(sizeOf("float")).append(" bytes\n");
    sb.append("- long: ").append(sizeOf("long")).append(" bytes\n");
    sb.append("- double: ").append(sizeOf("double")).append(" bytes\n");
    sb.append("- reference: ").append(sizeOf("reference")).append(" bytes\n");
    return sb.toString();
  }
}
