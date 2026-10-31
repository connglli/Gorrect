package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityHashGirTest {
  private static final String HEADER =
      "gir 1; shape N object 0 1; object 1 N [] [7]; root main = 1; ";

  @Test
  void roundTripsOperandsAndLabelsWithoutCreatingABinding() {
    var program =
        GirParser.parse(
            HEADER
                + "program { read-root n N main; hash: identity-hash n;"
                + " identity-hash null; }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals("hash", program.labels().get(2));
    assertTrue(IdentityHashProgram.usesIdentityHash(program));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "identity-hash 7;",
        "identity-hash missing;",
        "read-data scalar n 0; identity-hash scalar;",
        "release n; identity-hash n;",
        "repeat 0 { identity-hash missing; }",
        "if-nonzero 0 { identity-hash 9; }"
      })
  void rejectsInvalidReferenceOperandsEvenInSkippedBlocks(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root n N main; " + body + " }"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "parallel { worker a {} worker b {} }",
        "read-volatile-root shared N main;",
        "repeat 0 { poll-queue; }"
      })
  void emitterRejectsUnsupportedCompositesEvenWhenTheHashIsSkipped(String extra) {
    var program =
        GirParser.parse(HEADER + "program { repeat 0 { identity-hash null; } " + extra + " }");
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
  }

  @Test
  void unusedWrapperDeclarationsStillSelectTheUnsupportedReferenceContract() {
    var program = GirParser.parse(HEADER + "shape W weak; program { identity-hash null; }");
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
  }

  @Test
  void rejectsNaturalObservationForAnUnexecutedHash() {
    var program = GirParser.parse(HEADER + "program { repeat 0 { identity-hash null; } }");
    assertThrows(
        GirException.class, () -> JavaEmitter.emit(program, ObservationProfile.NATURAL_ROOTS));
  }
}
