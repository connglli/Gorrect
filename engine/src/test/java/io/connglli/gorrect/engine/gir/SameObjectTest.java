package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SameObjectTest {
  private static final String HEADER =
      """
      gir 1; shape N object 0 1; shape Other object 0 0;
      object 1 N [] [7]; object 2 N [] [7]; object 3 Other [] [];
      root first = 1; root second = 2; root other = 3;
      """;

  @Test
  void checksAliasesDistinctObjectsDifferentShapesAndNulls() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { read-root a N first; read-root b N second; read-root c Other other;
                  same-object self a a; same-object distinct a b; same-object types a c;
                  same-object nil null null; same-object mixed a null;
                }
                """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(
        List.of(1L, 0L, 0L, 1L, 0L),
        Oracle.interpret(program).events().stream()
            .filter(event -> event.kind() == ExecutionTrace.Kind.SCALAR_READ)
            .map(ExecutionTrace.Event::value)
            .toList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "same-object result 1 null;",
        "same-object result missing a;",
        "read-data value a 0; same-object result value a;",
        "release a; same-object result a null;",
        "same-object a a null;"
      })
  void rejectsInvalidReferenceOperandsAndNames(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root a N first; " + body + " }"));
  }
}
