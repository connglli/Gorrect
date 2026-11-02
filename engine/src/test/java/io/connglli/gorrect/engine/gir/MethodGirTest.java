package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.oracle.HistoryPlan;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.OracleLimitException;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MethodGirTest {
  private static final String HEADER = "gir 1; shape N object 1 1; root saved=null; ";

  @Test
  void signaturesRoundTripWithDefinitionsBeforeMainAndSyntheticParameterIds() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method first(ref N node, long value) { call second(node, value); }
                method second(ref N node, long value) { write-data node 0 value; }
                program { allocate node N; call first(node, 7); }
                """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(
        java.util.List.of(1, 2, 3, 4), program.instructions().map(Instruction::id).toList());
    assertEquals(
        java.util.List.of(5, 6, 7, 8),
        program.methods().stream()
            .flatMap(method -> method.parameters().stream())
            .map(GirProgram.Parameter::declaration)
            .toList());
    String source =
        JavaEmitter.emit(program, ObservationProfile.CONTROLLED_ROOTS, "retained-class").source();
    assertTrue(
        source.contains(
            "private static void helper1(TargetSession session, int callSite, S0 b5, long b6)"));
    assertTrue(source.contains("helper2(session, 1, b5, b6);"));
    assertFalse(source.contains("java.lang.reflect"));
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
    assertThrows(
        GirException.class,
        () -> JavaEmitter.emit(program, ObservationProfile.NATURAL_ROOTS, "retained-class"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "method f(ref N p) {} program { call f(); }",
        "method f(ref N p) {} program { call f(1); }",
        "method f(long p) {} program { call f(null); }",
        "shape M object 1 1; method f(ref N p) {} program { allocate a M; call f(a); }",
        "method f() { call missing(); } program {}",
        "method f() { call f(); } program {}",
        "method a() { repeat 0 { call b(); } } method b() { call a(); } program {}",
        "method f() {} method f() {} program {}",
        "method f(ref N p, long p) {} program {}",
        "method f(ref Missing p) {} program {}",
        "method f() { write-root saved caller; } program { allocate caller N; call f(); }",
        "method f(ref N p) { allocate p N; } program {}",
        "method f(ref N p) { scope { release p; } } program {}",
        "method f(ref N p) { release p; write-root saved p; } program {}"
      })
  void checksEveryDefinitionAndCallWithoutCallerLookup(String source) {
    assertThrows(GirException.class, () -> GirParser.parse(HEADER + source));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "method unused() { checkpoint; } program {}",
        "method unused() { parallel { worker a {} } } program {}",
        "method unused() { allocate n N; write-weak n 0 null; } program {}",
        "method unused() { read-volatile-root n N saved; } program {}",
        "method unused() { allocate n N; compare-set-data value n 0 0 1; } program {}",
        "method unused() { allocate n N; monitor n {} } program {}",
        "shape W weak; method unused() {} program {}",
        "shape W weak; queue q W; method unused() {} program {}",
        "root nativeRoot jni-global=null; method unused() {} program {}"
      })
  void wholeSourceUnsupportedCombinationsIncludeUnusedMethods(String source) {
    var program = GirParser.parse(HEADER + source);
    assertFalse(MethodProgram.unsupported(program).isEmpty());
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
    assertThrows(
        UnsupportedHistoryException.class, () -> HistoryPlan.plan(program, OracleLimits.DEFAULT));
    assertThrows(
        GirException.class,
        () -> JavaEmitter.emit(program, ObservationProfile.CONTROLLED_ROOTS, "retained-class"));
  }

  @Test
  void boundsParameterSlotsCodeAndSaturatedCallExpansionBeforeStorageAllocation() {
    String longs =
        IntStream.range(0, 127).mapToObj(i -> "long p" + i).collect(Collectors.joining(","));
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "method f(" + longs + ") {} program {}"));
    String calls = "call f();".repeat(118);
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "method f() {} program {" + calls + "}"));
    var huge =
        GirParser.parse(
            HEADER + "method f() { repeat 9223372036854775807 {} } program {call f();}");
    assertThrows(OracleLimitException.class, () -> HistoryPlan.plan(huge, OracleLimits.DEFAULT));
    var unused =
        GirParser.parse(HEADER + "method f() { repeat 9223372036854775807 {} } program {}");
    assertEquals(0, MethodProgram.steps(unused, OracleLimits.DEFAULT));
    String methods =
        IntStream.range(0, 65)
            .mapToObj(i -> "method m" + i + "() {} ")
            .collect(Collectors.joining());
    assertThrows(GirException.class, () -> GirParser.parse(HEADER + methods + "program {}"));
  }

  @Test
  void boundsGeneratedInitializationAndUnusedObjectLayoutsBeforeEmission() {
    String roots =
        IntStream.range(0, 2000)
            .mapToObj(index -> "root r" + index + "=null;")
            .collect(Collectors.joining());
    assertThrows(
        GirException.class, () -> GirParser.parse(HEADER + roots + "method f() {} program {}"));
    assertThrows(
        GirException.class,
        () -> GirParser.parse(HEADER + "shape Huge object 60001 0; method f() {} program {}"));
  }

  @Test
  void boundsAllRegisteredFieldMetadataIncludingUnusedShapes() {
    String eight =
        IntStream.range(0, 8)
            .mapToObj(index -> "shape S" + index + " object 60000 0;")
            .collect(Collectors.joining());
    GirParser.parse("gir 1;" + eight + "method unused() {} program {}");
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1;" + eight + "shape Ninth object 60000 0; method unused() {} program {}"));
    String sixty =
        IntStream.range(0, 60)
            .mapToObj(index -> "shape S" + index + " object 60000 0;")
            .collect(Collectors.joining());
    assertThrows(
        GirException.class,
        () -> GirParser.parse("gir 1;" + sixty + "method unused() {} program {}"));
    assertFalse(MethodProgram.selected(GirParser.parse("gir 1;" + sixty + "program {}")));
  }

  @Test
  void boundsExpandedArrayIdentityCountForCallsClonesInitialsAndBothArms() {
    String allocate = "gir 1; shape A refs 0; method make() { allocate a A; } program {repeat ";
    GirParser.parse(allocate + "4096 {call make();}}");
    assertThrows(GirException.class, () -> GirParser.parse(allocate + "4097 {call make();}}"));
    String clone =
        "gir 1; shape A refs 0; object 1 A [] []; root saved=1; method copy(ref A input) {clone"
            + " output input;} program {read-root input A saved; repeat ";
    GirParser.parse(clone + "4095 {call copy(input);}}");
    assertThrows(GirException.class, () -> GirParser.parse(clone + "4096 {call copy(input);}}"));
    String branch =
        "gir 1; shape A refs 0; method make() {allocate a A;} program {if-nonzero 0 "
            + "{repeat 2048 {call make();}} else {repeat ";
    GirParser.parse(branch + "2048 {call make();}}}");
    assertThrows(GirException.class, () -> GirParser.parse(branch + "2049 {call make();}}}"));
    var unused =
        GirParser.parse(
            "gir 1; shape A refs 0; method unused() {repeat 10000 {allocate a A;}} program {}");
    assertEquals(0, MethodProgram.steps(unused, OracleLimits.DEFAULT));
  }

  @Test
  void boundsCumulativeArrayNameMetadataBelowTheCountCeiling() {
    String name = "A".repeat(60_000);
    String source = "gir 1; shape " + name + " refs 0; method unused() {} program {repeat ";
    GirParser.parse(source + "500 {allocate a " + name + ";}}");
    assertThrows(
        GirException.class, () -> GirParser.parse(source + "600 {allocate a " + name + ";}}"));
  }

  @Test
  void boundsActualArrayShapeLiteralsIncludingUnusedBodiesAndInitialization() {
    String accepted = "A".repeat(65_534), rejected = accepted + "A";
    GirParser.parse(
        "gir 1; shape "
            + accepted
            + " refs 0; method unused() {allocate a "
            + accepted
            + ";} program {}");
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape "
                    + rejected
                    + " refs 0; method unused() {allocate a "
                    + rejected
                    + ";} program {}"));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape "
                    + rejected
                    + " refs 0; object 1 "
                    + rejected
                    + " [] []; method unused() {} program {}"));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape "
                    + rejected
                    + " refs 0; method unused(ref "
                    + rejected
                    + " input) {clone copy input;} program {}"));
    assertFalse(
        MethodProgram.selected(
            GirParser.parse("gir 1; shape " + rejected + " refs 0; program {}")));
  }

  @Test
  void boundsUnusedWideArgumentCallCodeBeforeGeneration() {
    String parameters =
        IntStream.range(0, 253)
            .mapToObj(index -> "ref N p" + index)
            .collect(Collectors.joining(","));
    String arguments =
        java.util.Collections.nCopies(253, "fresh").stream().collect(Collectors.joining(","));
    String declarations =
        "gir 1; shape N object 0 0; method target("
            + parameters
            + ") {} method caller("
            + parameters
            + ") {allocate fresh N; ";
    GirParser.parse(declarations + "call target(" + arguments + ");} program {}");
    var rejected =
        assertThrows(
            GirException.class,
            () ->
                GirParser.parse(
                    declarations
                        + ("call target(" + arguments + ");").repeat(80)
                        + "} program {}"));
    assertTrue(rejected.getMessage().contains("method code"));
  }

  @Test
  void boundsClassConstantPoolAcrossUnusedCallDefinitions() {
    String parameters =
        IntStream.range(0, 126)
            .mapToObj(index -> "long p" + index)
            .collect(Collectors.joining(","));
    var source = new StringBuilder("gir 1; method target(" + parameters + ") {} ");
    long value = 1_000_000_000_000L;
    for (int helper = 0; helper < 7; helper++) {
      source.append("method caller").append(helper).append("() {");
      for (int call = 0; call < 39; call++) {
        source.append("call target(");
        for (int argument = 0; argument < 126; argument++) {
          if (argument != 0) source.append(',');
          source.append(value++);
        }
        source.append(");");
      }
      source.append('}');
    }
    source.append("program {}");
    var rejected = assertThrows(GirException.class, () -> GirParser.parse(source.toString()));
    assertTrue(rejected.getMessage().contains("constant pool"));
  }

  @Test
  void boundsAggregateEmittedArrayLiteralBytesIncludingUnusedBodies() {
    var shapes = new ArrayList<Shape>();
    var body = new ArrayList<Instruction>();
    String prefix = "A".repeat(59_998);
    for (int index = 0; index < 70; index++) {
      String name = prefix + "_" + index;
      shapes.add(new Shape(name, Shape.Kind.REFERENCE_ARRAY, 0, 0));
      body.add(new Instruction.Allocate(index + 1, "a" + index, name));
    }
    var allowed =
        new GirProgram(
            1,
            shapes.subList(0, 69),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Set.of(),
            List.of(),
            List.of(new GirProgram.Method("unused", List.of(), body.subList(0, 69))));
    GirValidator.validate(allowed);
    var rejected =
        new GirProgram(
            1,
            shapes,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Set.of(),
            List.of(),
            List.of(new GirProgram.Method("unused", List.of(), body)));
    var failure = assertThrows(GirException.class, () -> GirValidator.validate(rejected));
    assertTrue(failure.getMessage().contains("constant bytes"));
  }

  @Test
  void combinesCallAndLexicalNestingAcrossDefinitionsAndMain() {
    String leaf = "method leaf() {} ";
    String helper = "method f() {" + "scope {".repeat(63) + "call leaf();" + "}".repeat(64);
    String accepted =
        HEADER + leaf + helper + "program {" + "scope {".repeat(63) + "call f();" + "}".repeat(64);
    Oracle.interpret(GirParser.parse(accepted));
    String rejected =
        HEADER + leaf + helper + "program {" + "scope {".repeat(64) + "call f();" + "}".repeat(65);
    assertThrows(GirException.class, () -> GirParser.parse(rejected));
  }

  @Test
  void bindingNamesAndParameterReleaseAreLocalToEachFrame() {
    var program =
        GirParser.parse(
            HEADER
                + """
                method f(ref N node, long value) { write-data node 0 value; release node; }
                program { allocate node N; call f(node, 42); write-root saved node; }
                """);
    assertEquals(
        42L,
        Oracle.interpret(program)
            .finalGraph()
            .objects()
            .values()
            .iterator()
            .next()
            .data()
            .getFirst());
  }
}
