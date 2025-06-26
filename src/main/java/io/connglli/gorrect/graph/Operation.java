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

package io.connglli.gorrect.graph;

/**
 * Operation represents an operation over the object graph
 */
public abstract class Operation {
    /// Return the name of the operator.
    ///
    /// NOTE:
    ///
    /// All operations should have the class name ending with Operation, otherwise,
    /// the behavior of this method is undefined.
    public String getName() {
        String className = getClass().getSimpleName();
        return className.substring(0, className.lastIndexOf("Operation"));
    }

    /// Apply the operation to the graph using specific inputs.
    ///
    /// NOTE:
    ///
    /// This method should always return the generated graph so that we can chain
    /// graph operations. It is recommended that the generated graph is the original graph
    /// given as input since ObjectGraph itself is not immutable.
    ///
    /// Additionally, it is recommended that applying the operation should result in
    /// a new graph that all objects are reachable. Although we will not check for the issue,
    /// unreachable objects in the graph makes some subsequent chained operations useless.
    public abstract ObjectGraph applyTo(ObjectGraph graph);
}
