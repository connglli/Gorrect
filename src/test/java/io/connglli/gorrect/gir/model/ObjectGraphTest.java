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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObjectGraphTest {

  @Test
  void testAddAndRemoveObject() {
    ObjectGraph graph = new ObjectGraph();
    GID objId = new GID("obj1");
    graph.addObject(objId, GObject.Type.GCROOT, 100);

    assertTrue(graph.hasObject(objId), "Graph should contain the added object.");

    graph.removeObject(objId);
    assertFalse(graph.hasObject(objId), "Graph should not contain the removed object.");
  }

  @Test
  void testAddAndRemoveReference() {
    ObjectGraph graph = new ObjectGraph();
    GID obj1 = new GID("obj1");
    GID obj2 = new GID("obj2");
    graph.addObject(obj1, GObject.Type.GCROOT, 100);
    graph.addObject(obj2, GObject.Type.ORDINARY, 50);

    GID refId = new GID("ref1");
    graph.addReference(refId, obj1, obj2, GReference.Type.STRONG);

    assertTrue(graph.hasReference(refId), "Graph should contain the added reference.");

    graph.removeReference(refId);
    assertFalse(graph.hasReference(refId), "Graph should not contain the removed reference.");
  }

  @Test
  void testReachability() {
    ObjectGraph graph = new ObjectGraph();
    GID rootId = new GID("root");
    GID obj1 = new GID("obj1");
    GID obj2 = new GID("obj2");
    graph.addObject(rootId, GObject.Type.GCROOT, 100);
    graph.addObject(obj1, GObject.Type.ORDINARY, 50);
    graph.addObject(obj2, GObject.Type.ORDINARY, 50);

    GID ref1 = new GID("ref1");
    GID ref2 = new GID("ref2");
    graph.addReference(ref1, rootId, obj1, GReference.Type.STRONG);
    graph.addReference(ref2, obj1, obj2, GReference.Type.STRONG);

    Set<GID> reachable = graph.getStronglyReachableObjects();
    assertTrue(reachable.contains(rootId), "Root should be strongly reachable.");
    assertTrue(reachable.contains(obj1), "Object1 should be strongly reachable.");
    assertTrue(reachable.contains(obj2), "Object2 should be strongly reachable.");
  }

  @Test
  void testRemoveUnreachableObjects() {
    ObjectGraph graph = new ObjectGraph();
    GID rootId = new GID("root");
    GID obj1 = new GID("obj1");
    GID obj2 = new GID("obj2");
    graph.addObject(rootId, GObject.Type.GCROOT, 100);
    graph.addObject(obj1, GObject.Type.ORDINARY, 50);
    graph.addObject(obj2, GObject.Type.ORDINARY, 50);

    GID ref1 = new GID("ref1");
    graph.addReference(ref1, rootId, obj1, GReference.Type.STRONG);

    graph.removeUnreachableObjects();

    assertTrue(graph.hasObject(rootId), "Root should not be removed.");
    assertTrue(graph.hasObject(obj1), "Object1 should not be removed.");
    assertFalse(graph.hasObject(obj2), "Object2 should be removed as unreachable.");
  }

  @Test
  void testEdgeCases() {
    ObjectGraph graph = new ObjectGraph();
    GID objId = new GID("obj1");
    GID refId = new GID("ref1");

    Throwable exception =
        assertThrows(
            AssertionError.class,
            () -> {
              graph.removeObject(objId);
            });
    assertTrue(
        exception.getMessage().contains("does not exist"),
        "Should throw error for non-existent object.");

    exception =
        assertThrows(
            AssertionError.class,
            () -> {
              graph.removeReference(refId);
            });
    assertTrue(
        exception.getMessage().contains("does not exist"),
        "Should throw error for non-existent reference.");
  }

  @Test
  void testComplexGraphStructure() {
    ObjectGraph graph = new ObjectGraph();

    GID rootId = new GID("root");
    GID obj1 = new GID("obj1");
    GID obj2 = new GID("obj2");
    GID obj3 = new GID("obj3");
    GID obj4 = new GID("obj4");

    graph.addObject(rootId, GObject.Type.GCROOT, 100);
    graph.addObject(obj1, GObject.Type.ORDINARY, 50);
    graph.addObject(obj2, GObject.Type.ORDINARY, 50);
    graph.addObject(obj3, GObject.Type.ORDINARY, 50);
    graph.addObject(obj4, GObject.Type.ORDINARY, 50);

    GID ref1 = new GID("ref1");
    GID ref2 = new GID("ref2");
    GID ref3 = new GID("ref3");
    GID ref4 = new GID("ref4");
    GID ref5 = new GID("ref5");

    graph.addReference(ref1, rootId, obj1, GReference.Type.STRONG);
    graph.addReference(ref2, obj1, obj2, GReference.Type.SOFT);
    graph.addReference(ref3, obj2, obj3, GReference.Type.WEAK);
    graph.addReference(ref4, obj3, obj4, GReference.Type.STRONG);
    graph.addReference(ref5, obj4, obj1, GReference.Type.WEAK);

    Set<GID> stronglyReachable = graph.getStronglyReachableObjects();
    assertTrue(stronglyReachable.contains(rootId), "Root should be strongly reachable.");
    assertTrue(stronglyReachable.contains(obj1), "Object1 should be strongly reachable.");
    assertFalse(stronglyReachable.contains(obj4), "Object4 should not be strongly reachable.");

    Set<GID> softlyReachable = graph.getSoftlyReachableObjects();
    assertTrue(softlyReachable.contains(obj2), "Object2 should be softly reachable.");

    Set<GID> weaklyReachable = graph.getWeaklyReachableObjects();
    assertTrue(weaklyReachable.contains(obj3), "Object3 should be weakly reachable.");
    assertTrue(weaklyReachable.contains(obj4), "Object4 should be weakly reachable.");
  }

  @Test
  void testLargeComplexGraph() {
    ObjectGraph graph = new ObjectGraph();

    // Create 15 objects
    List<GID> objects = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      GID objId = new GID("obj" + i);
      objects.add(objId);
      graph.addObject(objId, GObject.Type.ORDINARY, i * 10);
    }

    // Set the first object as GCROOT
    graph.getObject(objects.getFirst()).setType(GObject.Type.GCROOT);

    // Create at most 35 unique references (no self-edges, no multi-edges)
    List<GID> references = new ArrayList<>();
    int count = 0;
    outer:
    for (int fromIndex = 0; fromIndex < 15; fromIndex++) {
      for (int toIndex = fromIndex + 1; toIndex < 15; toIndex++) {
        GID refId = new GID("ref-" + (count + 1) + "-" + fromIndex + "_" + toIndex);
        references.add(refId);
        graph.addReference(
            refId, objects.get(fromIndex), objects.get(toIndex), GReference.Type.STRONG);
        count++;
        if (count >= 80) break outer;
      }
    }

    // Validate reachability
    Set<GID> stronglyReachable = graph.getStronglyReachableObjects();
    for (GID obj : objects) {
      assertTrue(stronglyReachable.contains(obj), obj + " should be strongly reachable.");
    }

    // Remove some references and validate reachability again
    for (int i = 1; i <= 10; i++) {
      final GID refererId = objects.get(i);
      List<GObject> referents = graph.getReferents(refererId);
      for (GObject referent : referents) {
        graph.removeReference(refererId, referent.getId());
      }
      graph.removeReference(graph.getReference(objects.getFirst(), refererId).getId());
    }

    // Change the references to soft ones for odd objects
    for (int i = 11; i < 14; i++) {
      if (i % 2 == 1) {
        final GID refererId = objects.getFirst();
        final GID referentId = objects.get(i);
        graph.getReference(refererId, referentId).setType(GReference.Type.SOFT);
      }
    }
    // Add a weak reference to the last object from the last but one object
    graph.addReference(new GID("ref-x"), objects.get(13), objects.getLast(), GReference.Type.WEAK);
    // Remove the reference from the first object to the last object
    graph.removeReference(objects.getFirst(), objects.getLast());

    // Validate reachability after modifications
    stronglyReachable = graph.getStronglyReachableObjects();
    Set<GID> softlyReachable = graph.getSoftlyReachableObjects();
    Set<GID> weaklyReachable = graph.getWeaklyReachableObjects();
    assertTrue(
        stronglyReachable.contains(objects.getFirst()), "Root should still be strongly reachable.");
    assertEquals(2, stronglyReachable.size(), "Only tw objects should be strongly reachable.");
    assertEquals(1, weaklyReachable.size(), "Only one object should be weakly reachable.");
    assertEquals(2, softlyReachable.size(), "Only two objects should be softly reachable.");
    ;
    for (int i = 1; i < 15; i++) {
      if (i == 14) {
        assertTrue(
            weaklyReachable.contains(objects.get(i)),
            "Object " + objects.get(i) + " should be weakly reachable.");
      } else if (i <= 10 || i % 2 == 1) {
        assertFalse(
            stronglyReachable.contains(objects.get(i)),
            objects.get(i) + " should no longer be reachable.");
        if (i > 10) {
          assertTrue(
              softlyReachable.contains(objects.get(i)),
              objects.get(i) + " should be softly reachable.");
        } else {
          assertFalse(
              softlyReachable.contains(objects.get(i)),
              objects.get(i) + " should not be softly reachable.");
        }
      } else {
        assertTrue(
            stronglyReachable.contains(objects.get(i)),
            objects.get(i) + " should still be reachable.");
      }
    }
  }
}
