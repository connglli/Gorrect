package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class YoungGcAvailabilityTest {
  @Test
  void youngMethodAndCaptureAvailabilityRemainSeparateFromExecutionCoverage() {
    var profile = profile("serial", "whitebox-young", "young-gc");
    var target = facts("serial", "whitebox-young");
    assertTrue(VmDriver.requirements(profile, target).isEmpty());
    target.setProperty("gc.evidence", "false");
    assertTrue(VmDriver.requirements(profile, target).contains("young-gc"));
    target.setProperty("gc.evidence", "true");
    target.setProperty("gc.problem", "youngGC is unavailable");
    assertFalse(VmDriver.requirements(profile, target).isEmpty());
  }

  @Test
  void unsupportedPoliciesAndIncompatibleCertificatesHaveNoFallback() {
    for (String collector : List.of("zgc", "shenandoah")) {
      var gaps =
          VmDriver.requirements(
              profile(collector, "whitebox-young", "young-gc"), facts(collector, "whitebox-young"));
      assertTrue(gaps.contains("young-gc"), gaps.toString());
      assertTrue(
          gaps.stream().anyMatch(gap -> gap.contains("whitebox-young is unsupported")),
          gaps.toString());
    }
    assertTrue(
        VmDriver.requirements(profile("serial", "system", "young-gc"), facts("serial", "system"))
            .contains("young-gc"));
    assertTrue(
        VmDriver.requirements(
                profile("serial", "whitebox-young", "gc-completion"),
                facts("serial", "whitebox-young"))
            .contains("gc-completion"));
    assertEquals("system", profile("serial", "system", "young-gc").gc().control());
  }

  @Test
  void generationalZgcNeedsExplicitModeAndConfirmedModernTargetFacts() {
    var selected = profile("zgc", "generational", "whitebox-young", "young-gc");
    var target = generationalFacts();
    assertTrue(VmDriver.unsupported(selected).isEmpty());
    assertTrue(VmDriver.requirements(selected, target).isEmpty());

    for (String mode : List.of("default", "non-generational")) {
      var requested = profile("zgc", mode, "whitebox-young", "young-gc");
      assertFalse(VmDriver.unsupported(requested).isEmpty());
      assertTrue(VmDriver.requirements(requested, target).contains("young-gc"));
    }
    target.setProperty("java.feature", "17");
    assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
    assertTrue(
        VmDriver.requirements(selected, target).stream().anyMatch(gap -> gap.contains("JDK 21+")));

    target = facts("zgc", "whitebox-young");
    assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
    for (String flag : List.of("false", "unknown", "TRUE")) {
      target = generationalFacts();
      target.setProperty("flag.ZGenerational", flag);
      assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
    }
    target = generationalFacts();
    target.remove("flag.ZGenerational");
    assertTrue(VmDriver.requirements(selected, target).isEmpty());
    target = generationalFacts();
    target.setProperty("java.vm.name", "Eclipse OpenJ9 VM");
    assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
    target = generationalFacts();
    target.setProperty("flag.UseZGC", "false");
    assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
    target = generationalFacts();
    target.setProperty("collectors", "ZGC Minor Cycles,ZGC Minor Pauses");
    assertTrue(VmDriver.requirements(selected, target).contains("young-gc"));
  }

  private static Properties generationalFacts() {
    var result = facts("zgc", "whitebox-young");
    result.setProperty("flag.ZGenerational", "true");
    result.setProperty(
        "collectors", "ZGC Minor Cycles,ZGC Minor Pauses,ZGC Major Cycles,ZGC Major Pauses");
    return result;
  }

  private static TargetProfile profile(String collector, String control, String requirement) {
    return profile(collector, "default", control, requirement);
  }

  private static TargetProfile profile(
      String collector, String mode, String control, String requirement) {
    var raw =
        JsonFiles.GSON.fromJson(
            """
            {"version":1,"vm":"hotspot","java":"jdk/bin/java","collector":"serial",
             "observation":"named-roots-diagnostic",
             "support":{"runtime":"runtime.jar","protocol":"protocol.jar","observer":"observer.so","whitebox":"client.jar"}}
            """,
            JsonObject.class);
    raw.addProperty("collector", collector);
    raw.addProperty("collectorMode", mode);
    var gc = new JsonObject();
    gc.addProperty("control", control);
    raw.add("gc", gc);
    raw.add("requirements", JsonFiles.GSON.toJsonTree(List.of(requirement)));
    return TargetProfile.parse(raw, Path.of("."));
  }

  private static Properties facts(String collector, String control) {
    var result = new Properties();
    result.setProperty("java.feature", "21");
    result.setProperty("java.vm.name", "OpenJDK 64-Bit Server VM");
    for (String family : List.of("serial", "parallel", "g1", "zgc", "shenandoah"))
      result.setProperty(
          "flag." + HotSpotDriver.flag(family), Boolean.toString(family.equals(collector)));
    result.setProperty("flag.ShenandoahGCMode", "satb");
    result.setProperty(
        "collectors",
        switch (collector) {
          case "serial" -> "Copy,MarkSweepCompact";
          case "parallel" -> "PS Scavenge,PS MarkSweep";
          case "g1" -> "G1 Young Generation,G1 Old Generation";
          case "zgc" -> "ZGC Cycles,ZGC Pauses";
          case "shenandoah" -> "Shenandoah Pauses,Shenandoah Cycles";
          default -> throw new IllegalArgumentException(collector);
        });
    result.setProperty("observer.capabilities", Long.toString(HeapSnapshot.TAGGING));
    result.setProperty("gc.control", control);
    result.setProperty("gc.problem", "");
    result.setProperty("gc.evidence", "true");
    return result;
  }
}
