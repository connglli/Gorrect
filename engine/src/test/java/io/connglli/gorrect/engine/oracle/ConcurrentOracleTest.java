package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrentOracleTest {
  @Test
  void workerAllocationsHaveDistinctIdentitiesAndJoinBeforeCheckpoint() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                """
                gir 1; shape N object 0 0; root left = null; root right = null;
                program { parallel {
                  worker alpha { allocate n N; write-root left n; }
                  worker beta { allocate n N; write-root right n; }
                } checkpoint; }
                """));
    assertEquals(new ObjectId(1, 2, 1), result.finalGraph().roots().get("left").target());
    assertEquals(new ObjectId(2, 4, 1), result.finalGraph().roots().get("right").target());
    assertTrue(result.checkpoints().getFirst().graph().handles().isEmpty());
    assertEquals(result.steps(), result.events().size());
    assertEquals(
        List.of(1L, 2L),
        result.events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.JOIN)
            .map(ExecutionTrace.Event::value)
            .toList());
  }

  @Test
  void barriersPermitOwnershipTransferAndKeepWorkerLocalsAlive() {
    var result =
        Oracle.interpret(
            GirParser.parse(
                """
                gir 1; shape N object 0 1; root shared = null;
                program { parallel {
                  worker producer { allocate n N; write-root shared n; barrier; release n; }
                  worker consumer { barrier; read-root held N shared; write-root shared null; collect; write-data held 0 19; write-root shared held; }
                } checkpoint; }
                """));
    var id = new ObjectId(1, 2, 1);
    assertEquals(List.of(19L), result.finalGraph().objects().get(id).data());
    assertEquals(id, result.finalGraph().roots().get("shared").target());
    assertEquals(
        List.of(0, 1),
        result.events().stream()
            .filter(event -> event.worker() == 2)
            .map(ExecutionTrace.Event::phase)
            .distinct()
            .toList());
  }

  @Test
  void exhaustiveTwoSlotAccessesAcceptOnlyConflictFreePhases() {
    for (int left = 0; left < 2; left++)
      for (int right = 0; right < 2; right++)
        for (int modes = 0; modes < 4; modes++) {
          boolean writeLeft = (modes & 1) != 0, writeRight = (modes & 2) != 0;
          String a =
              writeLeft ? "write-data n " + left + " 11;" : "read-data value n " + left + ";";
          String b =
              writeRight ? "write-data n " + right + " 22;" : "read-data value n " + right + ";";
          var program =
              GirParser.parse(
                  "gir 1; shape N object 0 2; object 1 N [] [0,0]; root a = 1; root b = 1; program"
                      + " { parallel { worker left { read-root n N a; "
                      + a
                      + " } worker right { read-root n N b; "
                      + b
                      + " } } }");
          if (left == right && (writeLeft || writeRight))
            assertThrows(GirException.class, () -> Oracle.interpret(program));
          else {
            var result = Oracle.interpret(program);
            var expected = new java.util.ArrayList<>(List.of(0L, 0L));
            if (writeLeft) expected.set(left, 11L);
            if (writeRight) expected.set(right, 22L);
            assertEquals(expected, result.finalGraph().objects().get(ObjectId.initial(1)).data());
            assertTrue(
                result.events().stream()
                    .filter(event -> event.kind() == ExecutionTrace.Kind.SCALAR_READ)
                    .allMatch(event -> event.value() == 0));
          }
        }
  }

  @Test
  void rejectsPointerRacesEvenWhenTargetSlotsDiffer() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1;
            object 1 N [2] [0]; object 2 N [null] [1]; object 3 N [null] [2];
            root r = 1; root other = 3;
            program { parallel {
              worker writer { read-root n N r; read-root target N other; write-ref n 0 target; }
              worker reader { read-root n N r; read-ref target N n 0; write-data target 0 9; }
            } }
            """);
    assertThrows(GirException.class, () -> Oracle.interpret(program));
  }
}
