package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Physical field widths and call-site types must agree with portable narrowing semantics. */
class PrimitivePayloadRunTest {
  @TempDir Path directory;

  static final String PROGRAM =
      """
      gir 1; shape N object 1 [int, long, int];
      object 1 N [null] [4294967295, 9223372036854775807, 2147483648]; root r = 1;
      program {
        scope {
          read-root n N r;
          read-data initial n 0; read-volatile-data initialMin n 2;
          write-data n 0 4294967297; read-data plain n 0;
          write-volatile-data n 0 4294967295; read-volatile-data vol n 0;
          read-data large n 1;
          compare-set-data success n 0 large 2147483648;
          compare-set-data failure n 0 2147483647 5;
          exchange-data old n 0 4294967297; read-data current n 0;
          compare-set-data wideFailure n 1 -1 7;
          exchange-data wideOld n 1 -9223372036854775808;
        }
        checkpoint; collect; checkpoint;
      }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void executesActualWidthsAndTypedAtomicCallsWithReplay(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, feature), run));
    checkPhysicalTypes(run);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void opaqueHashesPreserveTypedDataChecksAcrossCollection(int feature) throws IOException {
    String source =
        """
        gir 1; shape N object 0 [int, long];
        object 1 N [] [4294967295, 9223372036854775807]; root r = 1;
        program {
          scope {
            read-root n N r; read-root alias N r;
            read-data initial n 0; identity-hash n;
            write-data n 0 2147483648; read-data minimum alias 0;
            write-data n 1 -9223372036854775808;
            collect; identity-hash alias;
            read-data wide alias 1;
            write-data alias 0 4294967297; read-data one n 0;
          }
          checkpoint;
        }
        """;
    Path run = directory.resolve("hash-run");
    assertPass(RunEngine.run(input(directory, source), profile(directory, feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("hash-replay")));

    var trace =
        ExecutionTrace.read(
            run.resolve("traces/execution.gtrace"),
            JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong());
    var events = new ArrayList<>(trace.events());
    int index =
        java.util.stream.IntStream.range(0, events.size())
            .filter(i -> events.get(i).kind() == ExecutionTrace.Kind.SCALAR_READ)
            .findFirst()
            .orElseThrow();
    var event = events.get(index);
    assertEquals(-1, event.value());
    events.set(index, new ExecutionTrace.Event(event.operation(), event.kind(), 4294967295L));
    Path wrong = copy(directory, run, "wrong-int-trace");
    new ExecutionTrace(trace.run(), trace.complete(), events)
        .write(wrong.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(wrong).verdict());
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void capturesMixedFieldsWithLiveBindings(String metadata) throws IOException {
    Path selected = profile(directory, 21);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    settings.add("requirements", JsonFiles.GSON.toJsonTree(List.of("local-bindings")));
    JsonFiles.write(selected, settings);
    String live = PROGRAM.replace("read-data initial n 0;", "checkpoint; read-data initial n 0;");
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, live), selected, run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("openj9")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void selectedOpenJ9UsesTheSamePrimitiveContract(String metadata) throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m")));
    settings.addProperty("bindingMetadata", metadata);
    if (metadata.equals("retained-class")) settings.addProperty("observation", "roots-diagnostic");
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    checkPhysicalTypes(run);
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("hotspot")
  @Test
  void rejectsWrongDescriptorsPrimitiveCodesAndUnextendedIntValues() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), profile(directory, 21), run));
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var type =
        raw.classes().stream()
            .filter(c -> c.signature().equals("LCase$S0;"))
            .findFirst()
            .orElseThrow();
    var field = type.fields().stream().filter(f -> f.name().equals("d0")).findFirst().orElseThrow();
    int offset =
        java.util.stream.IntStream.range(0, raw.records().size())
            .filter(
                i -> {
                  var record = raw.records().get(i);
                  return record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && record.b() == field.index()
                      && raw.records().stream()
                          .anyMatch(
                              owner ->
                                  owner.kind() == HeapSnapshot.Kind.OBJECT
                                      && owner.a() == record.a()
                                      && owner.b() == type.id());
                })
            .findFirst()
            .orElseThrow();
    var original = raw.records().get(offset);
    for (long[] corruption : List.of(new long[] {74, original.d()}, new long[] {73, 4294967297L})) {
      var records = new ArrayList<>(raw.records());
      records.set(
          offset,
          new HeapSnapshot.Record(
              original.kind(),
              original.a(),
              original.b(),
              corruption[0],
              corruption[1],
              original.e(),
              original.f(),
              original.g()));
      assertTrue(
          SnapshotProjector.project(replace(raw, raw.classes(), records), layout)
              .graph()
              .isEmpty());
    }
    var fields = new ArrayList<>(type.fields());
    int fieldIndex = fields.indexOf(field);
    fields.set(
        fieldIndex,
        new HeapSnapshot.FieldInfo(field.index(), field.modifiers(), field.name(), "J"));
    var classes = new ArrayList<>(raw.classes());
    classes.set(
        classes.indexOf(type),
        new HeapSnapshot.ClassInfo(type.id(), type.kind(), type.signature(), fields));
    assertTrue(
        SnapshotProjector.project(replace(raw, classes, raw.records()), layout).graph().isEmpty());
  }

  @Tag("hotspot")
  @Test
  void referenceAcquisitionUsesTheSameNarrowingAndHistorySemantics() throws IOException {
    String source =
        """
        gir 1; shape N object 1 [int, long];
        object 1 N [weak(2)] [0, 0]; object 2 N [null] [4294967295, 9223372036854775807];
        root owner = 1; root guard = 2;
        program {
          scope { read-root n N owner; acquire held N n 0 {
            read-data initial held 0; write-data held 0 2147483648;
            read-data minimum held 0; write-volatile-data held 0 4294967297;
          } }
          checkpoint;
        }
        """;
    Path run = directory.resolve("references");
    assertPass(RunEngine.run(input(directory, source), profile(directory, 21), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Tag("hotspot")
  @Test
  void naturalCapturePreservesFutureUsedMixedObject() throws IOException {
    Path selected = profile(directory, 21);
    var settings = JsonFiles.read(selected);
    settings.addProperty("observation", "roots-natural");
    JsonFiles.write(selected, settings);
    String source =
        "gir 1; shape N object 0 [int, long]; object 1 N [] [2147483648, 4294967295]; root r = 1;"
            + " program { read-root n N r; checkpoint; read-data value n 0; read-data wide n 1; }";
    Path run = directory.resolve("natural");
    assertPass(RunEngine.run(input(directory, source), selected, run));
    assertPass(RunEngine.verify(run));
  }

  private static HeapSnapshot replace(
      HeapSnapshot raw, List<HeapSnapshot.ClassInfo> classes, List<HeapSnapshot.Record> records) {
    return new HeapSnapshot(
        raw.run(),
        raw.checkpoint(),
        raw.status(),
        raw.jvmtiError(),
        raw.capabilities(),
        raw.gcStartsBefore(),
        raw.gcFinishesBefore(),
        raw.gcStartsAfter(),
        raw.gcFinishesAfter(),
        classes,
        raw.arrays(),
        raw.methods(),
        records);
  }

  private static void checkPhysicalTypes(Path run) throws IOException {
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var type =
        raw.classes().stream()
            .filter(c -> c.signature().equals("LCase$S0;"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of("I", "J", "I"),
        type.fields().stream()
            .filter(f -> f.name().startsWith("d"))
            .sorted(java.util.Comparator.comparing(HeapSnapshot.FieldInfo::name))
            .map(HeapSnapshot.FieldInfo::descriptor)
            .toList());
    var graph = SnapshotProjector.project(raw, layout).graph().orElseThrow();
    assertEquals(
        List.of(1L, Long.MIN_VALUE, -2147483648L),
        graph.objects().get(graph.roots().get("r").target()).data());
    var output = new StringWriter();
    var errors = new StringWriter();
    assertEquals(
        0,
        ToolProvider.findFirst("javap")
            .orElseThrow()
            .run(
                new PrintWriter(output),
                new PrintWriter(errors),
                "-p",
                "-c",
                run.resolve("generated/classes/Case.class").toString()),
        errors.toString());
    String code = output.toString();
    for (String descriptor :
        List.of(
            "VarHandle.getVolatile:(LCase$S0;)I",
            "VarHandle.setVolatile:(LCase$S0;I)V",
            "VarHandle.compareAndSet:(LCase$S0;II)Z",
            "VarHandle.getAndSet:(LCase$S0;I)I",
            "VarHandle.getAndSet:(LCase$S0;J)J"))
      assertTrue(code.contains(descriptor), descriptor + "\n" + code);
  }
}
