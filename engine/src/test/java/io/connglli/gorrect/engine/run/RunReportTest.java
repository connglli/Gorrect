package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RunReportTest {
  @Test
  void laterViolationsOutrankEarlierCoverageGaps() {
    assertEquals(1, RunReport.combineExitStatus(3, 1));
    assertEquals(1, RunReport.combineExitStatus(5, 1));
    assertEquals(1, RunReport.combineExitStatus(1, 4));
    assertEquals(3, RunReport.combineExitStatus(0, 3));
    assertEquals(4, RunReport.combineExitStatus(4, 0));
  }
}
