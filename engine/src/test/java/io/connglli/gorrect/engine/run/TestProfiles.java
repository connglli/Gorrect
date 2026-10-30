package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Mandatory launch fields only; each test owns its VM options, requirements, and limits. */
final class TestProfiles {
  private TestProfiles() {}

  static JsonObject create(String vm, String javaExecutable, String collector) {
    Path support = Path.of(System.getProperty("gorrect.support"));
    var profile = new JsonObject();
    profile.addProperty("version", 1);
    profile.addProperty("vm", vm);
    profile.addProperty("java", javaExecutable);
    profile.addProperty("collector", collector);
    profile.addProperty("observation", "named-roots-diagnostic");
    var libraries = new JsonObject();
    libraries.addProperty("runtime", support.resolve("runtime.jar").toString());
    libraries.addProperty("protocol", support.resolve("protocol.jar").toString());
    libraries.addProperty(
        "observer", support.resolve(System.mapLibraryName("gorrect_observer")).toString());
    profile.add("support", libraries);
    return profile;
  }
}
