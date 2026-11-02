package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.protocol.ControlMessage;
import org.junit.jupiter.api.Test;

class NativeRootStartupTest {
  @Test
  void onlySelectedNativeQualificationRejectionIsUnsupported() {
    String rejected = ControlMessage.NATIVE_ROOTS_UNSUPPORTED + "multiplicity unavailable";
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        TargetRunner.startupFailure(false, false, true, rejected).execution());
    assertEquals(
        RunReport.Outcome.STARTUP_FAILURE,
        TargetRunner.startupFailure(false, false, false, rejected).execution());
    for (String detail :
        new String[] {
          "java.lang.OutOfMemoryError: native acquisition failed",
          "java.lang.IllegalStateException: unexpected resource failure"
        })
      assertEquals(
          RunReport.Outcome.STARTUP_FAILURE,
          TargetRunner.startupFailure(false, false, true, detail).execution());
  }
}
