package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.Shape;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceObjectGirTest {
  @Test
  void roundTripsNominalTypesReceiverAndLegacyArities() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0; shape W weak; shape S soft;
            program { allocate n N; allocate-ref w W n; allocate-ref s S w;
              acquire h W s { clear-ref h; is-cleared c h; }
              clear-ref w; is-cleared c w;
              write-weak n 0 null; clear-ref n 0; is-cleared d n 0;
            }
            """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(Shape.Kind.WEAK_REFERENCE, program.shapes().get(1).kind());
    var clear =
        program
            .instructions()
            .filter(Instruction.ClearReference.class::isInstance)
            .map(Instruction.ClearReference.class::cast)
            .toList();
    assertTrue(clear.getFirst().receiver());
    assertFalse(clear.getLast().receiver());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "allocate w W;",
        "allocate-ref w N null;",
        "clear-ref n;",
        "is-cleared c n;",
        "clear-ref w -1;",
        "is-cleared c w -1;",
        "acquire h N w -1 { }",
        "write-ref w 0 n;",
        "read-data d w 0;",
        "allocate-ref z W 1;",
        "acquire h N w { release h; }"
      })
  void rejectsWrongConstructionReceiverAndExplicitNegativeIndices(String operation) {
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 1 0; shape W weak; program { allocate n N; allocate-ref w W"
                    + " n; "
                    + operation
                    + " }"));
  }

  @Test
  void rejectsInitialWrapperConstruction() {
    assertThrows(
        GirException.class,
        () -> GirParser.parse("gir 1; shape W weak; object 1 W [] []; program {}"));
  }

  @Test
  void receiverEncodingCannotSmuggleAnIndexedOperation() {
    assertThrows(
        IllegalArgumentException.class, () -> new Instruction.ClearReference(1, "w", 0, true));
    assertThrows(
        IllegalArgumentException.class, () -> new Instruction.ReferenceState(1, "c", "w", 0, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Instruction.Acquire(1, "h", "N", "w", 0, List.of(), true));
  }

  @Test
  void supportsOrdinaryPointerAliasesButGuardsEveryLegacyExposureRoute() {
    var good =
        GirParser.parse(
            "gir 1; shape N object 1 0; shape W weak; program { allocate n N; allocate-ref w W n;"
                + " write-ref n 0 w; read-ref alias W n 0; }");
    assertTrue(ReferenceProgram.firstClassGaps(good).isEmpty());
    for (String body :
        List.of(
            "write-weak n 0 n; read-ref alias W n 0;",
            "if-nonzero 0 { write-weak n 0 n; } read-ref alias W n 0;",
            "write-weak n 0 w;",
            "write-weak n 0 n; acquire held W n 0 {}")) {
      var program =
          GirParser.parse(
              "gir 1; shape N object 1 0; shape W weak; program { allocate n N; allocate-ref w W n;"
                  + " "
                  + body
                  + " }");
      assertFalse(ReferenceProgram.firstClassGaps(program).isEmpty());
    }
  }
}
