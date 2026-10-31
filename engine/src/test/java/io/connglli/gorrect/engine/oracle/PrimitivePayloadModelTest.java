package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.Location;
import io.connglli.gorrect.engine.gir.Operand;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrimitivePayloadModelTest {
  @ParameterizedTest
  @CsvSource({
    "0,0",
    "2147483647,2147483647",
    "2147483648,-2147483648",
    "4294967295,-1",
    "4294967296,0",
    "4294967297,1",
    "-2147483649,2147483647",
    "-4294967295,1",
    "-4294967296,0",
    "9223372036854775807,-1",
    "-9223372036854775808,0"
  })
  void initialAndPlainVolatileStoresUseLiteralJavaConversionCases(long input, long expected) {
    var result =
        execute(
            "gir 1; shape N object 0 [int, long]; object 1 N [] ["
                + input
                + ", "
                + input
                + "]; root r = 1; program { read-root n N r; read-data initial n 0; write-data n 0 "
                + input
                + "; read-data plain n 0; write-volatile-data n 0 "
                + input
                + "; read-volatile-data vol n 0; read-data wide n 1; }");
    assertEquals(
        List.of(expected, expected, expected, input),
        result.events().stream()
            .filter(e -> e.kind() == ExecutionTrace.Kind.SCALAR_READ)
            .map(ExecutionTrace.Event::value)
            .toList());
    assertEquals(
        List.of(expected, input), result.graph().objects().get(ObjectId.initial(1)).data());
  }

  @Test
  void atomicOperandsNormalizeBeforeBothComparisonAndUpdate() {
    var result =
        execute(
            """
            gir 1; shape N object 0 [int, long]; object 1 N [] [-1, 4294967295]; root r = 1;
            program {
              read-root n N r;
              compare-set-data success n 0 4294967295 2147483648;
              compare-set-data failure n 0 2147483647 5;
              exchange-data old n 0 4294967297;
              read-data current n 0;
              compare-set-data wideFailure n 1 -1 7;
              exchange-data wideOld n 1 -9223372036854775808;
            }
            """);
    assertEquals(
        List.of(1L, 0L, -2147483648L, 1L, 0L, 4294967295L),
        result.events().stream()
            .filter(
                e ->
                    e.kind() == ExecutionTrace.Kind.SCALAR_READ
                        || e.kind() == ExecutionTrace.Kind.COMPARE_SET)
            .map(ExecutionTrace.Event::value)
            .toList());
    assertEquals(
        List.of(1L, Long.MIN_VALUE), result.graph().objects().get(ObjectId.initial(1)).data());
  }

  @Test
  void invalidAtomicInputsRejectWithoutEffects() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 [int]; object 1 N [] [4294967295]; root r = 1; program {"
                + " read-root n N r; }");
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    machine.apply((Instruction.Operation) program.body().getFirst());
    var key = machine.key();
    var events = machine.events();
    var graph = machine.graph();
    assertThrows(
        GirException.class,
        () ->
            machine.apply(
                new Instruction.CompareSet(
                    2,
                    "ok",
                    new Location(Location.Kind.DATA, "n", 0),
                    new Operand.Literal(4294967295L),
                    new Operand.Name("missing"))));
    assertEquals(key, machine.key());
    assertEquals(events, machine.events());
    assertEquals(graph, machine.graph());
    machine.apply(
        new Instruction.CompareSet(
            2,
            "ok",
            new Location(Location.Kind.DATA, "n", 0),
            new Operand.Literal(4294967295L),
            new Operand.Literal(4294967297L)));
    assertEquals(List.of(1L), machine.graph().objects().get(ObjectId.initial(1)).data());
  }

  @Test
  void observationsRetainCorruptUnextendedValuesForRefutation() {
    var shape = new Shape("N", Shape.Kind.OBJECT, 0, List.of(Shape.DataKind.INT));
    var id = ObjectId.initial(1);
    var roots = Map.of("r", Value.Reference.strong(id));
    var expected =
        new Graph(Map.of(id, new HeapObject(shape, List.of(), List.of(1L))), roots, Map.of());
    var observed =
        new Graph(
            Map.of(id, new HeapObject(shape, List.of(), List.of(4294967297L))), roots, Map.of());
    assertEquals(4294967297L, observed.objects().get(id).data().getFirst());
    assertEquals(
        ExactVerifier.Verdict.VIOLATED, ExactVerifier.verify(expected, observed, true).verdict());
  }

  private static GraphMachine execute(String source) {
    var program = GirParser.parse(source);
    var machine = new GraphMachine(program, OracleLimits.DEFAULT);
    for (var instruction : program.body()) machine.apply((Instruction.Operation) instruction);
    return machine;
  }
}
