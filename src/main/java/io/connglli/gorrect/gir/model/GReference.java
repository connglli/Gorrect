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

import io.connglli.gorrect.gir.model.visitor.ReferenceVisitor;
import io.connglli.gorrect.utility.GAssert;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

/** GReference represents a reference between two objects */
@Data
@AllArgsConstructor
public class GReference {
  public enum Type {
    STRONG,
    SOFT,
    WEAK;

    public boolean strongerThanOrEqual(Type other) {
      if (this == STRONG) {
        return other == STRONG;
      } else if (this == SOFT) {
        return other == SOFT || other == WEAK;
      } else {
        return other == WEAK;
      }
    }

    public boolean strongerThan(Type other) {
      if (this == STRONG) {
        return other != STRONG;
      } else if (this == SOFT) {
        return other == WEAK;
      } else {
        return false;
      }
    }

    public boolean weakerThan(Type other) {
      return !strongerThanOrEqual(other);
    }

    public boolean weakerThanOrEqual(Type other) {
      return !strongerThan(other);
    }

    public Type soften() {
      if (this == STRONG) {
        return SOFT;
      } else if (this == SOFT) {
        return WEAK;
      } else if (this == WEAK) {
        GAssert.panic("Weak reference cannot be further soften");
        return WEAK;
      } else {
        GAssert.panic("Cannot reach here");
        return WEAK;
      }
    }

    public Type strengthen() {
      if (this == WEAK) {
        return SOFT;
      } else if (this == SOFT) {
        return STRONG;
      } else if (this == STRONG) {
        GAssert.panic("Strong reference cannot be further strengthen");
        return STRONG;
      } else {
        GAssert.panic("Cannot reach here");
        return STRONG;
      }
    }
  }

  private final GID id; // Unique identifier for the reference
  private final GID referer; // The source/from object of the reference
  private final GID referent; // The target/to object being referenced
  private Type type; // The strength of the reference

  public GReference(@NonNull GReference other) {
    this.id = other.id;
    this.referer = other.referer;
    this.referent = other.referent;
    this.type = other.type;
  }

  public boolean isStrong() {
    return type == Type.STRONG;
  }

  public boolean isSoft() {
    return type == Type.SOFT;
  }

  public boolean isWeak() {
    return type == Type.WEAK;
  }

  public void accept(ObjectGraph graph, @NonNull ReferenceVisitor visitor) {
    visitor.visit(graph, this);
  }

  @Override
  public String toString() {
    return "ref(" + id + ": " + referer + "-[" + type.name() + "]->" + referent + ")";
  }
}
