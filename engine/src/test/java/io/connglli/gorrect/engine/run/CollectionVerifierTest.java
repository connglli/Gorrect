package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.protocol.CollectionEvidence;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CollectionVerifierTest {
  @Test
  void workerOrderForbidsOverlappingSpansWithoutUsingFileOrder() {
    var first = request("system", 10, 20);
    var second =
        new CollectionEvidence.Request(0, 2, 0, 0, 0, 2, 1, "system", true, time(20), time(30));
    assertEquals(
        java.util.List.of(first, second),
        CollectionVerifier.ordered(java.util.List.of(second, first)));
    var overlap =
        new CollectionEvidence.Request(0, 2, 0, 0, 0, 2, 1, "system", true, time(19), time(30));
    assertThrows(
        IllegalArgumentException.class,
        () -> CollectionVerifier.ordered(java.util.List.of(first, overlap)));
  }

  private static Instant time(long tick) {
    return Instant.ofEpochSecond(tick);
  }

  private static CollectionEvidence.Cycle cycle(String name, String cause, long begin, long end) {
    return new CollectionEvidence.Cycle(1, name, cause, time(begin), time(end));
  }

  private static CollectionEvidence.Request request(String control, long begin, long end) {
    return new CollectionEvidence.Request(
        0, 1, 0, 0, 0, 1, 1, control, true, time(begin), time(end));
  }

  @Test
  void requiresAWholeCycleWithTheRequestedCause() {
    var request = request("system", 10, 100);
    assertTrue(
        CollectionVerifier.completedWithin(
            request, cycle("SerialOld", "System.gc()", 10, 100), "serial"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("SerialOld", "System.gc()", 9, 90), "serial"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("SerialOld", "System.gc()", 11, 101), "serial"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("SerialOld", "Allocation Failure", 20, 90), "serial"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("DefNew", "System.gc()", 20, 90), "serial"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request("whitebox-full", 10, 100),
            cycle("SerialOld", "System.gc()", 20, 90),
            "serial"));
    assertTrue(
        CollectionVerifier.completedWithin(
            request("whitebox-full", 10, 100),
            cycle("SerialOld", "WhiteBox Initiated Full GC", 20, 90),
            "serial"));
  }

  @Test
  void distinguishesMajorCyclesFromMinorAndPauseEvents() {
    assertTrue(CollectionVerifier.qualifies("g1", "G1Old"));
    assertTrue(CollectionVerifier.qualifies("g1", "G1Full"));
    assertFalse(CollectionVerifier.qualifies("g1", "G1New"));
    assertTrue(CollectionVerifier.qualifies("zgc", "ZGC Major"));
    assertTrue(CollectionVerifier.qualifies("zgc", "Z"));
    assertFalse(CollectionVerifier.qualifies("zgc", "ZGC Minor"));
    assertTrue(CollectionVerifier.qualifies("shenandoah", "Shenandoah"));
    assertFalse(CollectionVerifier.qualifies("shenandoah", "Shenandoah Pauses"));
  }

  @Test
  void modeSpecificCyclesCannotSubstituteForEachOther() {
    var request = request("system", 10, 100);
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("Z", "System.gc()", 20, 90), "zgc", "generational"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle("ZGC Major", "System.gc()", 20, 90), "zgc", "non-generational"));
    assertTrue(
        CollectionVerifier.completedWithin(
            request, cycle("ZGC Major", "System.gc()", 20, 90), "zgc", "generational"));
  }

  @Test
  void coalescedRequestsCanShareAContainedCycle() {
    var cycle = cycle("Z", "System.gc()", 30, 60);
    assertTrue(CollectionVerifier.completedWithin(request("system", 10, 100), cycle, "zgc"));
    assertTrue(CollectionVerifier.completedWithin(request("system", 20, 80), cycle, "zgc"));
    assertFalse(CollectionVerifier.completedWithin(request("system", 40, 80), cycle, "zgc"));
  }
}
