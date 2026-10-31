package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.model.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrimitiveShapeGirTest {
  @Test
  void preservesSlotOrderAndCanonicalizesLegacyLongDeclarations() {
    var program =
        GirParser.parse(
            "gir 1; shape N object 2 [int, long, int]; shape L object 0 [long, long]; shape E"
                + " object 0 []; program {}");
    assertEquals(
        List.of(Shape.DataKind.INT, Shape.DataKind.LONG, Shape.DataKind.INT),
        program.shapes().getFirst().dataKinds());
    String formatted = GirFormatter.format(program);
    assertTrue(formatted.contains("shape N object 2 [int, long, int];"));
    assertTrue(formatted.contains("shape L object 0 2;"));
    assertEquals(program, GirParser.parse(formatted));
  }

  @Test
  void oldAndNullJsonKindsMeanAllLongAndMixedKindsRoundTrip() {
    var legacy = new Shape("N", Shape.Kind.OBJECT, 2, 3);
    String old = "{\"name\":\"N\",\"kind\":\"OBJECT\",\"references\":2,\"data\":3}";
    assertEquals(legacy, JsonFiles.GSON.fromJson(old, Shape.class));
    assertEquals(
        legacy, JsonFiles.GSON.fromJson(old.replace("}", ",\"dataKinds\":null}"), Shape.class));
    var mixed =
        new Shape("N", Shape.Kind.OBJECT, 0, List.of(Shape.DataKind.INT, Shape.DataKind.LONG));
    assertEquals(mixed, JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(mixed), Shape.class));
  }

  @Test
  void implicitLongSerializationStaysBoundedByShapeCountRatherThanArrayLength() {
    var array = new Shape("Large", Shape.Kind.DATA_ARRAY, 0, Integer.MAX_VALUE);
    String json = JsonFiles.GSON.toJson(array);
    assertFalse(json.contains("dataKinds"));
    assertTrue(json.length() < 150);
    assertEquals(array, JsonFiles.GSON.fromJson(json, Shape.class));
    assertFalse(
        JsonFiles.GSON.toJson(new Shape("N", Shape.Kind.OBJECT, 0, 3)).contains("dataKinds"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reconstructsMaximumCountFromItsOwnKindsWithoutMaterializingThem(boolean inferred) {
    var original = new Shape("Large", Shape.Kind.DATA_ARRAY, 0, Integer.MAX_VALUE);
    var copy =
        inferred
            ? new Shape(
                original.name(), original.kind(), original.references(), original.dataKinds())
            : new Shape(
                original.name(),
                original.kind(),
                original.references(),
                original.data(),
                original.dataKinds());
    assertEquals(original, copy);
    assertEquals(original.hashCode(), copy.hashCode());
    assertEquals(Shape.DataKind.LONG, copy.dataKinds().get(Integer.MAX_VALUE - 1));
    assertTrue(JsonFiles.GSON.toJson(copy).length() < 150);
    assertThrows(
        UnsupportedOperationException.class, () -> copy.dataKinds().set(0, Shape.DataKind.INT));
  }

  @Test
  void canonicalizingExplicitLongsPreservesInputValidationAndOwnership() {
    var kinds = new ArrayList<>(List.of(Shape.DataKind.LONG, Shape.DataKind.LONG));
    var shape = new Shape("N", Shape.Kind.OBJECT, 0, kinds);
    kinds.set(0, Shape.DataKind.INT);
    assertEquals(List.of(Shape.DataKind.LONG, Shape.DataKind.LONG), shape.dataKinds());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Shape(
                "N",
                Shape.Kind.DATA_ARRAY,
                0,
                1,
                Collections.nCopies(Integer.MAX_VALUE, Shape.DataKind.LONG)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Shape(
                "N",
                Shape.Kind.DATA_ARRAY,
                0,
                Integer.MAX_VALUE,
                Collections.nCopies(Integer.MAX_VALUE, Shape.DataKind.INT)));
    assertThrows(
        NullPointerException.class,
        () -> new Shape("N", Shape.Kind.OBJECT, 0, 2, Arrays.asList(Shape.DataKind.LONG, null)));
    assertThrows(
        NullPointerException.class,
        () -> new Shape("N", Shape.Kind.OBJECT, 0, Arrays.asList(Shape.DataKind.LONG, null)));
    assertThrows(
        NullPointerException.class,
        () -> new Shape("N", Shape.Kind.OBJECT, 0, (List<Shape.DataKind>) null));
  }

  @Test
  void rejectsWrongCountsAndNonObjectIntFieldsAndOwnsItsKindList() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Shape("N", Shape.Kind.OBJECT, 0, 2, List.of(Shape.DataKind.INT)));
    for (var kind : Shape.Kind.values())
      if (kind != Shape.Kind.OBJECT)
        assertThrows(
            IllegalArgumentException.class,
            () -> new Shape("N", kind, 0, 1, List.of(Shape.DataKind.INT)));
    var kinds = new ArrayList<>(List.of(Shape.DataKind.INT));
    var shape = new Shape("N", Shape.Kind.OBJECT, 0, kinds);
    kinds.set(0, Shape.DataKind.LONG);
    assertEquals(List.of(Shape.DataKind.INT), shape.dataKinds());
    assertThrows(
        UnsupportedOperationException.class, () -> shape.dataKinds().set(0, Shape.DataKind.LONG));
    assertThrows(
        RuntimeException.class,
        () ->
            JsonFiles.GSON.fromJson(
                "{\"name\":\"N\",\"kind\":\"OBJECT\",\"references\":0,\"data\":1,\"dataKinds\":[\"FLOAT\"]}",
                Shape.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"[byte]", "[INT]", "[int,]", "[int long]", "[null]", "[-1]", "[int", "-1"})
  void rejectsMalformedDeclarations(String kinds) {
    assertThrows(
        GirException.class,
        () -> GirParser.parse("gir 1; shape N object 0 " + kinds + "; program {}"));
  }

  @Test
  void explicitKindsStillRequireMatchingInitialDataAndValidateUnexecutedBlocks() {
    assertThrows(
        GirException.class,
        () -> GirParser.parse("gir 1; shape N object 0 [int]; object 1 N [] []; program {}"));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 0 [int]; root r = null; program { repeat 0 { read-root n N"
                    + " r; write-data n 1 7; } }"));
  }
}
