package io.connglli.gorrect.engine.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirFormatter;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.oracle.Oracle;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkloadGeneratorTest {
  @ParameterizedTest
  @ValueSource(ints = {2, 4, 16})
  void seedsProduceValidReproducibleProgramsWithinBudgets(int workers) {
    for (long seed : new long[] {0, 1, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
      var program = WorkloadGenerator.generate(seed, "concurrent-jit", workers);
      assertEquals(program, WorkloadGenerator.generate(seed, "concurrent-jit", workers));
      assertEquals(program, GirParser.parse(GirFormatter.format(program)));
      var model = Oracle.interpret(program);
      assertEquals(
          workers + 1, model.events().stream().map(event -> event.worker()).distinct().count());
      assertEquals(model.events().size(), model.steps());
      assertTrue(model.finalGraph().handles().isEmpty());
      assertTrue(model.finalGraph().objects().keySet().stream().anyMatch(id -> id.worker() > 0));
    }
  }

  @Test
  void rejectionDoesNotChangeAnotherSeedsResult() {
    var before = WorkloadGenerator.generate(42, "concurrent", 2);
    assertThrows(
        IllegalArgumentException.class, () -> WorkloadGenerator.generate(42, "unknown", 2));
    assertThrows(
        IllegalArgumentException.class, () -> WorkloadGenerator.generate(42, "concurrent", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkloadGenerator.generate(42, "concurrent", 16, 1000));
    assertEquals(before, WorkloadGenerator.generate(42, "concurrent", 2));
    assertNotEquals(before, WorkloadGenerator.generate(43, "concurrent", 2));
  }

  @Test
  void durationsNeedUnitsAndCannotOverflow() {
    assertEquals(Duration.ofMillis(2), FuzzCampaign.duration("2ms"));
    assertEquals(Duration.ofMinutes(3), FuzzCampaign.duration("3m"));
    assertEquals(Duration.ofHours(1), FuzzCampaign.duration("1h"));
    for (String value : new String[] {"2", "-1s", "1.5s", "forever", "999999999999999999h"})
      assertThrows(IllegalArgumentException.class, () -> FuzzCampaign.duration(value));
  }
}
