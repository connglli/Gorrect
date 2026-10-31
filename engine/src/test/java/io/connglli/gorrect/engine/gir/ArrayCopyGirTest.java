package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArrayCopyGirTest {
  private static final String HEADER =
      "gir 1; shape A data 4; shape R refs 4; shape N object 0 0;"
          + " root source = null; root destination = null; root refs = null; root node = null;";

  @Test
  void preservesLabelsRangesAndSourceRoundtrip() {
    var program =
        GirParser.parse(
            HEADER
                + "program { read-root a A source; read-root b A destination;"
                + " move: copy-array a 0 b 1 3; }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals("move", program.labels().get(3));
    var copy = (Instruction.CopyArray) program.body().getLast();
    assertEquals(0, copy.sourceIndex());
    assertEquals(1, copy.destinationIndex());
    assertEquals(3, copy.length());
    assertTrue(JavaEmitter.emit(program).source().contains("System.arraycopy("));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "copy-array a -1 b 0 0;",
        "copy-array a 0 b -1 0;",
        "copy-array a 0 b 0 -1;",
        "copy-array a 4 b 0 1;",
        "copy-array a 0 b 4 1;",
        "copy-array a 1 b 0 4;",
        "copy-array a 0 b 1 4;",
        "copy-array a 2147483647 b 0 1;",
        "copy-array a 0 b 0 2147483648;",
        "copy-array a 0 r 0 0;",
        "copy-array n 0 b 0 0;",
        "copy-array value 0 b 0 0;",
        "copy-array missing 0 b 0 0;"
      })
  void rejectsTypesAndRangesEvenInSkippedBlocks(String instruction) {
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                HEADER
                    + "program {"
                    + " read-root a A source; read-root b A destination; read-root r R refs;"
                    + " read-root n N node; same-object value a b; repeat 0 {"
                    + instruction
                    + "} }"));
  }

  @Test
  void permitsEndIndexForAnEmptyRangeWithoutOverflow() {
    var program =
        GirParser.parse(
            "gir 1; shape A data 2147483647; root source = null; program { read-root a A source;"
                + " if-present a { copy-array a 2147483647 a 2147483647 0; } }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    Oracle.interpret(program);
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape A data 2147483647;"
                    + " root source = null; program { read-root a A source; repeat 0 {"
                    + " copy-array a 2147483646 a 0 2; } }"));
  }

  @Test
  void rejectsNaturalObservationBeforeEmission() {
    var program =
        GirParser.parse(HEADER + "program { read-root a A source;" + " copy-array a 0 a 0 0; }");
    assertThrows(
        GirException.class,
        () -> JavaEmitter.emit(program, ObservationProfile.parse("roots-natural")));
  }

  @Test
  void reportsUnsupportedReferenceCompositionBeforeExactInterpretation() {
    var program =
        GirParser.parse(
            HEADER
                + " shape S object 1 0; object 1 S [weak(1)] []; root weak = 1; program {"
                + " read-root a A source; copy-array a 0 a 0 0; }");
    assertTrue(ArrayCopyProgram.unsupported(program).size() > 0);
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
  }
}
