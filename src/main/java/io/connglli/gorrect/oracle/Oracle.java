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

package io.connglli.gorrect.oracle;

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.utility.UnmodifiableGraph;
import io.connglli.gorrect.utility.Pair;
import java.util.Map;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

/** The Oracle defines a contract for validating object graphs against an oracle graph. */
public abstract class Oracle {

  @Getter @NonNull protected final UnmodifiableGraph oracle;

  public Oracle(@NonNull GGraph oracle) {
    oracle.removeUnreachableObjects();
    this.oracle = UnmodifiableGraph.of(oracle);
  }

  @Value
  public class ValidationResult {
    boolean isValid;
    String failureMessage;
    Map<GID, String> objectViolations; // Maps object IDs to violation messages
    Map<Pair<GID, GID>, String> referenceViolations; // Maps reference IDs to violation messages

    public UnmodifiableGraph getOracle() {
      return oracle;
    }
  }

  /**
   * Checks if the obtained graph is valid according to the oracle graph.
   *
   * <p>NOTICE:<br>
   * 1. Objects can be checked by their IDs<br>
   * 2. Never check references by their IDs as these IDs might be randomized.
   */
  public ValidationResult validate(@NonNull GGraph obtained) {
    obtained.removeUnreachableObjects();
    return doValidate(UnmodifiableGraph.of(obtained));
  }

  protected abstract ValidationResult doValidate(@NonNull UnmodifiableGraph obtained);

  /** Returns a successful validation result. */
  protected ValidationResult success() {
    return new ValidationResult(true, null, null, null);
  }

  /** Returns a failed validation result with the given failure message and violations. */
  protected ValidationResult failure(
      String failureMessage,
      Map<Pair<GID, GID>, String> referenceViolations,
      Map<GID, String> objectViolations) {
    return new ValidationResult(false, failureMessage, objectViolations, referenceViolations);
  }
}
