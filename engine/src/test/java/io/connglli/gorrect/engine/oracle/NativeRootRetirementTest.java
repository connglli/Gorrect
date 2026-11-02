package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.connglli.gorrect.engine.gir.GirParser;
import org.junit.jupiter.api.Test;

class NativeRootRetirementTest {
  @Test
  void nativeRecoveryKeepsRetirementIneligibleWithoutRejectingExecution() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; root saved jni-global=null;
            program { scope { allocate n N; write-root saved n; } checkpoint;
              scope { read-root recovered N saved; write-root saved null; checkpoint;
                write-root saved recovered; } checkpoint; }
            """);
    assertFalse(Oracle.retirementEligible(program));
    assertEquals(Oracle.interpret(program, OracleLimits.DEFAULT, false), Oracle.interpret(program));
  }
}
