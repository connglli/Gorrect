package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectCloneSourceTest {
  @ParameterizedTest
  @ValueSource(strings = {"missing", "extra", "shape", "signature", "worker", "site", "occurrence"})
  void markerAndRoleAuthorityComesFromExactSourceSelection(String damage) {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 0; shape U object 0 0; object 1 N [] []; root r = 1; program {"
                + " read-root n N r; repeat 0 { clone c n; } }");
    var emission = JavaEmitter.emit(program);
    var json = JsonFiles.GSON.toJsonTree(emission.layout()).getAsJsonObject();
    var selected = json.getAsJsonArray("objects").get(0).getAsJsonObject();
    switch (damage) {
      case "missing" -> selected.remove("cloneIdentity");
      case "extra" ->
          json.getAsJsonArray("objects")
              .get(1)
              .getAsJsonObject()
              .addProperty("cloneIdentity", ObservationLayout.OBJECT_CLONE_IDENTITY);
      case "shape" -> selected.getAsJsonObject("shape").addProperty("name", "U");
      case "signature" -> selected.addProperty("signature", "LUnknown;");
      default -> selected.addProperty(damage, "other");
    }
    var layout = JsonFiles.GSON.fromJson(json, ObservationLayout.class);
    assertThrows(IOException.class, () -> ObjectClones.source(program, layout));
    assertDoesNotThrow(() -> ObjectClones.source(program, emission.layout()));
  }

  @Test
  void markerCannotAuthorizeAnUnclonedSourceOrUnknownEncoding() {
    var program = GirParser.parse("gir 1; shape N object 0 0; program {}");
    var emission = JavaEmitter.emit(program);
    var json = JsonFiles.GSON.toJsonTree(emission.layout()).getAsJsonObject();
    var object = json.getAsJsonArray("objects").get(0).getAsJsonObject();
    object.addProperty("cloneIdentity", ObservationLayout.OBJECT_CLONE_IDENTITY);
    var forged = JsonFiles.GSON.fromJson(json, ObservationLayout.class);
    assertThrows(IOException.class, () -> ObjectClones.source(program, forged));
    object.addProperty("cloneIdentity", "Cloneable");
    assertThrows(
        RuntimeException.class, () -> JsonFiles.GSON.fromJson(json, ObservationLayout.class));
  }
}
