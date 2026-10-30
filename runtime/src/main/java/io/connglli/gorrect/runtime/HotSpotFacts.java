package io.connglli.gorrect.runtime;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Properties;

/** Lightweight target identity and option query; it neither runs a graph nor loads the observer. */
public final class HotSpotFacts {
  private HotSpotFacts() {}

  public static Properties read() {
    var properties = VmFacts.common();
    try {
      var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      for (String name :
          new String[] {
            "UseSerialGC",
            "UseParallelGC",
            "UseG1GC",
            "UseZGC",
            "UseShenandoahGC",
            "ZGenerational",
            "ShenandoahGCMode",
            "DisableExplicitGC",
            "ExplicitGCInvokesConcurrent",
            "UseCompiler",
            "TieredCompilation",
            "TieredStopAtLevel"
          }) {
        try {
          properties.setProperty("flag." + name, bean.getVMOption(name).getValue());
        } catch (IllegalArgumentException absent) {
          /* An absent option is distinct from false. */
        }
      }
    } catch (LinkageError | RuntimeException unavailable) {
      properties.setProperty("flags.problem", unavailable.toString());
    }
    return properties;
  }

  public static void main(String[] args) throws IOException {
    read().store(System.out, "HotSpot facts");
  }
}
