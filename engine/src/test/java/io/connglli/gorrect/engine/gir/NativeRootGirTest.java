package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.observe.NaturalLiveness;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.oracle.HistoryPlan;
import io.connglli.gorrect.engine.oracle.Oracle;
import io.connglli.gorrect.engine.oracle.OracleLimits;
import io.connglli.gorrect.engine.oracle.UnsupportedHistoryException;
import io.connglli.gorrect.protocol.NativeRootPlan;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeRootGirTest {
  private static final String HEADER = "gir 1; shape N object 1 1; root saved jni-global=null; ";

  @Test
  void roundTripsAndRecoversAfterAllJavaHandlesAreReleased() {
    var program =
        GirParser.parse(
            HEADER
                + """
                program {
                  scope { allocate item N; write-data item 0 42; write-root saved item; }
                  collect; checkpoint;
                  scope { read-root recovered N saved; write-root saved null;
                    read-data value recovered 0; checkpoint; }
                }
                """);
    assertEquals(NativeRootPlan.MAX_GENERATIONS, OracleLimits.DEFAULT.steps());
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals(GirProgram.RootStorage.JNI_GLOBAL, program.roots().getFirst().storage());
    var result = Oracle.interpret(program);
    assertEquals(
        new ObjectId(0, 2, 1),
        result.checkpoints().getFirst().graph().roots().get("saved").target());
    assertEquals(1, result.checkpoints().get(1).graph().required().objects().size());
    assertTrue(result.finalGraph().required().objects().isEmpty());
    String source = JavaEmitter.emit(program, ObservationProfile.CONTROLLED_ROOTS).source();
    assertTrue(source.contains("TargetSession.openNative(args, Case.class"));
    assertTrue(source.contains("session.writeNativeRoot(0, b2)"));
    assertTrue(source.contains("session.readNativeRoot(0, S0.class, \"N\")"));
    assertFalse(source.contains("public static Object r0;"));
    assertEquals(
        java.util.List.of("saved"), JavaEmitter.emit(program).layout().nativeRootPlan().roots());
  }

  @Test
  void nativeSelectionRoundTripsAndLegacyLayoutsOmitItsField() {
    var selected = JavaEmitter.emit(GirParser.parse(HEADER + "program {}"));
    String json = JsonFiles.GSON.toJson(selected.layout());
    assertEquals(selected.layout(), JsonFiles.GSON.fromJson(json, ObservationLayout.class));
    var legacy = JavaEmitter.emit(GirParser.parse("gir 1; root saved=null; program {}"));
    assertFalse(JsonFiles.GSON.toJson(legacy.layout()).contains("nativeRootPlan"));
    assertFalse(legacy.source().contains("NativeRoot"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "read-volatile-root shared N saved;",
        "repeat 0 { allocate item N; write-weak item 0 null; }",
        "parallel { worker a {} worker b {} }",
        "allocate item N; repeat 0 { compare-set-data old item 0 0 1; }"
      })
  void wholeSourceRejectsSkippedUnsupportedCombinations(String body) {
    var program = GirParser.parse(HEADER + "program {" + body + "}");
    assertThrows(GirException.class, () -> JavaEmitter.emit(program));
    assertThrows(UnsupportedHistoryException.class, () -> Oracle.interpret(program));
    assertThrows(
        UnsupportedHistoryException.class, () -> HistoryPlan.plan(program, OracleLimits.DEFAULT));
  }

  @Test
  void nativeStorageRequiresNullAndEnforcesTheFixedSlotBound() {
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 0 0; object 1 N [] []; root bad jni-global=1; program {}"));
    String slots =
        IntStream.range(0, 65)
            .mapToObj(i -> "root r" + i + " jni-global=null;")
            .collect(java.util.stream.Collectors.joining());
    assertThrows(GirException.class, () -> GirParser.parse("gir 1;" + slots + "program {}"));
    assertThrows(GirException.class, () -> GirParser.parse(HEADER + "root saved=null; program {}"));
    var program = GirParser.parse(HEADER + "program {}");
    assertThrows(UnsupportedHistoryException.class, () -> NaturalLiveness.analyze(program));
    assertThrows(
        GirException.class, () -> JavaEmitter.emit(program, ObservationProfile.NATURAL_ROOTS));
  }
}
