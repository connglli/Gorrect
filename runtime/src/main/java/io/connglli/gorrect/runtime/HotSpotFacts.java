package io.connglli.gorrect.runtime;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.Properties;

/** Lightweight target identity and option query; it neither runs a graph nor loads the observer. */
public final class HotSpotFacts {
  private HotSpotFacts() {}

  public static Properties read() {
    var properties = new Properties();
    for (String name :
        new String[] {
          "java.version", "java.vm.name", "java.vm.version", "java.vendor", "os.name", "os.arch"
        }) properties.setProperty(name, System.getProperty(name));
    properties.setProperty("process.pid", Long.toString(ProcessHandle.current().pid()));
    properties.setProperty(
        "vm.start", Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()));
    properties.setProperty("java.feature", Integer.toString(Runtime.version().feature()));
    properties.setProperty(
        "collectors",
        String.join(
            ",",
            ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(bean -> bean.getName())
                .toList()));
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

  public static void main(String[] args) throws java.io.IOException {
    read().store(System.out, "HotSpot facts");
  }
}
