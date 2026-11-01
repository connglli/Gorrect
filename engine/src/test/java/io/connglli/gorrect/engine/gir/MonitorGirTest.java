package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.observe.NaturalLiveness;
import io.connglli.gorrect.engine.observe.ObservationCapabilities;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonitorGirTest {
  private static final String HEADER =
      "gir 1; shape N object 1 1; object 1 N [null] [7]; root main=1; ";

  @Test
  void roundTripsNestedAndSkippedBlocksAndVisitsEachBodyOnce() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { read-root owner N main;
                  outer: monitor owner {
                    repeat 0 { skipped: monitor owner { allocate fresh N; } }
                    if-nonzero 1 { monitor owner {} } else { monitor owner {} }
                    scope { monitor owner {} }
                  }
                }
                """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(
        IntStream.rangeClosed(1, 10).boxed().toList(),
        program.instructions().map(Instruction::id).toList());
    assertEquals("outer", program.labels().get(2));
    assertTrue(MonitorProgram.usesMonitors(program));
    var children = new ArrayList<Instruction>();
    var immutable = new Instruction.Monitor(1, "owner", children);
    children.add(new Instruction.Collect(2));
    assertEquals(List.of(), immutable.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "monitor null {}",
        "monitor 1 {}",
        "monitor absent {}",
        "read-data scalar owner 0; monitor scalar {}",
        "release owner; monitor owner {}",
        "monitor owner { release owner; }",
        "monitor owner { allocate owner N; }",
        "repeat 0 { monitor owner { read-data value absent 0; } }",
        "if-nonzero 0 { monitor absent {} }"
      })
  void staticValidationCoversAllBodiesAndKeepsOwnerInTheOuterScope(String body) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "program { read-root owner N main; " + body + " }"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "parallel { worker a {} worker b {} }", "read-volatile-root shared N main;",
        "compare-set-data outcome owner 0 7 8;", "repeat 0 { write-weak owner 0 null; }",
        "repeat 0 { poll-queue; }"
      })
  void skippedMonitorsStillGateUnsupportedCompositions(String extra) {
    var program =
        GirParser.parse(
            HEADER
                + "program { read-root owner N main; repeat 0 { monitor owner {} } "
                + extra
                + " }");
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
  }

  @Test
  void unusedWrapperDeclarationsAndEveryNaturalEntryRemainUnsupported() {
    var wrappers =
        GirParser.parse(
            HEADER + "shape W weak; program { read-root owner N main; monitor owner {} }");
    assertThrows(GirException.class, () -> JavaEmitter.emit(wrappers));
    var program =
        GirParser.parse(
            HEADER + "program { read-root owner N main; repeat 0 { monitor owner {} } }");
    var oracle = Oracle.interpret(program);
    for (var profile : List.of(ObservationProfile.NATURAL_ROOTS)) {
      assertTrue(!ObservationCapabilities.unsupported(program, profile).isEmpty());
      assertThrows(GirException.class, () -> JavaEmitter.emit(program, profile));
    }
    assertThrows(UnsupportedHistoryException.class, () -> NaturalLiveness.analyze(program));
    assertThrows(UnsupportedHistoryException.class, () -> NaturalLiveness.analyze(program, oracle));
  }
}
