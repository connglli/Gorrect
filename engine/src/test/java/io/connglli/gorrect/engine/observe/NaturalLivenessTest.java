package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NaturalLivenessTest {
  private static final String HEADER = "gir 1; shape N object 0 1; ";

  @Test
  void keepsSemanticUsesAndDropsUnusedAndExpiredBindings() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program { scope {
                  allocate dead N; allocate live N;
                  checkpoint; read-data value live 0;
                  checkpoint; release live; checkpoint;
                } }
                """);
    var oracle = Oracle.interpret(program);
    var live =
        program
            .instructions()
            .filter(
                instruction ->
                    instruction instanceof Instruction.Allocate op && op.name().equals("live"))
            .findFirst()
            .orElseThrow();
    var analysis = NaturalLiveness.analyze(program);
    assertEquals(
        List.of(Set.of(live.id()), Set.of(), Set.of()),
        oracle.checkpoints().stream()
            .map(
                point ->
                    analysis
                        .obligations(point.graph(), point.operation(), point.occurrence())
                        .handles()
                        .keySet())
            .toList());
  }

  @Test
  void distinguishesTheLastLoopVisitFromFutureIterationUses() {
    var program =
        GirParser.parse(
            HEADER
                + """
                object 1 N [] [7]; root r = 1;
                program { read-root outer N r;
                  repeat 3 { read-data value outer 0; checkpoint; }
                }
                """);
    var points = Oracle.interpret(program).checkpoints();
    int binding = program.body().getFirst().id();
    var analysis = NaturalLiveness.analyze(program);
    assertEquals(
        List.of(Set.of(binding), Set.of(binding), Set.of()),
        points.stream().map(point -> analysis.at(point.operation(), point.occurrence())).toList());
    assertEquals(List.of(1L, 2L), analysis.visits(points.getFirst().operation(), binding));
  }

  @Test
  void doesNotBorrowLivenessFromAnotherAllocationActivationOrZeroIterationBody() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  allocate outer N;
                  repeat 2 { allocate temporary N; read-data value temporary 0; checkpoint; }
                  repeat 0 { read-data never outer 0; }
                  checkpoint;
                }
                """);
    var analysis = NaturalLiveness.analyze(program);
    for (var point : Oracle.interpret(program).checkpoints())
      assertEquals(Set.of(), analysis.at(point.operation(), point.occurrence()));
  }

  @Test
  void boundsEmptyLoopAnalysis() {
    var program = GirParser.parse(HEADER + "program { repeat 100000 {} }");
    assertThrows(OracleLimitException.class, () -> NaturalLiveness.analyze(program));
  }

  @Test
  void agreesWithIndependentlyEnumeratedUsePositions() {
    for (int count = 0; count <= 3; count++)
      for (int mask = 0; mask < 32; mask++) {
        boolean beforeOuter = (mask & 1) != 0,
            afterOuter = (mask & 2) != 0,
            beforeInner = (mask & 4) != 0,
            afterInner = (mask & 8) != 0,
            afterLoop = (mask & 16) != 0;
        var program =
            GirParser.parse(
                HEADER
                    + "program { allocate outer N; repeat "
                    + count
                    + " { allocate inner N; "
                    + (beforeOuter ? "read-data a outer 0; " : "")
                    + (beforeInner ? "read-data b inner 0; " : "")
                    + "checkpoint; "
                    + (afterOuter ? "read-data c outer 0; " : "")
                    + (afterInner ? "read-data d inner 0; " : "")
                    + "} checkpoint; "
                    + (afterLoop ? "read-data end outer 0; " : "")
                    + "}");
        var analysis = NaturalLiveness.analyze(program);
        var points = Oracle.interpret(program).checkpoints();
        int outer = program.body().getFirst().id();
        int inner =
            program
                .instructions()
                .filter(
                    instruction ->
                        instruction instanceof Instruction.Allocate op && op.name().equals("inner"))
                .findFirst()
                .orElseThrow()
                .id();
        for (int visit = 0; visit < count; visit++) {
          var expected = new HashSet<Integer>();
          if (afterOuter || afterLoop || visit + 1 < count && (beforeOuter || afterOuter))
            expected.add(outer);
          if (afterInner) expected.add(inner);
          var point = points.get(visit);
          assertEquals(
              expected,
              analysis.at(point.operation(), point.occurrence()),
              "count=" + count + ", mask=" + mask);
        }
        var last = points.getLast();
        assertEquals(
            afterLoop ? Set.of(outer) : Set.of(), analysis.at(last.operation(), last.occurrence()));
      }
  }
}
