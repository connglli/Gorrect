package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GirTraversalTest {
  @Test
  void visitsEveryBranchAndZeroIterationBodyInWorkerDeclarationOrder() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0; object 1 N [weak(1)] []; root main = 1;
            program {
              outer: scope {
                owner: read-root owner N main;
                zero: repeat 0 { skipped: allocate skipped N; }
                acquired: acquire held N owner 0 {
                  group: parallel {
                    worker first {
                      read: read-root current N main;
                      branch: if-present current { gc: collect; }
                    }
                    worker second { nested: scope { right: allocate right N; } }
                  }
                }
              }
            }
            """);
    var expected =
        List.of(
            "outer",
            "owner",
            "zero",
            "skipped",
            "acquired",
            "group",
            "read",
            "branch",
            "gc",
            "nested",
            "right");
    assertEquals(
        expected,
        program.instructions().map(instruction -> program.labels().get(instruction.id())).toList());
    assertTrue(ReferenceProgram.usesReferences(program));
    assertTrue(ReferenceProgram.hasParallel(program));
    assertTrue(HistoryProgram.usesConcurrentHistory(program));
    // Capability queries must not consume or change a later traversal.
    assertEquals(
        expected,
        program.instructions().map(instruction -> program.labels().get(instruction.id())).toList());
  }

  @Test
  void traversesTheMaximumSupportedNestingWithoutExecutingAnything() {
    int depth = GirValidator.MAX_NESTING;
    List<Instruction> body = List.of(new Instruction.Collect(depth + 1));
    for (int index = depth; index > 0; index--) body = List.of(new Instruction.Scope(index, body));
    var program = new GirProgram(1, List.of(), List.of(), List.of(), body);
    GirValidator.validate(program);
    assertEquals(
        IntStream.rangeClosed(1, depth + 1).boxed().toList(),
        program.instructions().map(Instruction::id).toList());
    assertFalse(ReferenceProgram.usesReferences(program));
    assertFalse(ReferenceProgram.hasParallel(program));
  }
}
