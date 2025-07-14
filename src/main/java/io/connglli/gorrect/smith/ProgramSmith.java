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

/** ProgramSmith generates a random GIR program. */
public class ProgramSmith {
  ////////////////////////////////////////////////////////////////////////////
  ///
  /// When adding references, always ensure that a strong/weak/soft reference is added only when a
  /// strong reference exists. Otherwise, undefined behavior may occur. For example:
  ///
  /// 1. add_non_root_obj(u, v) // Add a non-root object v by a weak reference from u.
  /// 2. add_strong_ref(z, v) // Add a strong reference to v
  ///
  /// It is likely that the second instruction may fail and z points to null. Such cases happen when
  /// GC occurs immediately after the first instruction
  ///
  /// Therefore, during program generation, we should always assume that softly and weakly reachable
  /// objects are indeed not reachable at all. Also, we should guarantee that the referer object is
  /// not null (i.e., shouldn't be cleaned, i.e., is strongly reachable).
  ///
  ////////////////////////////////////////////////////////////////////////////
}
