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

import io.connglli.gorrect.gir.model.visitor.GObjectVisitor;
import io.connglli.gorrect.utility.MemorySize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

/** GObject represents an object */
@Data
@AllArgsConstructor
public class GObject {
  public enum Type {
    GCROOT,
    ORDINARY;
  }

  private final GID id; // Unique identifier for the object
  private Type type; // The type of the object
  private long bytes; // The size of the object in bytes

  public GObject(@NonNull GObject other) {
    this.id = other.id;
    this.type = other.type;
    this.bytes = other.bytes;
  }

  public long getSize() {
    return bytes;
  }

  public boolean isRoot() {
    return type == Type.GCROOT;
  }

  public void accept(GGraph graph, @NonNull GObjectVisitor visitor) {
    visitor.visit(graph, this);
  }

  @Override
  public String toString() {
    return "obj(" + id + ":" + type.name() + "=" + MemorySize.toString(bytes) + ")";
  }
}
