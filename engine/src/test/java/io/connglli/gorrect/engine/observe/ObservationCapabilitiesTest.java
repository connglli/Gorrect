package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservationCapabilitiesTest {
  @Test
  void distinguishesAvailabilityExerciseAndVerificationAndRejectsUnsupportedTargets() {
    var program = GirParser.parse("gir 1; program {}");
    var target = new Properties();
    target.setProperty(
        "observer.capabilities",
        Long.toString(HeapSnapshot.TAGGING | HeapSnapshot.LOCAL_VARIABLES));
    var available =
        ObservationCapabilities.describe(
                program, ObservationProfile.CONTROLLED_ROOTS, target, Set.of(), Set.of())
            .get(1);
    assertTrue(available.available());
    assertFalse(available.exercised());
    assertFalse(available.verified());
    var exercised =
        ObservationCapabilities.describe(
                program,
                ObservationProfile.CONTROLLED_ROOTS,
                target,
                Set.of("local-bindings"),
                Set.of())
            .get(1);
    assertTrue(exercised.exercised());
    assertFalse(exercised.verified());
    target.setProperty("observer.capabilities", Long.toString(HeapSnapshot.TAGGING));
    var unsupported =
        ObservationCapabilities.describe(
                program,
                ObservationProfile.CONTROLLED_ROOTS,
                target,
                Set.of("local-bindings"),
                Set.of("local-bindings"))
            .get(1);
    assertFalse(unsupported.available());
    assertFalse(unsupported.verified());
    assertFalse(unsupported.unsupportedReasons().isEmpty());
  }

  @Test
  void permitsUnusedArrayDeclarationsButRejectsDiagnosticArrayBindingInNaturalMode() {
    var unused = GirParser.parse("gir 1; shape R refs 1; program {}");
    assertTrue(
        ObservationCapabilities.unsupported(unused, ObservationProfile.NATURAL_ROOTS).isEmpty());
    var allocated = GirParser.parse("gir 1; shape R refs 1; program { allocate r R; }");
    assertFalse(
        ObservationCapabilities.unsupported(allocated, ObservationProfile.NATURAL_ROOTS).isEmpty());
    assertTrue(
        ObservationCapabilities.unsupported(allocated, ObservationProfile.CONTROLLED_ROOTS)
            .isEmpty());
  }

  @Test
  void doesNotAdvertiseUnsupportedMemoryOrConditionalCombinations() {
    var history =
        GirParser.parse(
            "gir 1; shape N object 0 0; root r = null; program { read-volatile-root n N r; }");
    assertFalse(
        ObservationCapabilities.unsupported(history, ObservationProfile.NATURAL_ROOTS).isEmpty());
    var conditional =
        GirParser.parse(
            "gir 1; shape N object 0 0; root r = null; program { read-root n N r; if-present n {}"
                + " }");
    assertFalse(
        ObservationCapabilities.unsupported(conditional, ObservationProfile.NATURAL_ROOTS)
            .isEmpty());
  }
}
