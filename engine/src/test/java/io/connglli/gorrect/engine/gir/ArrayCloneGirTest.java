package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArrayCloneGirTest {
  private static final String HEADER =
      "gir 1; shape A data 2; shape N object 0 0; object 1 A [] [7, 8];"
          + " object 2 N [] []; root arr = 1; root node = 2; root empty = null;";

  @Test
  void infersNominalShapeAndPreservesOptionalLabels() {
    var program =
        GirParser.parse(
            HEADER + "program { read-root original A arr; copied: clone copy original; }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(Map.of(2, program.shapes().getFirst()), GirValidator.arrayCloneShapes(program));
    assertEquals("copied", program.labels().get(2));
    assertTrue(GirFormatter.format(program).contains("copied: clone copy original;"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "clone copy missing;",
        "clone original original;",
        "read-data scalar original 0; clone copy scalar;",
        "release original; clone copy original;",
        "read-root ordinary N node; clone copy ordinary;",
        "read-root ordinary N node; repeat 0 { clone copy ordinary; }",
        "read-root ordinary N node; if-nonzero 1 {} else { clone copy ordinary; }"
      })
  void rejectsInvalidNamesAndNonArrayTypesInEveryBlock(String source) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root original A arr; " + source + " }"));
  }

  @Test
  void rejectsNaturalCaptureEvenWhenTheCloneDoesNotExecute() {
    var program =
        GirParser.parse(
            HEADER + "program { read-root original A arr; repeat 0 { clone copy original; } }");
    assertThrows(
        GirException.class, () -> JavaEmitter.emit(program, ObservationProfile.NATURAL_ROOTS));
  }
}
