package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicGirTest {
  private static final String HEADER =
      "gir 1; shape N object 1 1; object 1 N [null] [7]; root r = 1; ";

  @Test
  void roundTripsVolatileAtomicAndOwnershipOperations() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  read-volatile-root owner N r;
                  read-volatile-ref child N owner 0;
                  write-volatile-ref owner 0 child;
                  read-volatile-data value owner 0;
                  write-volatile-data owner 0 value;
                  compare-set-root won r owner null;
                  compare-set-ref linked owner 0 null owner;
                  compare-set-data changed owner 0 value 9;
                  exchange-root taken N r owner;
                  exchange-ref prior N owner 0 null;
                  exchange-data old owner 0 value;
                  if-present taken { write-volatile-root r taken; }
                }
                """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertTrue(HistoryProgram.usesConcurrentHistory(program));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "compare-set-root x missing null null;",
        "compare-set-data x owner 0 null 7;",
        "compare-set-ref x owner 1 null null;",
        "exchange-data old owner 0 owner;",
        "exchange-root old Missing r null;",
        "read-data n owner 0; if-present n {}"
      })
  void rejectsInvalidContracts(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root owner N r; " + body + " }"));
  }
}
