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

                if (fromIdx == toIdx && !config.allowSelfLoops) {
                    continue;
                }

                int from = objIds.get(fromIdx);
                int to = objIds.get(toIdx);
                var refKey = new SimpleEntry<>(from, to);

                if (!config.allowMultipleRefs && existingRefs.contains(refKey)) {
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
            int toId = toObj.id;
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

    public static class Config {
        public final int minRoots;
        public final int maxRoots;
        public final int minObjs;
        public final int maxObjs;
        public final int minRefs;
        public final int maxRefs;
        public final double strongRefProba;
        public final double softRefProba;
        public final double weakRefProba;
        public final boolean allowSelfLoops;
        public final boolean allowMultipleRefs;

        private Config(Builder builder) {
            this.minRoots = builder.minRoots;
            this.maxRoots = builder.maxRoots;
            this.minObjs = builder.minObjs;
            this.maxObjs = builder.maxObjs;
            this.minRefs = builder.minRefs;
            this.maxRefs = builder.maxRefs;
            this.strongRefProba = builder.strongRefProba;
            this.softRefProba = builder.softRefProba;
            this.weakRefProba = builder.weakRefProba;
            this.allowSelfLoops = builder.allowSelfLoops;
            this.allowMultipleRefs = builder.allowMultipleRefs;

            // Validate probabilities sum to 1.0
            double sum = strongRefProba + softRefProba + weakRefProba;
            GAssert.set(Math.abs(sum - 1.0) < 0.001,
                    "Reference type probabilities must sum to 1.0, but we got " + sum);

        }

        public static class Builder {
            private int minRoots = 1;
            private int maxRoots = 10;
            private int minObjs = 5;
            private int maxObjs = 20;
            private int minRefs = 5;
            private int maxRefs = 30;
            private double strongRefProba = 0.5;
            private double softRefProba = 0.2;
            private double weakRefProba = 0.3;
            private boolean allowSelfLoops = false;
            private boolean allowMultipleRefs = false;

            public Builder minRoots(int minRoots) {
                this.minRoots = minRoots;
                return this;
            }

            public Builder maxRoots(int maxRoots) {
                this.maxRoots = maxRoots;
                return this;
            }

            public Builder minObjs(int minObjs) {
                this.minObjs = minObjs;
                return this;
            }

            public Builder maxObjs(int maxObjs) {
                this.maxObjs = maxObjs;
                return this;
            }

            public Builder minRefs(int minRefs) {
                this.minRefs = minRefs;
                return this;
            }

            public Builder maxRefs(int maxRefs) {
                this.maxRefs = maxRefs;
                return this;
            }

            public Builder strongRefProba(double prob) {
                this.strongRefProba = prob;
                return this;
            }

            public Builder softRefProba(double prob) {
                this.softRefProba = prob;
                return this;
            }

            public Builder weakRefProba(double prob) {
                this.weakRefProba = prob;
                return this;
            }

            public Builder allowSelfLoops(boolean allow) {
                // noinspection DataFlowIssue
                GAssert.set(false, "Set is currently not supported");
                allowSelfLoops = allow;
                return this;
            }

            public Builder allowMultipleRefs(boolean allow) {
                // noinspection DataFlowIssue
                GAssert.set(false, "Set allowMultipleRefs is currently not supported");
                allowMultipleRefs = allow;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }
}
