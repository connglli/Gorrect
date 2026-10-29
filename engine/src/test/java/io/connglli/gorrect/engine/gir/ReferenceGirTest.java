package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.ReferencePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceGirTest {
  private static final String HEADER =
      "gir 1; shape N object 1 1; object 1 N [soft(1)] [0]; root r = 1; ";

  @Test
  void roundTripsReferenceOperationsAndPlansBothAcquisitionOutcomes() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  read-root owner N r;
                  write-weak owner 0 null;
                  write-soft owner 0 owner;
                  acquired: acquire held N owner 0 {
                    allocate fresh N;
                    write-ref held 0 fresh;
                  }
                  is-cleared state owner 0;
                  write-data owner 0 state;
                  clear-ref owner 0;
                  poll-queue;
                  release owner;
                  checkpoint;
                }
                """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertTrue(ReferenceProgram.usesReferences(program));
    var plan = ReferencePlan.plan(program, OracleLimits.DEFAULT);
    assertEquals(13, plan.events().size());
    assertTrue(plan.checkpoints().getFirst().graph().handles().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "acquire held N owner 0 { release held; }",
        "acquire held N owner 0 { checkpoint; }",
        "acquire held N owner 0 {} write-root r held;",
        "acquire held Missing owner 0 {}",
        "is-cleared state owner 1;",
        "clear-ref owner -1;",
        "write-soft owner 0 1;"
      })
  void rejectsInvalidReferenceContracts(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root owner N r; " + body + " }"));
  }
}
