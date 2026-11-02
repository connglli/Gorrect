package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.engine.gir.CloneProgram;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Re-derives mutable diagnostic roles from saved source before captured evidence is used. */
final class ObjectClones {
  private ObjectClones() {}

  static boolean selected(ObservationLayout layout) {
    return layout.objects().stream().anyMatch(type -> type.cloneIdentity() != null);
  }

  static boolean selected(Path directory) {
    Path path = directory.resolve("generated/layout.json");
    if (!Files.exists(path)) return false;
    try {
      return selected(JsonFiles.GSON.fromJson(JsonFiles.read(path), ObservationLayout.class));
    } catch (IOException failure) {
      throw new IllegalArgumentException("Cannot read object clone launch selection", failure);
    }
  }

  static List<String> configuration(TargetProfile profile) {
    return ControlledLaunch.configuration(profile, true);
  }

  /** Qualified target families are explicit; the report alone cannot select clone semantics. */
  static void admission(TargetProfile profile, JsonObject manifest, Properties facts)
      throws IOException {
    ControlledLaunch.evidence(profile, manifest, facts, true);
    var problems = new ArrayList<String>();
    ControlledLaunch.effective(profile, facts, problems, true);
    if (!problems.isEmpty()) throw new IOException(String.join("; ", problems));
    if (!facts.getProperty("os.name", "").equals("Linux")
        || !facts.getProperty("os.arch", "").equals("amd64"))
      throw new IOException("Object cloning requires qualified Linux amd64");
    String feature = facts.getProperty("java.feature", "");
    if (profile.vm().equals("hotspot")) {
      if (!Set.of("17", "21").contains(feature))
        throw new IOException("Object cloning qualification covers HotSpot JDK 17/21");
    } else if (!profile.vm().equals("openj9")
        || !feature.equals("21")
        || !facts.getProperty("java.vm.version", "").equals("master-6e16eb1a4f"))
      throw new IOException("Object cloning requires the selected OpenJ9 build");
    if (!facts.getProperty("observer.objectClones.qualified", "false").equals("true"))
      throw new IOException("Actual generated object clone qualification is missing");
  }

  static void source(GirProgram program, ObservationLayout layout) throws IOException {
    var selected = CloneProgram.objectShapes(program);
    var expected = new HashMap<String, Shape>();
    int index = 0;
    for (var shape : program.shapes()) {
      if (selected.contains(shape)) expected.put("LCase$S" + index + ";", shape);
      index++;
    }
    var found = new HashSet<String>();
    for (var object : layout.objects()) {
      var shape = expected.get(object.signature());
      String marker = shape == null ? null : ObservationLayout.OBJECT_CLONE_IDENTITY;
      if (!Objects.equals(marker, object.cloneIdentity())
          || (shape != null && (!shape.equals(object.shape()) || !found.add(object.signature()))))
        throw new IOException("Object clone identity layout differs from source selection");
      if (shape != null
          && (!object.worker().equals("idWorker")
              || !object.site().equals("idSite")
              || !object.occurrence().equals("idOccurrence")))
        throw new IOException("Object clone diagnostic roles differ from generated fields");
    }
    if (!found.equals(expected.keySet()))
      throw new IOException("Missing source-selected object clone layout");
  }
}
