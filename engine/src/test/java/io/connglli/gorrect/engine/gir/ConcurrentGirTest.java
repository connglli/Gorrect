package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConcurrentGirTest {
  @Test
  void roundTripsNamedWorkersLabelsBarriersAndRequirements() {
    var source =
        """
        gir 1; require concurrent-overlap; require compiled-execution;
        shape N object 1 1; root r = null;
        program { rounds: repeat 2 { group: parallel {
          worker alpha { fresh: allocate n N; collect; boundary: barrier; release n; }
          worker beta { allocate n N; barrier; release n; }
        } } done: checkpoint; }
        """;
    var program = GirParser.parse(source);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    var parallel =
        (Instruction.Parallel) ((Instruction.Repeat) program.body().getFirst()).body().getFirst();
    assertEquals(List.of(1, 2), parallel.workers().stream().map(Instruction.Worker::id).toList());
    assertEquals(
        List.of("alpha", "beta"),
        parallel.workers().stream().map(Instruction.Worker::name).toList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gir 1; program { parallel { worker a {} } }",
        "gir 1; program { collect; }",
        "gir 1; require concurrent-overlap; program {}"
      })
  void initialVersionAcceptsAllImplementedFeatures(String source) {
    var program = GirParser.parse(source);
    assertEquals(1, program.version());
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gir 1; require unknown; program {}",
        "gir 1; program { barrier; }",
        "gir 1; program { parallel {} }",
        "gir 1; program { parallel { worker same {} worker same {} } }",
        "gir 1; program { parallel { worker a { barrier; } worker b {} } }",
        "gir 1; program { parallel { worker a { scope { barrier; } } } }",
        "gir 1; program { parallel { worker a { repeat 0 { barrier; } } } }",
        "gir 1; program { parallel { worker a { checkpoint; } } }",
        "gir 1; program { parallel { worker a { parallel { worker b {} } } } }",
        "gir 1; shape N object 0 0; program { allocate outer N; parallel { worker a { write-root"
            + " missing outer; } } }",
        "gir 1; shape N object 0 0; root r = null; program { allocate outer N; parallel { worker a"
            + " { write-root r outer; } } }"
      })
  void rejectsUnsupportedOrUnsynchronizedStructure(String source) {
    assertThrows(GirException.class, () -> GirParser.parse(source));
  }
}
