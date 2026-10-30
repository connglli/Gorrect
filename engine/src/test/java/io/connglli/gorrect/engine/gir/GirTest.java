package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.Oracle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GirTest {
  private static final String HEADER =
      """
      gir 1;
      shape N object 1 1;
      object 1 N [null] [0];
      root r = 1;
      root empty = null;
      """;

  @Test
  void labelsAreOptionalAndPreservedOnOperationsAndBlocks() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  setup: scope {
                    created: allocate a N;
                    write-root r a;
                    before_gc: checkpoint;
                  }
                  after_gc: checkpoint;
                }
                """);
    assertEquals(Map.of(1, "setup", 2, "created", 4, "before_gc", 5, "after_gc"), program.labels());
    String formatted = GirFormatter.format(program);
    assertEquals(program, GirParser.parse(formatted));
    assertTrue(formatted.contains("created: allocate a N;"));
    assertTrue(formatted.contains("    write-root r a;"));
    assertTrue(!Pattern.compile("(?m)^\\s*[0-9]+:").matcher(formatted).find());
  }

  @Test
  void insertingInstructionsDoesNotRequireChangingLabels() {
    var original = GirParser.parse(HEADER + "program { after: checkpoint; }");
    var edited = GirParser.parse(HEADER + "program { checkpoint; after: checkpoint; }");
    assertEquals(Map.of(1, "after"), original.labels());
    assertEquals(Map.of(2, "after"), edited.labels());
    assertEquals(
        "after", edited.labels().get(Oracle.interpret(edited).checkpoints().getLast().operation()));
  }

  @Test
  void labelsCanShareNamesWithKeywordsAndBindings() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  a: allocate a N;
                  repeat: repeat 2 {
                    checkpoint: // the label can precede its instruction on another line
                      checkpoint;
                  }
                }
                """);
    assertEquals(Map.of(1, "a", 2, "repeat", 3, "checkpoint"), program.labels());
    var result = Oracle.interpret(program);
    assertEquals(
        List.of(1L, 2L),
        result.checkpoints().stream().map(checkpoint -> checkpoint.occurrence()).toList());
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
  }

  @Test
  void programmaticLabelsCannotBeInvalidOrSilentlyDiscarded() {
    var base = GirParser.parse(HEADER + "program { checkpoint; checkpoint; }");
    List<Map<Integer, String>> invalid =
        List.of(
            Map.of(99, "missing"),
            Map.of(1, "same", 2, "same"),
            Map.of(1, "not-a-name"),
            Map.of(1, "null"));
    for (var labels : invalid) {
      var program =
          new GirProgram(1, base.shapes(), base.objects(), base.roots(), base.body(), labels);
      assertThrows(GirException.class, () -> GirFormatter.format(program));
    }
  }

  @Test
  void documentationExampleExecutesAsDescribed() throws IOException {
    try (var input = getClass().getResourceAsStream("/docs/gir.md")) {
      String document =
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
              .replace("\r\n", "\n");
      String source = document.split("```text\\n", 2)[1].split("```", 2)[0];
      var result = Oracle.interpret(GirParser.parse(source));
      var graph = result.finalGraph();
      var parentId = graph.roots().get("main").target();
      var parent = graph.objects().get(parentId);
      var childId = parent.references().get(0).target();
      var freshId = parent.references().get(1).target();
      assertEquals(2, result.checkpoints().size());
      assertEquals(3, result.checkpoints().getFirst().graph().handles().size());
      assertTrue(result.checkpoints().getLast().graph().handles().isEmpty());
      assertEquals(ObjectId.initial(1), parentId);
      assertEquals(ObjectId.initial(2), childId);
      assertTrue(freshId.site() > 0);
      assertEquals(1, freshId.occurrence());
      assertEquals(3, graph.objects().size());
      assertEquals(List.of(7L), parent.data());
      assertEquals(List.of(42L), graph.objects().get(childId).data());
      assertEquals(List.of(9L), graph.objects().get(freshId).data());
      assertEquals(
          List.of(Value.Reference.NULL, Value.Reference.NULL),
          graph.objects().get(freshId).references());
    }
  }

  @Test
  void canonicalFormatPreservesTheEntireProgramAndOperationIds() throws IOException {
    try (var input = getClass().getResourceAsStream("/cases/core.gir")) {
      String source =
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
              .replace("\r\n", "\n");
      var program = GirParser.parse(source);
      assertEquals(program, GirParser.parse(GirFormatter.format(program)));
      assertEquals(source, GirFormatter.format(program));
      assertEquals(program, GirParser.parse("// comment\r\n" + source.replace("\n", "\r\n")));
      assertEquals(program, GirParser.parse("// comment\r" + source.replace("\n", "\r")));
    }
  }

  @Test
  void handlesAllShapeKindsAndSignedLongValues() {
    var program =
        GirParser.parse(
            """
            gir 1;
            shape Empty object 0 0;
            shape Links refs 2;
            shape Payload data 2;
            object 1 Links [1, 2] [];
            object 2 Payload [] [-9223372036854775808, 9223372036854775807];
            root array = 1;
            program { checkpoint; }
            """);
    assertEquals(List.of(Long.MIN_VALUE, Long.MAX_VALUE), program.objects().get(1).data());
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "read-root a Missing r;",
        "read-root a N missing;",
        "write-root missing null;",
        "write-root r 42;",
        "allocate a N; allocate a N;",
        "allocate a N; scope { allocate a N; }",
        "allocate a N; release a; write-root r a;",
        "allocate a N; release a; allocate a N;",
        "allocate a N; scope { release a; }",
        "allocate a N; release a; release a;",
        "release unknown;",
        "allocate a N; read-ref b Missing a 0;",
        "allocate a N; write-ref a 1 null;",
        "allocate a N; write-data a -1 0;",
        "allocate a N; write-data a 0 a;",
        "allocate a N; write-ref a 0 7;",
        "allocate a N; read-data x a 0; write-root r x;",
        "allocate a N; read-data x a 0; read-ref b N x 0;",
        "scope { allocate a N; } write-root r a;",
        "same: checkpoint; same: checkpoint;",
        "same: scope { same: checkpoint; }",
        "repeat -1 {}",
        "repeat 0 { allocate a N; write-ref a 99 null; }"
      })
  void rejectsInvalidStaticContractsEvenInUnexecutedBlocks(String body) {
    assertThrows(GirException.class, () -> GirParser.parse(HEADER + "program {" + body + "}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gir 2; program {}",
        "gir 99; program {}",
        "gir 1; shape N object -1 0; program {}",
        "gir 1; shape N object 0 0; shape N refs 0; program {}",
        "gir 1; shape null object 0 0; program {}",
        "gir 1; shape N weak 1; program {}",
        "gir 1; object 1 Missing [] []; program {}",
        "gir 1; shape N object 1 0; object 1 N [] []; program {}",
        "gir 1; shape N object 1 0; object 1 N [2] []; program {}",
        "gir 1; shape N object 0 0; object 1 N [] []; object 1 N [] []; program {}",
        "gir 1; root r = 1; program {}",
        "gir 1; root r = null; root r = null; program {}",
        "gir 1; root r = 0; program {}",
        "gir 1; program { weak-acquire; }",
        "gir 1; program { worker; }",
        "gir 1; program { checkpoint;",
        "gir 1; program {} trailing",
        "gir 1; program { 1: checkpoint; }",
        "gir 1; program { null: checkpoint; }",
        "gir 1; program { missing: }",
        "gir 1; root r = 9223372036854775808; program {}",
        "gir 1; shape Bad refs 2147483648; program {}"
      })
  void rejectsInvalidDeclarationsAndUnsupportedSyntax(String source) {
    assertThrows(GirException.class, () -> GirParser.parse(source));
  }

  @Test
  void syntaxErrorsIdentifyTheInputLocation() {
    var error =
        assertThrows(
            GirException.class, () -> GirParser.parse("gir 1;\nprogram {\n  checkpoint\n}\n"));
    assertTrue(error.getMessage().startsWith("4:1:"), error.getMessage());
  }

  @Test
  void excessiveNestingIsAnExplicitError() {
    var source = new StringBuilder("gir 1; program {");
    for (int id = 1; id <= 130; id++) source.append("scope {");
    source.append("}".repeat(131));
    var error = assertThrows(GirException.class, () -> GirParser.parse(source.toString()));
    assertTrue(error.getMessage().contains("nesting limit"));
  }

  @Test
  void programmaticReferencesUseTheHistoryOracle() {
    var base = GirParser.parse(HEADER + "program {}");
    var object = base.objects().getFirst();
    for (var kind : List.of(Value.ReferenceKind.SOFT, Value.ReferenceKind.WEAK)) {
      var changed =
          new GirProgram.InitialObject(
              object.id(), object.shape(), List.of(new Value.Reference(kind, null)), object.data());
      var program = new GirProgram(1, base.shapes(), List.of(changed), base.roots(), base.body());
      GirValidator.validate(program);
      assertThrows(GirException.class, () -> Oracle.interpret(program));
    }
  }

  @Test
  void canonicalFormattingDoesNotDiscardInvalidProgrammaticInputs() {
    for (int id : List.of(0, 7)) {
      var invalid =
          new GirProgram(
              1, List.of(), List.of(), List.of(), List.of(new Instruction.Checkpoint(id)));
      assertThrows(GirException.class, () -> GirFormatter.format(invalid));
    }
  }
}
