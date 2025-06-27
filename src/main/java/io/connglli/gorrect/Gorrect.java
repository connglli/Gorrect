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

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.connglli.gorrect.graph.GObject;
import io.connglli.gorrect.graph.GReference;
import io.connglli.gorrect.graph.ObjectGraph;
import io.connglli.gorrect.graph.Operation;
import io.connglli.gorrect.graph.ops.AddRefOperation;
import io.connglli.gorrect.graph.ops.RemoveObjOperation;
import io.connglli.gorrect.graph.ops.RemoveRefOperation;
import io.connglli.gorrect.utility.GRandom;

public class Gorrect {
    public static void main(String[] args) {
        GRandom.getInstance().setSeed(1);

        GraphGenerator.Config config = GraphGenerator.Config.builder().minRoots(2).maxRoots(2)
                .minObjs(3).maxObjs(8).minRefs(10).maxRefs(15).build();

        GraphGenerator generator = new GraphGenerator(config);
        ObjectGraph graph = generator.generate();

        System.out.println("#Roots: " + graph.getRoots().size());
        for (GObject obj : graph.getRoots()) {
            System.out.println("  " + obj);
        }

        System.out.println("#NonRoots: " + graph.getNonRoots().size());
        for (GObject obj : graph.getNonRoots()) {
            System.out.println("  " + obj);
        }

        System.out.println("#Refs: " + graph.getReferences().size());
        for (GReference ref : graph.getReferences()) {
            System.out.println("  " + ref);
        }

        System.out.println("Table: ");
        Map<Integer, Set<Integer>> table = graph.getAdjacentTable();
        for (int source : graph.getAdjacentTable().keySet()) {
            System.out.println("  " + source + ": "
                    + table.get(source).stream()
                            .map(t -> t + ":" + graph.getReference(source, t).getType().name())
                            .collect(Collectors.joining(", ")));
        }

        Operation op1 = new RemoveRefOperation(1);
        Operation op2 = new AddRefOperation(0, 3, GReference.Type.WEAK);

        graph.apply(op1).apply(op2).apply(new RemoveObjOperation(3));

        System.out.println("#Roots: " + graph.getRoots().size());
        for (GObject obj : graph.getRoots()) {
            System.out.println("  " + obj);
        }

        System.out.println("#NonRoots: " + graph.getNonRoots().size());
        for (GObject obj : graph.getNonRoots()) {
            System.out.println("  " + obj);
        }

        System.out.println("#Refs: " + graph.getReferences().size());
        for (GReference ref : graph.getReferences()) {
            System.out.println("  " + ref);
        }

        System.out.println("Table: ");
        table = graph.getAdjacentTable();
        for (int source : graph.getAdjacentTable().keySet()) {
            System.out.println("  " + source + ": "
                    + table.get(source).stream()
                            .map(t -> t + ":" + graph.getReference(source, t).getType().name())
                            .collect(Collectors.joining(", ")));
        }
    }
}
