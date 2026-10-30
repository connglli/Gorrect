package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScalarBranchGirTest {
  private static final String HEADER =
      "gir 1; shape N object 0 1; object 1 N [] [7]; root main = 1; ";

  @Test
  void roundTripsBothArmsAndKeywordLabelsInSourcePreorder() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  owner: read-root owner N main;
                  value: read-data value owner 0;
                  choice: if-nonzero value {
                    left: allocate local N;
                  } else {
                    zero: repeat 0 { right: allocate local N; }
                  }
                  empty: if-nonzero 0 {} else {}
                  else: checkpoint;
                }
                """);
    var expected = List.of("owner", "value", "choice", "left", "zero", "right", "empty", "else");
    assertEquals(
        expected,
        program.instructions().map(instruction -> program.labels().get(instruction.id())).toList());
    String canonical = GirFormatter.format(program);
    assertEquals(program, GirParser.parse(canonical));
    assertTrue(canonical.contains("} else {"));
    assertTrue(canonical.contains("empty: if-nonzero 0 {\n  }\n  else: checkpoint;"));
    assertFalse(HistoryProgram.usesConcurrentHistory(program));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "if-nonzero owner {}",
        "if-nonzero null {}",
        "if-nonzero missing {}",
        "read-data value owner 0; release value; if-nonzero value {}",
        "if-nonzero 0 {} else { write-root missing null; }",
        "repeat 0 { if-nonzero 1 {} else { read-root missing Missing main; } }",
        "if-nonzero 0 { release owner; }",
        "if-nonzero 1 {} else { allocate owner N; }",
        "if-nonzero 1 { allocate local N; } write-data local 0 7;",
        "if-nonzero 0 { barrier; }",
        "if-nonzero 0 { parallel { worker a { checkpoint; } } }",
        "parallel { worker a { if-nonzero 0 { checkpoint; } } }",
        "parallel { worker a { if-nonzero 1 {} else { barrier; } } }",
        "if-nonzero 9223372036854775808 {}"
      })
  void validatesTypesScopesAndBothUnexecutedArms(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root owner N main; " + body + " }"));
  }

  @Test
  void allowsTheSameLocalNameInSeparateArmsAndAnOmittedElse() {
    var program =
        GirParser.parse(
            HEADER
                + "program { if-nonzero -1 { allocate local N; } else { allocate local N; }"
                + " if-nonzero -9223372036854775808 {} }");
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(4, program.instructions().count());
  }
}
