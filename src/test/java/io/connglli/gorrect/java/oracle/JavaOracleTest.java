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

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.oracle.Oracle.ValidationResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JavaOracleTest {

  private GGraph oracleGraph;
  private JavaOracle oracle;

  @BeforeEach
  void setUp() {
    oracleGraph = new GGraph();
    // Create a basic graph with root, strongly reachable, softly reachable, and weakly reachable
    // objects
    setupBasicOracleGraph();
    oracle = new JavaOracle(oracleGraph);
  }

  private void setupBasicOracleGraph() {
    // Create objects
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID softObj = new GID("soft");
    GID weakObj = new GID("weak");

    oracleGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    oracleGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    oracleGraph.addObject(softObj, GObject.Type.ORDINARY, 50);
    oracleGraph.addObject(weakObj, GObject.Type.ORDINARY, 50);

    // Create references
    GID strongRef = new GID("strongRef");
    GID softRef = new GID("softRef");
    GID weakRef = new GID("weakRef");

    oracleGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    oracleGraph.addReference(softRef, rootId, softObj, GReference.Type.SOFT);
    oracleGraph.addReference(weakRef, rootId, weakObj, GReference.Type.WEAK);
  }

  @Test
  void testValidateIdenticalGraph() {
    // Create an identical obtained graph
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID softObj = new GID("soft");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(softObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    GID softRef = new GID("softRef");

    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    obtainedGraph.addReference(softRef, rootId, softObj, GReference.Type.SOFT);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertTrue(result.isValid(), "Validation should succeed for a valid graph");
    assertNull(result.getFailureMessage());
  }

  @Test
  void testValidateMissingRootObject() {
    GGraph obtainedGraph = new GGraph();
    // Don't add the root object

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail when root object is missing");
    assertNotNull(result.getFailureMessage());
    assertTrue(result.getObjectViolations().containsKey(new GID("root")));
    assertEquals("Root object not found", result.getObjectViolations().get(new GID("root")));
  }

  @Test
  void testValidateRootObjectNotRoot() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");

    // Add root object but as ordinary type instead of GCROOT
    obtainedGraph.addObject(rootId, GObject.Type.ORDINARY, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);

    // Add reference to make it reachable (so it won't be removed)
    GID strongRef = new GID("strongRef");
    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail when root object is not marked as root");
    assertTrue(result.getObjectViolations().containsKey(rootId));
    assertEquals("Root object not found", result.getObjectViolations().get(rootId));
  }

  @Test
  void testValidateUnexpectedRootObject() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID unexpectedRootId = new GID("unexpectedRoot");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(unexpectedRootId, GObject.Type.GCROOT, 100);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail when unexpected root object is found");
    assertTrue(result.getObjectViolations().containsKey(unexpectedRootId));
    assertEquals(
        "Unexpected root object found", result.getObjectViolations().get(unexpectedRootId));
  }

  @Test
  void testValidateMissingStronglyReachableObject() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    // Add root but not the strongly reachable object
    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(
        result.isValid(), "Validation should fail when strongly reachable object is missing");
    assertTrue(result.getObjectViolations().containsKey(new GID("strong")));
    assertEquals(
        "Strongly reachable object not found", result.getObjectViolations().get(new GID("strong")));
  }

  @Test
  void testValidateIncorrectReachability() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);

    // Add a soft reference instead of strong
    GID softRef = new GID("wrongRef");
    obtainedGraph.addReference(softRef, rootId, strongObj, GReference.Type.SOFT);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail when reachability is incorrect");
    assertTrue(result.getObjectViolations().containsKey(strongObj));
    assertTrue(result.getObjectViolations().get(strongObj).contains("Unexpected reachability"));
  }

  @Test
  void testValidateUnexpectedStronglyReachableObject() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID unexpectedStrongObj = new GID("unexpectedStrong");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(unexpectedStrongObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    GID unexpectedStrongRef = new GID("unexpectedStrongRef");

    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    obtainedGraph.addReference(
        unexpectedStrongRef, rootId, unexpectedStrongObj, GReference.Type.STRONG);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(
        result.isValid(),
        "Validation should fail when unexpected strongly reachable object is found");
    assertTrue(result.getObjectViolations().containsKey(unexpectedStrongObj));
    assertTrue(
        result
            .getObjectViolations()
            .get(unexpectedStrongObj)
            .contains("Unexpected strongly reachable object found"));
  }

  @Test
  void testValidateSoftlyReachableObjectCanBeCleared() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertTrue(
        result.isValid(), "Validation should succeed when softly reachable objects are cleared");
  }

  @Test
  void testValidateSoftlyReachableObjectCanBePresent() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID softObj = new GID("soft");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(softObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);

    GID softRef = new GID("softRef");
    obtainedGraph.addReference(softRef, rootId, softObj, GReference.Type.SOFT);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertTrue(
        result.isValid(), "Validation should succeed when softly reachable objects are cleared");
  }

  @Test
  void testValidateWeaklyReachableObjectMustBeCleared() {
    // Create a separate test oracle with only strongly reachable objects
    GGraph oracleForThisTest = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID weakObj = new GID("weak");

    oracleForThisTest.addObject(rootId, GObject.Type.GCROOT, 100);
    oracleForThisTest.addObject(strongObj, GObject.Type.ORDINARY, 50);
    oracleForThisTest.addObject(weakObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    GID weakRef = new GID("weakRef");

    oracleForThisTest.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    oracleForThisTest.addReference(weakRef, rootId, weakObj, GReference.Type.WEAK);

    JavaOracle testOracle = new JavaOracle(oracleForThisTest);

    // Create obtained graph that still has the weakly reachable object
    GGraph obtainedGraph = new GGraph();
    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(weakObj, GObject.Type.ORDINARY, 50); // Should not exist!

    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    // Add a weak reference that should not be present
    obtainedGraph.addReference(weakRef, rootId, weakObj, GReference.Type.WEAK);

    ValidationResult result = testOracle.validate(obtainedGraph);
    assertFalse(
        result.isValid(), "Validation should fail when weakly reachable object is not cleared");
    assertTrue(result.getObjectViolations().containsKey(weakObj));
    assertTrue(
        result
            .getObjectViolations()
            .get(weakObj)
            .contains("Unexpected weakly reachable object found"));
  }

  @Test
  void testValidateUnexpectedWeaklyReachableObject() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID unexpectedWeakObj = new GID("unexpectedWeak");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(unexpectedWeakObj, GObject.Type.ORDINARY, 50);

    GID strongRef = new GID("strongRef");
    GID weakRef = new GID("weakRef");

    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    obtainedGraph.addReference(weakRef, rootId, unexpectedWeakObj, GReference.Type.WEAK);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(
        result.isValid(),
        "Validation should fail when unexpected weakly reachable object is found");
    assertTrue(result.getObjectViolations().containsKey(unexpectedWeakObj));
    assertTrue(
        result
            .getObjectViolations()
            .get(unexpectedWeakObj)
            .contains("Unexpected weakly reachable object found"));
  }

  @Test
  void testValidateStrongToStrongReferenceMustExist() {
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");

    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    // Don't add the reference

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(
        result.isValid(), "Validation should fail when strong-to-strong reference is missing");
    assertNotNull(result.getReferenceViolations());
    assertFalse(result.getReferenceViolations().isEmpty());
  }

  @Test
  void testValidateWeakReferencesShouldNotExist() {
    // Create oracle with weak references that should be cleared
    GGraph oracleWithWeak = new GGraph();
    GID rootId = new GID("root");
    GID weakObj = new GID("weak");

    oracleWithWeak.addObject(rootId, GObject.Type.GCROOT, 100);
    oracleWithWeak.addObject(weakObj, GObject.Type.ORDINARY, 50);

    GID weakRef = new GID("weakRef");
    oracleWithWeak.addReference(weakRef, rootId, weakObj, GReference.Type.WEAK);

    JavaOracle weakOracle = new JavaOracle(oracleWithWeak);

    // Create obtained graph with the weak reference still present
    GGraph obtainedGraph = new GGraph();
    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(weakObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addReference(weakRef, rootId, weakObj, GReference.Type.WEAK);

    ValidationResult result = weakOracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail when weak references are not cleared");
    assertNotNull(result.getReferenceViolations());
  }

  @Test
  void testValidateComplexGraphWithMultipleViolations() {
    // Create a complete graph with all expected objects first
    GGraph obtainedGraph = new GGraph();
    GID rootId = new GID("root");
    GID strongObj = new GID("strong");
    GID softObj = new GID("soft");
    GID unexpectedRoot = new GID("unexpectedRoot");
    GID unexpectedStrong = new GID("unexpectedStrong");
    GID weakObj = new GID("weak");

    // Add the expected objects
    obtainedGraph.addObject(rootId, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(strongObj, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(softObj, GObject.Type.ORDINARY, 50);

    // Add unexpected objects
    obtainedGraph.addObject(unexpectedRoot, GObject.Type.GCROOT, 100);
    obtainedGraph.addObject(unexpectedStrong, GObject.Type.ORDINARY, 50);
    obtainedGraph.addObject(weakObj, GObject.Type.ORDINARY, 50); // Should be cleared

    // Add expected references
    GID strongRef = new GID("strongRef");
    GID softRef = new GID("softRef");
    obtainedGraph.addReference(strongRef, rootId, strongObj, GReference.Type.STRONG);
    obtainedGraph.addReference(softRef, rootId, softObj, GReference.Type.SOFT);

    // Add unexpected references
    GID unexpectedStrongRef = new GID("unexpectedStrongRef");
    obtainedGraph.addReference(
        unexpectedStrongRef, unexpectedRoot, unexpectedStrong, GReference.Type.STRONG);

    ValidationResult result = oracle.validate(obtainedGraph);
    assertFalse(result.isValid(), "Validation should fail with multiple violations");

    Map<GID, String> objViolations = result.getObjectViolations();
    assertTrue(objViolations.size() >= 2, "Should have multiple object violations");
    assertTrue(objViolations.containsKey(unexpectedRoot));
    assertTrue(objViolations.containsKey(unexpectedStrong));

    assertTrue(result.getFailureMessage().contains("object violations"));
  }

  @Test
  void testValidateEmptyObtainedGraph() {
    GGraph emptyGraph = new GGraph();

    ValidationResult result = oracle.validate(emptyGraph);
    assertFalse(result.isValid(), "Validation should fail for empty obtained graph");
    assertNotNull(result.getObjectViolations());
    assertTrue(result.getObjectViolations().containsKey(new GID("root")));
  }

  @Test
  void testValidateNullGraph() {
    assertThrows(
        NullPointerException.class,
        () -> {
          oracle.validate(null);
        },
        "Should throw NullPointerException for null graph");
  }
}
