package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.CollectionEvidence;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectionVerifierTest {
  @Test
  void workerOrderForbidsOverlappingSpansWithoutUsingFileOrder() {
    var first = request("system", 10, 20);
    var second =
        new CollectionEvidence.Request(0, 2, 0, 0, 0, 2, 1, "system", true, time(20), time(30));
    assertEquals(List.of(first, second), CollectionVerifier.ordered(List.of(second, first)));
    var overlap =
        new CollectionEvidence.Request(0, 2, 0, 0, 0, 2, 1, "system", true, time(19), time(30));
    assertThrows(
        IllegalArgumentException.class, () -> CollectionVerifier.ordered(List.of(first, overlap)));
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

  @ParameterizedTest
  @CsvSource({"serial,DefNew", "parallel,ParallelScavenge", "g1,G1New"})
  void youngCertificatesRequireTheSelectedYoungKindCauseAndCompleteInterval(
      String collector, String name) {
    var request = request("whitebox-young", 10, 100);
    assertTrue(
        CollectionVerifier.youngWithin(
            request, cycle(name, "WhiteBox Initiated Young GC", 10, 100), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle(name, "Allocation Failure", 20, 90), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle(name, "WhiteBox Initiated Full GC", 20, 90), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle(name, "WhiteBox Initiated Young GC", 9, 90), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle(name, "WhiteBox Initiated Young GC", 20, 101), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle(name, "WhiteBox Initiated Young GC", 20, 20), collector));
    assertFalse(
        CollectionVerifier.youngWithin(
            request("system", 10, 100),
            cycle(name, "WhiteBox Initiated Young GC", 20, 90),
            collector));
    assertFalse(
        CollectionVerifier.completedWithin(
            request, cycle(name, "WhiteBox Initiated Young GC", 20, 90), collector));
  }

  @Test
  void fullCyclesAndOtherCollectorsCannotSubstituteForAYoungCertificate() {
    var request = request("whitebox-young", 10, 100);
    for (String name :
        List.of("SerialOld", "ParallelOld", "G1Full", "G1Old", "Z", "ZGC Minor", "Shenandoah"))
      assertFalse(
          CollectionVerifier.youngWithin(
              request, cycle(name, "WhiteBox Initiated Young GC", 20, 90), "serial"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle("ZGC Minor", "WhiteBox Initiated Young GC", 20, 90), "zgc"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle("Shenandoah", "WhiteBox Initiated Young GC", 20, 90), "shenandoah"));
    assertFalse(
        CollectionVerifier.youngWithin(
            new CollectionEvidence.Request(
                0, 1, 0, 0, 0, 1, 1, "whitebox-young", false, time(10), time(100)),
            cycle("DefNew", "WhiteBox Initiated Young GC", 20, 90),
            "serial"));
  }

  @ParameterizedTest
  @CsvSource({"serial,default,DefNew", "zgc,generational,ZGC Minor"})
  void youngCoverageChecksEveryRequestAgainstIndependentExpectedContext(
      String collector, String mode, String name) {
    var profile = youngProfile(collector, mode);
    var key = new CollectionVerifier.Key(0, 0, 0, 1);
    var expected = Map.of(key, new CollectionVerifier.Expected(1, 0, 1));
    var valid = request("whitebox-young", 10, 100);
    var event = cycle(name, "WhiteBox Initiated Young GC", 20, 90);
    assertTrue(
        CollectionVerifier.compare(
                expected, new CollectionEvidence(List.of(valid), List.of(event)), profile)
            .unmet()
            .isEmpty());

    for (var requests :
        List.of(
            List.<CollectionEvidence.Request>of(),
            List.of(valid, request("whitebox-young", 100, 200)),
            List.of(request("system", 10, 100)),
            List.of(
                new CollectionEvidence.Request(
                    0, 2, 0, 0, 0, 1, 1, "whitebox-young", true, time(10), time(100))),
            List.of(
                new CollectionEvidence.Request(
                    0, 1, 0, 0, 1, 1, 1, "whitebox-young", true, time(10), time(100))),
            List.of(
                new CollectionEvidence.Request(
                    0, 1, 0, 0, 0, 1, 2, "whitebox-young", true, time(10), time(100))),
            List.of(
                new CollectionEvidence.Request(
                    0, 1, 0, 0, 0, 2, 1, "whitebox-young", true, time(10), time(100))),
            List.of(
                new CollectionEvidence.Request(
                    0, 1, 0, 0, 0, 1, 1, "whitebox-young", false, time(10), time(100))))) {
      var result =
          CollectionVerifier.compare(
              expected, new CollectionEvidence(requests, List.of(event)), profile);
      assertEquals(List.of("young-gc"), result.unmet());
      assertFalse(result.problems().isEmpty());
    }
    assertEquals(
        List.of("young-gc"),
        CollectionVerifier.compare(
                expected, new CollectionEvidence(List.of(valid), List.of()), profile)
            .unmet());
    assertEquals(
        List.of("young-gc"),
        CollectionVerifier.compare(
                Map.of(), new CollectionEvidence(List.of(), List.of(event)), profile)
            .unmet());
  }

  @ParameterizedTest
  @CsvSource({"serial,default,DefNew", "zgc,generational,ZGC Minor"})
  void coalescedYoungRequestsRetainWorkerContextsWithoutUsingFileOrder(
      String collector, String mode, String name) {
    var first =
        new CollectionEvidence.Request(
            1, 11, 3, 2, 4, 1, 101, "whitebox-young", true, time(10), time(100));
    var second =
        new CollectionEvidence.Request(
            2, 12, 3, 2, 4, 1, 102, "whitebox-young", true, time(20), time(90));
    var expected =
        Map.of(
            new CollectionVerifier.Key(1, 3, 2, 1), new CollectionVerifier.Expected(11, 4, 101),
            new CollectionVerifier.Key(2, 3, 2, 1), new CollectionVerifier.Expected(12, 4, 102));
    var evidence =
        new CollectionEvidence(
            List.of(second, first), List.of(cycle(name, "WhiteBox Initiated Young GC", 30, 80)));
    assertTrue(
        CollectionVerifier.compare(expected, evidence, youngProfile(collector, mode))
            .unmet()
            .isEmpty());
  }

  @Test
  void zgcMinorCertificateNeedsExplicitGenerationalModeAndItsOwnCause() {
    var request = request("whitebox-young", 10, 100);
    var minor = cycle("ZGC Minor", "WhiteBox Initiated Young GC", 20, 90);
    assertTrue(CollectionVerifier.youngWithin(request, minor, "zgc", "generational"));
    assertFalse(CollectionVerifier.youngWithin(request, minor, "zgc"));
    for (String mode : List.of("default", "non-generational", "unknown"))
      assertFalse(CollectionVerifier.youngWithin(request, minor, "zgc", mode));
    for (String name : List.of("Z", "ZGC Major", "ZGC Minor Pauses", "G1New"))
      assertFalse(
          CollectionVerifier.youngWithin(
              request, cycle(name, "WhiteBox Initiated Young GC", 20, 90), "zgc", "generational"));
    for (String cause : List.of("System.gc()", "WhiteBox Initiated Full GC", "Allocation Rate"))
      assertFalse(
          CollectionVerifier.youngWithin(
              request, cycle("ZGC Minor", cause, 20, 90), "zgc", "generational"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle("ZGC Minor", minor.cause(), 9, 90), "zgc", "generational"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle("ZGC Minor", minor.cause(), 20, 101), "zgc", "generational"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request, cycle("ZGC Minor", minor.cause(), 20, 20), "zgc", "generational"));
    assertFalse(
        CollectionVerifier.youngWithin(
            request("whitebox-full", 10, 100), minor, "zgc", "generational"));
    assertFalse(
        CollectionVerifier.completedWithin(
            request("whitebox-full", 10, 100), minor, "zgc", "generational"));
  }

  @Test
  void nestedMajorOrUnrelatedMinorEventsDoNotStandInForTheYoungRequest() {
    var profile = youngProfile("zgc", "generational");
    var expected =
        Map.of(new CollectionVerifier.Key(0, 0, 0, 1), new CollectionVerifier.Expected(1, 0, 1));
    var request = request("whitebox-young", 10, 100);
    var major = cycle("ZGC Major", "WhiteBox Initiated Young GC", 20, 90);
    var wrongMinor = cycle("ZGC Minor", "WhiteBox Initiated Full GC", 30, 80);
    assertEquals(
        List.of("young-gc"),
        CollectionVerifier.compare(
                expected,
                new CollectionEvidence(List.of(request), List.of(major, wrongMinor)),
                profile)
            .unmet());

    // A qualifying minor proves the young path even if another major also runs; this is not a
    // claim that the interval contains young-only work.
    var minor = cycle("ZGC Minor", "WhiteBox Initiated Young GC", 30, 80);
    assertTrue(
        CollectionVerifier.compare(
                expected, new CollectionEvidence(List.of(request), List.of(major, minor)), profile)
            .unmet()
            .isEmpty());
    assertEquals(
        List.of("young-gc"),
        CollectionVerifier.compare(
                Map.of(), new CollectionEvidence(List.of(), List.of(minor)), profile)
            .unmet());
  }

  private static TargetProfile youngProfile(String collector, String mode) {
    var raw =
        JsonFiles.GSON.fromJson(
            """
            {"version":1,"vm":"hotspot","java":"jdk/bin/java","collector":"serial",
             "observation":"named-roots-diagnostic","requirements":["young-gc"],
             "gc":{"control":"whitebox-young"},
             "support":{"runtime":"runtime.jar","protocol":"protocol.jar","observer":"observer.so","whitebox":"client.jar"}}
            """,
            JsonObject.class);
    raw.addProperty("collector", collector);
    raw.addProperty("collectorMode", mode);
    return TargetProfile.parse(raw, Path.of("."));
  }
}
