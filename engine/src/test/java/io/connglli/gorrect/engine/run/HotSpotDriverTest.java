package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class HotSpotDriverTest {
  private static TargetProfile profile(String collector, String mode) {
    var raw = new JsonObject();
    raw.addProperty("version", 1);
    raw.addProperty("vm", "hotspot");
    raw.addProperty("java", "java");
    raw.addProperty("collector", collector);
    raw.addProperty("collectorMode", mode);
    raw.addProperty("observation", "named-roots-diagnostic");
    var support = new JsonObject();
    support.addProperty("runtime", "runtime.jar");
    support.addProperty("protocol", "protocol.jar");
    support.addProperty("observer", "observer.so");
    raw.add("support", support);
    return TargetProfile.parse(raw, Path.of("."));
  }

  @Test
  void zgcModesUseActualFlagAvailabilityAndBeanIdentity() {
    var old = new Properties();
    old.setProperty("collectors", "ZGC Cycles,ZGC Pauses");
    assertEquals(
        List.of("-XX:+UseZGC"),
        HotSpotDriver.collectorArguments(profile("zgc", "non-generational"), old));
    assertThrows(
        IllegalArgumentException.class,
        () -> HotSpotDriver.collectorArguments(profile("zgc", "generational"), old));
    old.setProperty("flag.ZGenerational", "false");
    assertEquals(
        List.of("-XX:+UseZGC", "-XX:+ZGenerational"),
        HotSpotDriver.collectorArguments(profile("zgc", "generational"), old));
    var modern = new Properties();
    modern.setProperty(
        "collectors", "ZGC Minor Cycles,ZGC Minor Pauses,ZGC Major Cycles,ZGC Major Pauses");
    assertEquals("generational", HotSpotDriver.mode("zgc", modern));
    assertEquals(
        List.of("-XX:+UseZGC"),
        HotSpotDriver.collectorArguments(profile("zgc", "generational"), modern));
    assertThrows(
        IllegalArgumentException.class,
        () -> HotSpotDriver.collectorArguments(profile("zgc", "non-generational"), modern));
  }

  @Test
  void presentZgcGenerationFlagsMustAgreeWithBeans() {
    var facts = new Properties();
    facts.setProperty(
        "collectors", "ZGC Minor Cycles,ZGC Minor Pauses,ZGC Major Cycles,ZGC Major Pauses");
    for (String flag : List.of("false", "unknown", "TRUE", "")) {
      facts.setProperty("flag.ZGenerational", flag);
      assertThrows(IllegalArgumentException.class, () -> HotSpotDriver.mode("zgc", facts));
    }
    facts.setProperty("flag.ZGenerational", "true");
    assertEquals("generational", HotSpotDriver.mode("zgc", facts));
    facts.remove("flag.ZGenerational");
    assertEquals("generational", HotSpotDriver.mode("zgc", facts));
    facts.setProperty("collectors", "ZGC Cycles,ZGC Pauses");
    facts.setProperty("flag.ZGenerational", "true");
    assertThrows(IllegalArgumentException.class, () -> HotSpotDriver.mode("zgc", facts));
    facts.setProperty("flag.ZGenerational", "false");
    assertEquals("non-generational", HotSpotDriver.mode("zgc", facts));
  }

  @Test
  void g1AllowsTheBackportedConcurrentBeanButNotAnotherCollector() {
    var facts = new Properties();
    for (String name : List.of("serial", "parallel", "g1", "zgc", "shenandoah"))
      facts.setProperty("flag." + HotSpotDriver.flag(name), Boolean.toString(name.equals("g1")));
    facts.setProperty("collectors", "G1 Young Generation,G1 Old Generation");
    assertTrue(HotSpotDriver.selected("g1", facts));
    facts.setProperty("collectors", "G1 Young Generation,G1 Old Generation,G1 Concurrent GC");
    assertTrue(HotSpotDriver.selected("g1", facts));
    facts.setProperty("flag.UseSerialGC", "true");
    assertFalse(HotSpotDriver.selected("g1", facts));
  }
}
