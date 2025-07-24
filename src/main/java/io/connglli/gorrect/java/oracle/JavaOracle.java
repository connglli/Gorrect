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

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.utility.UnmodifiableGraph;
import io.connglli.gorrect.oracle.Oracle;
import io.connglli.gorrect.utility.Pair;
import java.util.*;
import lombok.NonNull;

public class JavaOracle extends Oracle {

  public JavaOracle(@NonNull GGraph oracle) {
    super(oracle);
  }

  @Override
  public ValidationResult doValidate(@NonNull UnmodifiableGraph obtained) {
    Map<GID, String> objViol = new HashMap<>();
    {
      // Root objects in the oracle should still be roots in the obtained graph.
      for (GObject oracleRoot : oracle.getRoots()) {
        GID oracleRootId = oracleRoot.getId();
        if (!obtained.hasObject(oracleRootId)) {
          objViol.putIfAbsent(oracleRootId, "Root object not found");
        } else if (!obtained.getObject(oracleRootId).isRoot()) {
          objViol.putIfAbsent(oracleRootId, "Root object is not root");
        }
      }
      // Any root object in the obtained graph that is not found in the oracle should be reported.
      for (GObject obtainedRoot : obtained.getRoots()) {
        GID obtainedRootId = obtainedRoot.getId();
        if (!oracle.hasObject(obtainedRootId)) {
          objViol.putIfAbsent(obtainedRootId, "Unexpected root object found");
        }
      }

      // Strongly reachable objects in the oracle should remain strongly reachable.
      for (GID oracleObj : oracle.getStronglyReachableObjects()) {
        if (!obtained.hasObject(oracleObj)) {
          objViol.putIfAbsent(oracleObj, "Strongly reachable object not found");
        } else if (!obtained.isStronglyReachable(oracleObj)) {
          objViol.putIfAbsent(
              oracleObj,
              "Unexpected reachability, expected: %s-reachable, actual: %s-reachable"
                  .formatted(GReference.Type.STRONG, obtained.getReachability(oracleObj)));
        }
      }
      // Strongly reachable objects in the obtained graph that are not found or not strongly
      // reachable in the oracle should be reported.
      for (GID obtainedObj : obtained.getStronglyReachableObjects()) {
        if (!oracle.hasObject(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected strongly reachable object found, expected: cleared, actual: %s-reachable"
                  .formatted(GReference.Type.STRONG));
        } else if (!oracle.isStronglyReachable(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected reachability, expected: %s-reachable, actual: %s-reachable"
                  .formatted(oracle.getReachability(obtainedObj), GReference.Type.STRONG));
        }
      }

      // Softly reachable objects in the oracle can be softly reachable or cleared.
      for (GID oracleObj : oracle.getSoftlyReachableObjects()) {
        // It's okay if the object is not found in the obtained graph
        if (obtained.hasObject(oracleObj) && !obtained.isSoftlyReachable(oracleObj)) {
          objViol.putIfAbsent(
              oracleObj,
              "Unexpected reachability, expected: %s-reachable, actual: %s-reachable"
                  .formatted(GReference.Type.SOFT, obtained.getReachability(oracleObj)));
        }
      }
      // Softly reachable objects in the obtained graph that are not found or not softly reachable
      // in the oracle should be reported.
      for (GID obtainedObj : obtained.getSoftlyReachableObjects()) {
        if (!oracle.hasObject(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected softly reachable object found, expected: cleared, actual: %s-reachable"
                  .formatted(GReference.Type.SOFT));
        } else if (!oracle.isSoftlyReachable(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected reachability, expected: %s-reachable, actual: %s-reachable"
                  .formatted(oracle.getReachability(obtainedObj), GReference.Type.SOFT));
        }
      }

      // All weakly reachable objects should not exist in the obtained since they were all cleared
      // after the last each GC cycle.
      // Indeed, all weakly reachable objects are expected to be cleared after each GC cycle.
      for (GID oracleObj : oracle.getWeaklyReachableObjects()) {
        if (obtained.hasObject(oracleObj)) {
          objViol.putIfAbsent(
              oracleObj,
              "Unexpected weakly reachable object found, expected: cleared, actual: %s-reachable."
                  .formatted(obtained.getReachability(oracleObj)));
        }
      }
      // Since weakly reachable objects are all expected to be cleared after each GC cycle,
      // there shouldn't be any weakly reachable objects in the obtained graph.
      for (GID obtainedObj : obtained.getWeaklyReachableObjects()) {
        if (!oracle.hasObject(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected weakly reachable object found, expected: cleared, actual: %s-reachable"
                  .formatted(GReference.Type.WEAK));
        } else if (oracle.isWeaklyReachable(obtainedObj)) {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected weakly reachable object found, expected: cleared, actual: %s-reachable"
                  .formatted(GReference.Type.WEAK));
        } else {
          objViol.putIfAbsent(
              obtainedObj,
              "Unexpected reachability, expected: %s-reachable, actual: %s-reachable"
                  .formatted(oracle.getReachability(obtainedObj), GReference.Type.WEAK));
        }
      }
    }

    Map<Pair<GID, GID>, String> refViol = new HashMap<>();
    {
      //
      // For each reference in the oracle, the reachability of the referer and referent and the
      // existence of the reference in the obtained must satisfy the following matrix (where v means
      // must exist, o means it's okay if it doesn't exist, and x means must not exist):
      //
      //                     referer
      //                        |
      //                        v
      //                    +--------+--------+------+------+
      //       referent --> |        | STRONG | SOFT | WEAK |
      //                    +--------+--------+------+------+
      //                    | STRONG |   v    |  o   |  x   |
      //                    +--------+--------+------+------+
      //                    |  SOFT  |   o    |  o   |  x   |
      //                    +--------+--------+------+------+
      //                    |  WEAK  |   x    |  x   |  x   |
      //                    +--------+--------+------+------+
      //
      // The matrix should be satisfied for all references in the oracle, regardless of its type.
      //
      for (GReference oracleRef : oracle.getReferences()) {
        GID oracleReferer = oracleRef.getReferer();
        GID oracleReferent = oracleRef.getReferent();
        GReference.Type oracleRefererReach = oracle.getReachability(oracleReferer);
        GReference.Type oracleReferentReach = oracle.getReachability(oracleReferent);
        if (oracleRefererReach == GReference.Type.STRONG
            && oracleReferentReach == GReference.Type.STRONG) {
          if (!obtained.hasReference(oracleReferer, oracleReferent)) {
            refViol.putIfAbsent(
                Pair.of(oracleReferer, oracleReferent),
                "The reference between the %s-reachable referer and the %s-reachable referent not found"
                    .formatted(GReference.Type.STRONG, GReference.Type.STRONG));
          }
        } else if (oracleReferentReach == GReference.Type.WEAK) {
          if (obtained.hasReference(oracleReferer, oracleReferent)) {
            refViol.putIfAbsent(
                Pair.of(oracleReferer, oracleReferent),
                "Unexpected incoming weak reference found for a %s-reachable referent"
                    .formatted(GReference.Type.WEAK));
          }
        } else if (oracleRefererReach == GReference.Type.WEAK) {
          if (obtained.hasReference(oracleReferer, oracleReferent)) {
            refViol.putIfAbsent(
                Pair.of(oracleReferer, oracleReferent),
                "Unexpected outgoing weak reference found for a %s-reachable referer"
                    .formatted(GReference.Type.WEAK));
          }
        }
      }
    }

    if (!objViol.isEmpty() || !refViol.isEmpty()) {
      return failure(
          "Validation failed, found %d object violations and %d reference violations."
              .formatted(objViol.size(), refViol.size()),
          refViol,
          objViol);
    }

    return success();
  }
}
