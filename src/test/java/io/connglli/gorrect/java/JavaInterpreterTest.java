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

package io.connglli.gorrect.java;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.model.ObjectGraph;
import io.connglli.gorrect.utility.Logger;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

public class JavaInterpreterTest {
  static {
    Logger.setLevel(Level.INFO);
    Logger.enableHeadless();
  }

  @Test
  void testSimpleGraph() {
    // Use JavaVM and initialize ObjectGraph
    ObjectGraph initState = new ObjectGraph();

    // Initialize GProgram
    GSetHeapInstruction setHeap =
        new GSetHeapInstruction(4L * 1024 * 1024 * 1024, initState); // 4GB
    GProgram program = new GProgram(setHeap);

    // Add a root object of size 30MB
    GID rootId = new GID("r1");
    GAddRootObjInstruction addRootObj = new GAddRootObjInstruction(30L * 1024 * 1024, rootId);
    program.addInstruction(addRootObj);

    // Execute the program
    JavaInterpreter interpreter = new JavaInterpreter(program);
    interpreter.interpret();

    ObjectGraph finalState = interpreter.getState();

    // Validate the resulting object graph
    assertTrue(finalState.getObjects().stream().anyMatch(obj -> obj.getId().equals(rootId)));
  }

  @Test
  void testComplexGraph() {
    ObjectGraph initState = new ObjectGraph();

    // Initialize GProgram
    GSetHeapInstruction setHeap =
        new GSetHeapInstruction(4L * 1024 * 1024 * 1024, initState); // 4GB
    GProgram program = new GProgram(setHeap);

    // Add objects and references
    GID r1 = new GID("r1");
    GID r2 = new GID("r2");
    GID o1 = new GID("o1");

    GID e_r1_o1 = new GID("e_r1_o1");
    GID e_o1_r1 = new GID("e_o1_r1");

    program.addInstruction(new GAddRootObjInstruction(30L * 1024 * 1024, r1));
    program.addInstruction(
        new GAddNonRootObjInstruction(300L * 1024 * 1024, r1, GReference.Type.WEAK, o1, e_r1_o1));
    program.addInstruction(new GAddWeakRefInstruction(o1, r1, e_o1_r1));
    program.addInstruction(new GAddRootObjInstruction(1024L, r2));
    program.addInstruction(new GRemoveObjInstruction(r2));

    // Execute the program
    JavaInterpreter interpreter = new JavaInterpreter(program);
    interpreter.interpret();

    ObjectGraph finalState = interpreter.getState();

    // Validate the resulting object graph
    Set<GID> stronglyReachable = finalState.getStronglyReachableObjects();
    Set<GID> weaklyReachable = finalState.getWeaklyReachableObjects();
    assertTrue(stronglyReachable.contains(r1));
    assertTrue(weaklyReachable.contains(o1));
    assertFalse(stronglyReachable.contains(r2));
    assertFalse(weaklyReachable.contains(r2));
  }

  @Test
  void testCyclicGraph() {
    ObjectGraph initState = new ObjectGraph();

    // Initialize GProgram
    GSetHeapInstruction setHeap =
        new GSetHeapInstruction(4L * 1024 * 1024 * 1024, initState); // 4GB
    GProgram program = new GProgram(setHeap);

    // Add objects and cyclic references
    GID r1 = new GID("r1");
    GID o1 = new GID("o1");
    GID o2 = new GID("o2");

    GID e_r1_o1 = new GID("e_r1_o1");
    GID e_o1_o2 = new GID("e_o1_o2");
    GID e_o2_o1 = new GID("e_o2_o1");

    program.addInstruction(new GAddRootObjInstruction(30L * 1024 * 1024, r1));
    program.addInstruction(
        new GAddNonRootObjInstruction(300L * 1024 * 1024, r1, GReference.Type.STRONG, o1, e_r1_o1));
    program.addInstruction(
        new GAddNonRootObjInstruction(300L * 1024 * 1024, o1, GReference.Type.STRONG, o2, e_o1_o2));
    program.addInstruction(new GAddStrongRefInstruction(o2, o1, e_o2_o1));

    // Execute the program
    JavaInterpreter interpreter = new JavaInterpreter(program);
    interpreter.interpret();

    ObjectGraph finalState = interpreter.getState();

    // Validate the resulting object graph
    Set<GID> stronglyReachable = finalState.getStronglyReachableObjects();
    assertTrue(stronglyReachable.contains(r1));
    assertTrue(stronglyReachable.contains(o1));
    assertTrue(stronglyReachable.contains(o2));
  }

  @Test
  void testSoftReferences() {
    ObjectGraph initState = new ObjectGraph();

    // Initialize GProgram
    GSetHeapInstruction setHeap =
        new GSetHeapInstruction(4L * 1024 * 1024 * 1024, initState); // 4GB
    GProgram program = new GProgram(setHeap);

    // Add objects and soft references
    GID r1 = new GID("r1");
    GID o1 = new GID("o1");

    GID e_r1_o1 = new GID("e_r1_o1");

    program.addInstruction(new GAddRootObjInstruction(30L * 1024 * 1024, r1));
    program.addInstruction(
        new GAddNonRootObjInstruction(300L * 1024 * 1024, r1, GReference.Type.SOFT, o1, e_r1_o1));

    // Execute the program
    JavaInterpreter interpreter = new JavaInterpreter(program);
    interpreter.interpret();

    ObjectGraph finalState = interpreter.getState();

    // Validate the resulting object graph
    Set<GID> stronglyReachable = finalState.getStronglyReachableObjects();
    Set<GID> softlyReachable = finalState.getSoftlyReachableObjects();
    assertTrue(stronglyReachable.contains(r1));
    assertTrue(softlyReachable.contains(o1));
  }

  @Test
  void testAllInstructions() {
    ObjectGraph initState = new ObjectGraph();

    // Initialize GProgram
    GSetHeapInstruction setHeap =
        new GSetHeapInstruction(4L * 1024 * 1024 * 1024, initState); // 4GB
    GProgram program = new GProgram(setHeap);

    // Add root object
    GID r1 = new GID("r1");
    program.addInstruction(new GAddRootObjInstruction(30L * 1024 * 1024, r1));

    // Add non-root object
    GID o1 = new GID("o1");
    GID ref_r1_o1 = new GID("ref-r1_o1");
    program.addInstruction(
        new GAddNonRootObjInstruction(
            10L * 1024 * 1024, r1, GReference.Type.STRONG, o1, ref_r1_o1));

    // Add strong reference
    GID ref_o1_r1 = new GID("ref-o1_r1");
    program.addInstruction(new GAddStrongRefInstruction(o1, r1, ref_o1_r1));

    // Soften reference
    program.addInstruction(new GSoftenRefInstruction(ref_r1_o1));

    // Add a new referent
    GID o2 = new GID("o2");
    GID ref_o1_o2 = new GID("ref-o1_o2");
    program.addInstruction(
        new GAddNonRootObjInstruction(1024L, o1, GReference.Type.WEAK, o2, ref_o1_o2));

    // Replace referent, o1 get unreachable
    program.addInstruction(new GReplaceReferentInstruction(ref_r1_o1, o2));

    // Add fanout references
    GID r2 = new GID("r2");
    program.addInstruction(new GAddRootObjInstruction(10 * 1024 * 1024L, r2));

    List<GID> fanoutReferentIds = List.of(o2, r1);
    List<GID> fanoutReferenceIds = List.of(new GID("ref-r2_o2"), new GID("ref-r2_r1"));
    List<GReference.Type> fanoutReferenceTypes =
        List.of(GReference.Type.STRONG, GReference.Type.WEAK);
    program.addInstruction(
        new GAddFanoutRefsInstruction(
            r2, fanoutReferentIds, fanoutReferenceTypes, fanoutReferenceIds));

    // Add fanin references
    List<GID> faninRefererIds = List.of(r1, o2);
    List<GReference.Type> faninReferenceTypes =
        List.of(GReference.Type.STRONG, GReference.Type.SOFT);
    List<GID> faninReferenceIds = List.of(new GID("ref-r1_r2"), new GID("ref-o2_r2"));
    program.addInstruction(
        new GAddFaninRefsInstruction(faninRefererIds, r2, faninReferenceTypes, faninReferenceIds));

    // Clone object
    GID o2c = new GID("o2c");
    program.addInstruction(new GCloneObjInstruction(o2, o2c));

    // Execute the program
    JavaInterpreter interpreter = new JavaInterpreter(program);
    interpreter.interpret();

    ObjectGraph intermediateState = interpreter.getState();
    assertFalse(intermediateState.hasObject(o1), "Expected o1 to be unreachable");
    assertTrue(intermediateState.hasReference(r1, r2), "Expected strong reference from r1 to r2");
    assertTrue(intermediateState.hasReference(r1, o2), "Expected strong reference from r1 to o2");
    assertTrue(intermediateState.hasReference(r1, o2c), "Expected strong reference from r1 to o2c");
    assertTrue(intermediateState.hasReference(r2, r1), "Expected strong reference from r2 to r1");
    assertTrue(intermediateState.hasReference(r2, o2), "Expected strong reference from r2 to o2");
    assertTrue(intermediateState.hasReference(r2, o2c), "Expected strong reference from r2 to o2c");
    assertTrue(intermediateState.hasReference(o2, r2), "Expected strong reference from o2 to r2");
    assertTrue(intermediateState.hasReference(o2c, r2), "Expected strong reference from o2c to r2");

    Set<GID> stronglyReachable = intermediateState.getStronglyReachableObjects();
    assertEquals(4, stronglyReachable.size(), "All objects remained should  be strongly reachable");
    assertTrue(stronglyReachable.contains(r1), "r1 should be strongly reachable");
    assertTrue(stronglyReachable.contains(r2), "r2 should be strongly reachable");
    assertTrue(stronglyReachable.contains(o2), "o2 should be strongly reachable");
    assertTrue(stronglyReachable.contains(o2c), "o2c should be strongly reachable");

    GProgram newProgram =
        new GProgram(new GSetHeapInstruction(40L * 1024 * 1024 * 1024, intermediateState));

    // Remove object
    newProgram.addInstruction(new GRemoveObjInstruction(o2c));

    // Remove reference
    newProgram.addInstruction(new GRemoveRefInstruction(new GID("ref-r2_o2")));

    JavaInterpreter newInterpreter = new JavaInterpreter(newProgram);
    newInterpreter.interpret();

    ObjectGraph finalState = newInterpreter.getState();
    stronglyReachable = finalState.getStronglyReachableObjects();
    Set<GID> softlyReachable = finalState.getSoftlyReachableObjects();
    Set<GID> weaklyReachable = finalState.getWeaklyReachableObjects();

    // Validate the resulting object graph
    assertEquals(3, finalState.getObjects().size(), "Expected 3 objects in final graph");
    assertEquals(
        2,
        stronglyReachable.size(),
        "Expected 2 strongly reachable objects, i.e., the two root objects");
    assertEquals(1, softlyReachable.size(), "Expected 1 softly reachable object, i.e., o2");
    assertEquals(0, weaklyReachable.size(), "Expected no weakly reachable objects");
    assertTrue(stronglyReachable.contains(r1), "r1 should be strongly reachable");
    assertTrue(stronglyReachable.contains(r2), "r2 should be strongly reachable");
    assertTrue(softlyReachable.contains(o2), "o2 should be softly reachable");
  }
}
