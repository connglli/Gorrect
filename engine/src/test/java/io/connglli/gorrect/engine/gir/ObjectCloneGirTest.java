package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectCloneGirTest {
  private static final String HEADER =
      "gir 1; shape N object 1 [int, long]; shape U object 0 0; object 1 N [1] [7, 8];"
          + " root node = 1; root empty = null;";

  @Test
  void infersObjectsThroughCloneChainsAndSelectsSkippedSites() {
    var program =
        GirParser.parse(
            HEADER
                + "program { read-root n N node; repeat 0 {"
                + " first: clone copy n; clone again copy; } }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    var n = program.shapes().getFirst();
    assertEquals(Map.of(3, n, 4, n), GirValidator.cloneShapes(program));
    var emission = JavaEmitter.emit(program);
    assertEquals(
        ObservationLayout.OBJECT_CLONE_IDENTITY,
        emission.layout().objects().getFirst().cloneIdentity());
    assertEquals(null, emission.layout().objects().get(1).cloneIdentity());
    assertTrue(emission.source().contains("S0 implements java.lang.Cloneable"));
    assertFalse(emission.source().contains("S1 implements"));
    assertTrue(emission.source().contains("(S0) super.clone()"));
    assertTrue(emission.source().contains("copy.idSite = site; copy.idOccurrence = occurrence;"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "clone c missing;",
        "read-data scalar n 0; clone c scalar;",
        "release n; clone c n;",
        "clone n n;"
      })
  void invalidOwnersAndNamesReject(String source) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root n N node; " + source + " }"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "shape W weak;",
        "root nativeSlot jni-global = null;",
        "method unused() {}",
        "method unused(ref N n) { repeat 0 { clone c n; } }"
      })
  void unusedDeclarationsCannotBypassObjectCompositionGuards(String declaration) {
    var program =
        GirParser.parse(HEADER + declaration + "program { read-root n N node; clone c n; }");
    assertFalse(CloneProgram.unsupported(program).isEmpty());
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
  }

  @Test
  void excludesWorkersNaturalObservationAndOrderedAccessesEvenWhenSkipped() {
    for (String source :
        List.of(
            "parallel { worker a {} worker b {} }", "repeat 0 { write-volatile-data n 0 1; }")) {
      var program =
          GirParser.parse(HEADER + "program { read-root n N node; clone c n; " + source + " }");
      assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
    }
    var program =
        GirParser.parse(HEADER + "program { read-root n N node; repeat 0 { clone c n; } }");
    assertThrows(
        GirException.class, () -> JavaEmitter.emit(program, ObservationProfile.NATURAL_ROOTS));
  }

  @Test
  void ordinaryClonesDoNotConsumeArrayIdentityCapacityInCallPlanning() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 0; object 1 N [] []; root r = 1;"
                + " method unused() {} program { read-root n N r; repeat 5000 { clone c n; } }");
    assertFalse(MethodProgram.unsupported(program).isEmpty());
    assertEquals(1, GirValidator.cloneShapes(program).size());
  }

  @Test
  void boundsQualificationForSkippedShapesBeforeEmission() {
    assertDoesNotThrow(
        () ->
            JavaEmitter.emit(
                GirParser.parse(
                    "gir 1; shape N object 466 0; root r = null; program { read-root n N r;"
                        + " repeat 0 { clone c n; } }")));
    assertDoesNotThrow(
        () ->
            JavaEmitter.emit(
                GirParser.parse(
                    "gir 1; shape N object 232 0; shape M object 232 0; root r = null; program {"
                        + " read-root n N r; read-root m M r; repeat 0 { clone c n; clone d m;"
                        + " } }")));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 467 0; root r = null; program { read-root n N r;"
                    + " repeat 0 { clone c n; } }"));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 233 0; shape M object 233 0; root r = null; program {"
                    + " read-root n N r; read-root m M r; repeat 0 { clone c n; clone d m; } }"));
  }

  @Test
  void absentSelectionDoesNotSerializeNewLayoutFields() {
    var emission = JavaEmitter.emit(GirParser.parse(HEADER + "program {}"));
    assertFalse(emission.source().contains("Cloneable"));
    assertFalse(emission.source().contains("qualifyClones"));
    assertFalse(JsonFiles.GSON.toJson(emission.layout()).contains("cloneIdentity"));
  }
}
