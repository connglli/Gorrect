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

package io.connglli.gorrect;

import static java.util.AbstractMap.SimpleEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.connglli.gorrect.graph.GObject;
import io.connglli.gorrect.graph.GReference;
import io.connglli.gorrect.graph.ObjectGraph;
import io.connglli.gorrect.utility.GAssert;
import io.connglli.gorrect.utility.GRandom;
import lombok.Builder;
import lombok.Getter;

/**
 * GraphGenerator generates a random ObjectGraph
 */
public class GraphGenerator {
    private final Config config;
    private final GRandom random;

    public GraphGenerator(Config config) {
        this.config = config;
        random = GRandom.getInstance();
    }

    public ObjectGraph generate() {
        ObjectGraph graph = new ObjectGraph();

        List<Integer> objIds = new ArrayList<>();
        int numRoots = random.nextInt(config.minRoots, config.maxRoots + 1);
        for (int i = 0; i < numRoots; i++) {
            objIds.add(graph.addObject(GObject.Type.GC_ROOT));
        }

        int numObjs = random.nextInt(config.minObjs, config.maxObjs + 1);
        for (int i = 0; i < numObjs; i++) {
            objIds.add(graph.addObject(GObject.Type.ORDINARY));
        }

        int numRefs = random.nextInt(config.minRefs, config.maxRefs + 1);
        numRefs = Math.min(numRefs, numObjs * (numObjs - 1)); // Prevent infinite loops

        Set<SimpleEntry<Integer, Integer>> existingRefs = new HashSet<>();

        for (int i = 0; i < numRefs; i++) {
            for (int attempts = 0; attempts < 100; attempts++) {
                int fromIdx = random.nextInt(objIds.size());
                int toIdx = random.nextInt(objIds.size());

                if (fromIdx == toIdx) {
                    continue;
                }

                int from = objIds.get(fromIdx);
                int to = objIds.get(toIdx);
                var refKey = new SimpleEntry<>(from, to);

                if (existingRefs.contains(refKey)) {
                    continue;
                }

                GReference.Type type = randomReferenceType();
                graph.addReference(from, to, type);
                existingRefs.add(refKey);

                break;
            }
        }

        enforceReachabilityFromRoots(graph);

        return graph;
    }

    private GReference.Type randomReferenceType() {
        GReference.Type[] types =
                {GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK};
        double[] probas = {config.strongRefProba, config.softRefProba, config.weakRefProba};
        return types[random.nextIndex(probas)];
    }

    private void enforceReachabilityFromRoots(ObjectGraph graph) {
        List<Integer> reachable = new ArrayList<>(graph.getReachableObjects());
        for (var toObj : graph.getObjects()) {
            int toId = toObj.getId();
            if (reachable.contains(toId)) {
                continue;
            }
            // Let's randomly select an object and add a reference to it
            int fromId = reachable.get(random.nextInt(0, reachable.size()));
            graph.addReference(fromId, toId, randomReferenceType());
            reachable.add(toId);
        }
        GAssert.set(reachable.size() == graph.getReachableObjects().size(),
                "Not all objects are reachable even though we have enforced reachability");
    }

    @Builder
    @Getter
    public static class Config {
        @Builder.Default
        private int minRoots = 1;

        @Builder.Default
        private int maxRoots = 10;

        @Builder.Default
        private int minObjs = 5;

        @Builder.Default
        private int maxObjs = 20;

        @Builder.Default
        private int minRefs = 5;

        @Builder.Default
        private int maxRefs = 30;

        @Builder.Default
        private double strongRefProba = 0.5;

        @Builder.Default
        private double softRefProba = 0.2;

        @Builder.Default
        private double weakRefProba = 0.3;
    }
}
