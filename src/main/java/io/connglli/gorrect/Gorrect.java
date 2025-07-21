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

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.JavaInterpreter;
import io.connglli.gorrect.java.oracle.JavaVM;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.smith.GraphSmith;
import io.connglli.gorrect.smith.IdSmith;
import io.connglli.gorrect.smith.ProgramSmith;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.MemorySize;
import io.connglli.gorrect.utility.Rand;
import java.util.logging.Level;

public class Gorrect {
  public static void main(String[] args) {
    Logger.setLevel(Level.INFO);
    Rand.getInstance().setSeed(0);

    JavaVM jvm = new JavaVM(MemorySize.of(1, MemorySize.Unit.GB)); // 1GB heap

    IdSmith objectIdSmith = new IdSmith("obj");
    IdSmith referenceIdSmith = new IdSmith("ref");

    GraphSmith.Config graphConfig =
        GraphSmith.Config.builder()
            .vm(jvm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .minRoots(1)
            .maxRoots(3)
            .minNonRoots(2)
            .maxNonRoots(5)
            .minRefs(2)
            .maxRefs(8)
            .build();
    GraphSmith graphSmith = new GraphSmith(graphConfig);

    GGraph initState = graphSmith.craft();

    ProgramSmith.Config programConfig =
        ProgramSmith.Config.builder()
            .vm(jvm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .initState(initState)
            .minInstructions(1)
            .maxInstructions(32)
            .minObjSizeBytes(1)
            .maxObjSizeBytes(MemorySize.of(128, MemorySize.Unit.KB))
            .build();
    ProgramSmith programSmith = new ProgramSmith(programConfig);

    GProgram program = programSmith.craft();
    System.out.println(program);
    ProgramSmith.Stats progamGenStats = programSmith.getStats();
    System.out.println("Program generation stats: ");
    System.out.println(progamGenStats.formatToText());

    OracleInterpreter oracle = new OracleInterpreter(jvm, program);
    oracle.interpret();
    GGraph oracleState = oracle.getState();

    JavaInterpreter java = new JavaInterpreter(program);
    java.interpret();
    GGraph javaInterpState = java.getState();

    if (oracleState.equals(javaInterpState)) {
      System.out.println("Equals");
    } else {
      System.out.println("Not equals");
    }

    try {
      String hprof = java.executeJavaCode("/tmp");
      System.out.println("HPROF file generated at: " + hprof);
    } catch (Exception e) {
      System.out.println("FAILED to execute Java code: " + e.getMessage());
    }
  }
}
