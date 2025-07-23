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

import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.utility.Assert;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abstract class representing a Java Virtual Machine (JVM) for the OracleInterpreter. This class
 * exists because the Java specification does not define the memory layout of objects: "For example,
 * the memory layout of run-time data areas, the garbage-collection algorithm used, and any internal
 * optimization of the Java Virtual Machine instructions (for example, translating them into machine
 * code) are left to the discretion of the implementor." See <a
 * href=https://docs.oracle.com/javase/specs/index.html">Java SE Specifications</a>.
 */
public abstract class JavaVM extends LangVM {

  protected File homeDirectory;

  public JavaVM(File home, long heapCapacity) {
    super(heapCapacity);
    homeDirectory = home;
    Assert.set(
        Files.exists(Path.of(getJavaCompilerPath())),
        "Java compiler (javac) not found at " + getJavaCompilerPath());
    Assert.set(
        Files.exists(Path.of(getJavaExecutablePath())),
        "Java executable (java) not found at " + getJavaCompilerPath());
  }

  public String getJavaCompilerPath() {
    return homeDirectory.toString() + File.separator + "bin" + File.separator + "javac";
  }

  public String getJavaExecutablePath() {
    return homeDirectory.toString() + File.separator + "bin" + File.separator + "java";
  }

  /*
   * Java Types and Values
   * ---------------------
   *
   * According to Java Language Specification, each Java value can have either a Reference Type or a Primitive Type,
   * where, the size of the reference type is determined by the JVM implementation, and the size of the primitive
   * types are fixed as follows:
   *
   * - Boolean: 4 bytes
   * - Byte: 1 byte
   * - Char: 2 bytes
   * - Short: 2 bytes
   * - Int: 4 bytes
   * - Long: 8 bytes
   * - Float: 4 bytes
   * - Double: 8 bytes
   *
   * More: Types, Values, and Variables (https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html)
   *
   *
   * Java Object Layout
   * -------------------
   *
   * The layout of a Java object in memory is not defined by the Java Language Specification, it is determined by
   * the JVM implementation. However, the shallow size of a Java object can be estimated based on the size of its
   * fields and possibly some other header information.
   */

  public abstract long getReferenceSize();

  public long getBoolSize() {
    return 1;
  }

  public long getByteSize() {
    return 1;
  }

  public long getCharSize() {
    return 2;
  }

  public long getShortSize() {
    return 2;
  }

  public long getIntSize() {
    return 4;
  }

  public long getLongSize() {
    return 8;
  }

  public long getFloatSize() {
    return 4;
  }

  public long getDoubleSize() {
    return 8;
  }

  public long align(int unit, long size) {
    return size + (unit - (size % unit)) % unit;
  }
}
