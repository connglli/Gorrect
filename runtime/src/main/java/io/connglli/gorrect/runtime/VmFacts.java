package io.connglli.gorrect.runtime;

import java.lang.management.ManagementFactory;
import java.util.Properties;

/** Target identity and VM-specific facts, queried before any graph is constructed. */
public final class VmFacts {
  private VmFacts() {}

  static boolean isOpenJ9() {
    return System.getProperty("java.vm.name", "").contains("OpenJ9");
  }

  static Properties common() {
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
    return properties;
  }

  public static Properties read() {
    if (!isOpenJ9()) return HotSpotFacts.read();
    var properties = common();
    try {
      // Query the public MBean attribute without a compile-time dependency on IBM classes.
      var server = ManagementFactory.getPlatformMBeanServer();
      properties.setProperty(
          "collector.policy",
          (String)
              server.getAttribute(
                  new javax.management.ObjectName("java.lang:type=Memory"), "GCMode"));
    } catch (javax.management.JMException | RuntimeException unavailable) {
      properties.setProperty("collector.problem", unavailable.toString());
    }
    return properties;
  }

  public static void main(String[] args) throws java.io.IOException {
    read().store(System.out, "Target VM facts");
  }
}
