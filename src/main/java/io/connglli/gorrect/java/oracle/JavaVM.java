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

/**
 * Abstract class representing a Java Virtual Machine (JVM) for the OracleInterpreter. This class
 * exists because the Java specification does not define the memory layout of objects: "For example,
 * the memory layout of run-time data areas, the garbage-collection algorithm used, and any internal
 * optimization of the Java Virtual Machine instructions (for example, translating them into machine
 * code) are left to the discretion of the implementor." See <a
 * href=https://docs.oracle.com/javase/specs/index.html">Java SE Specifications</a>.
 */
public abstract class JavaVM extends LangVM {

  public JavaVM(long heapCapacity) {
    super(heapCapacity);
  }
}
