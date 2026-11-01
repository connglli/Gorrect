package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.observe.RetainedLocalMetadata;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual synchronized bytecode, primitive boundaries and ordinary physical local evidence. */
class MonitorRunTest {
  @TempDir Path directory;

  private static final String PROGRAM =
      """
      gir 1; shape N object 1 1; shape D data 2; shape R refs 1;
      object 1 N [null] [7]; object 2 D [] [9,11]; object 3 R [1] [];
      root main=1; root data=2; root refs=3;
      program { before: checkpoint; scope {
        read-root owner N main; read-root alias N main;
        read-root values D data; read-root links R refs;
        identity-hash owner;
        outer: monitor owner {
          write-data owner 0 42;
          reentrant: monitor alias {
            distinct: monitor values {
              monitor links { write-ref links 0 owner; }
              clone duplicate values; copy-array values 0 duplicate 0 2;
              identity-hash duplicate; release duplicate;
              collect; identity-hash owner; inside: checkpoint;
            }
          }
        }
        identity-hash owner;
      } outside: checkpoint; }
      """;

  @Tag("hotspot")
  @ParameterizedTest
  @CsvSource({"17,jvmti", "21,jvmti", "17,retained-class", "21,retained-class"})
  void capturesNestedObjectAndArrayMonitorsOnEachReleaseAndMetadataProvider(
      int feature, String metadata) throws IOException {
    checkRun(selected(feature, metadata));
  }

  @Tag("hotspot")
  @ParameterizedTest
  @CsvSource({"17,jvmti", "21,retained-class"})
  void mixedPrimitiveFieldsComposeWithNestedMonitorsHashAndCollection(int feature, String metadata)
      throws IOException {
    String source =
        """
        gir 1; shape N object 0 [int, long, int];
        object 1 N [] [4294967295, 9223372036854775807, 2147483648]; root main=1;
        program { before: checkpoint; scope {
          read-root owner N main; identity-hash owner;
          monitor owner {
            write-data owner 0 4294967298; write-data owner 1 -9223372036854775808;
            read-data narrowed owner 0;
            if-nonzero narrowed { monitor owner {
              write-data owner 2 4294967295; collect; identity-hash owner;
              read-data signed owner 2; inside: checkpoint;
            } }
          }
          identity-hash owner;
        } after: checkpoint; }
        """;
    Path run = directory.resolve("mixed-monitor");
    assertPass(RunEngine.run(input(directory, source), selected(feature, metadata), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("mixed-replay")));
    var reads =
        GirParser.parse(source)
            .instructions()
            .filter(Instruction.ReadData.class::isInstance)
            .map(Instruction::id)
            .toList();
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run));
    assertEquals(
        List.of(2L, -1L),
        trace.events().stream()
            .filter(e -> reads.contains(e.operation()))
            .map(ExecutionTrace.Event::value)
            .toList());
    var raw = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/2.gsnap")));
    var fields =
        raw.classes().stream()
            .filter(c -> c.signature().equals("LCase$S0;"))
            .findFirst()
            .orElseThrow()
            .fields();
    assertEquals(
        List.of("I", "J", "I"),
        List.of("d0", "d1", "d2").stream()
            .map(
                name ->
                    fields.stream()
                        .filter(f -> f.name().equals(name))
                        .findFirst()
                        .orElseThrow()
                        .descriptor())
            .toList());
    int field =
        fields.stream().filter(f -> f.name().equals("d0")).findFirst().orElseThrow().index();
    long owner = ownerTag(raw);
    var records = new ArrayList<>(raw.records());
    int index =
        java.util.stream.IntStream.range(0, records.size())
            .filter(
                n -> {
                  var record = records.get(n);
                  return record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && record.a() == owner
                      && record.b() == field;
                })
            .findFirst()
            .orElseThrow();
    var record = records.get(index);
    assertEquals(73, record.c());
    assertEquals(2, record.d());
    records.set(
        index,
        new HeapSnapshot.Record(
            record.kind(),
            record.a(),
            record.b(),
            record.c(),
            4294967298L,
            record.e(),
            record.f(),
            record.g()));
    Path malformed = copy(directory, run, "mixed-noncanonical-int");
    Files.write(
        malformed.resolve("snapshots/2.gsnap"), SnapshotCodec.write(withRecords(raw, records)));
    var report = RunEngine.verify(malformed);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, report.verdict(), report.toString());
    assertTrue(
        report.problems().stream().anyMatch(problem -> problem.contains("Noncanonical int")));
  }

  @Tag("openj9")
  @Test
  void capturesAndReplaysOnTheSelectedOpenJ9() throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xint")));
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    Path selected = directory.resolve("profile.json");
    JsonFiles.write(selected, settings);
    checkRun(selected);
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void hiddenReceiverAndGenericMonitorRootsCannotReplaceTheDeclaredOwner(String metadata)
      throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21, metadata), run));
    Path damaged = copy(directory, run, "missing-owner");
    Path file = damaged.resolve("snapshots/2.gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(file));
    long ownerTag = ownerTag(raw);
    var roots =
        raw.records().stream()
            .filter(r -> r.kind() == HeapSnapshot.Kind.ROOT && r.a() == ownerTag && r.b() == 24)
            .toList();
    assertTrue(roots.size() >= 2, "Owner and alias must have actual stack origins");
    // Slots are read from actual metadata, never from the expected graph or matching target ID.
    var method =
        raw.methods().stream().filter(m -> m.name().equals("execute")).findFirst().orElseThrow();
    var scopes =
        RetainedLocalMetadata.read(run.resolve("generated/classes/Case.class"), "LCase;")
            .methods()
            .stream()
            .filter(m -> m.name().equals("execute"))
            .findFirst()
            .orElseThrow()
            .code()
            .orElseThrow()
            .locals();
    int ownerSlot =
        scopes.stream().filter(l -> l.name().equals("b3")).findFirst().orElseThrow().slot();
    if (metadata.equals("jvmti"))
      assertEquals(
          ownerSlot,
          method.locals().stream()
              .filter(l -> l.name().equals("b3"))
              .findFirst()
              .orElseThrow()
              .slot());
    var bindingSlots =
        scopes.stream()
            .filter(l -> l.name().startsWith("b"))
            .map(RetainedLocalMetadata.LocalVariable::slot)
            .toList();
    assertTrue(
        roots.stream().anyMatch(r -> !bindingSlots.contains((int) r.e())),
        "The actual javac receiver temporary must remain captured");
    var records = new ArrayList<>(raw.records());
    int slot = ownerSlot;
    assertTrue(
        records.removeIf(
            r ->
                r.kind() == HeapSnapshot.Kind.ROOT
                    && r.a() == ownerTag
                    && r.b() == 24
                    && r.e() == slot));
    records.add(new HeapSnapshot.Record(HeapSnapshot.Kind.ROOT, ownerTag, 23, 0, -1, -1, 0, -1));
    Files.write(file, SnapshotCodec.write(withRecords(raw, records)));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(damaged).verdict());
    Path sameCut = copy(directory, damaged, "same-cut");
    corruptPayload(sameCut, 2);
    var refuted = RunEngine.verify(sameCut);
    assertEquals(ExactVerifier.Verdict.VIOLATED, refuted.verdict(), refuted.toString());
    assertTrue(refuted.problems().stream().anyMatch(p -> p.contains("physical root")));
    assertTrue(refuted.problems().stream().anyMatch(p -> p.contains("data[0]")));
    for (String context : List.of("run", "checkpoint", "thread", "activation", "worker", "cut")) {
      Path invalid = copy(directory, sameCut, "context-" + context);
      if (context.equals("cut")) Files.write(invalid.resolve("snapshots/2.gcut"), new byte[] {0});
      else {
        long runToken = token(invalid);
        var binding = BindingEvidence.read(invalid.resolve("snapshots/2.gbind"), runToken, 2);
        new BindingEvidence(
                runToken + (context.equals("run") ? 1 : 0),
                context.equals("checkpoint") ? 3 : 2,
                binding.thread() + (context.equals("thread") ? 1 : 0),
                context.equals("worker") ? 1 : 0,
                context.equals("activation") ? 2 : 1,
                binding.bindings())
            .write(invalid.resolve("snapshots/2.gbind"));
      }
      assertEquals(
          ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(invalid).verdict(), context);
      corruptPayload(invalid);
      assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(invalid).verdict(), context);
    }
    for (String faults : List.of("declaration", "launch", "both")) {
      Path combined = copy(directory, sameCut, "combined-" + faults);
      boolean invalidDeclarations = !faults.equals("launch");
      if (invalidDeclarations) {
        long runToken = token(combined);
        var binding = BindingEvidence.read(combined.resolve("snapshots/2.gbind"), runToken, 2);
        new BindingEvidence(
                runToken,
                2,
                binding.thread(),
                0,
                1,
                binding.bindings().stream().filter(value -> value.declaration() != 3).toList())
            .write(combined.resolve("snapshots/2.gbind"));
      }
      if (!faults.equals("declaration"))
        Files.writeString(
            combined.resolve("target.properties"),
            "\nvm.argument.0=duplicate\n",
            StandardOpenOption.APPEND);
      var result = RunEngine.verify(combined);
      assertEquals(
          invalidDeclarations ? ExactVerifier.Verdict.INCONCLUSIVE : ExactVerifier.Verdict.VIOLATED,
          result.verdict(),
          faults + ": " + result);
      corruptPayload(combined);
      assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(combined).verdict(), faults);
    }
    corruptPayload(damaged);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(damaged).verdict());
  }

  @Tag("hotspot")
  @Test
  void markerAndControlDamagePreserveIndependentCoveredRefutations() throws IOException {
    String source = PROGRAM.replaceAll("identity-hash [a-z]+;", "");
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, source), selected(21, "jvmti"), run));
    long token = token(run);
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token);
    int outer =
        GirParser.parse(source)
            .instructions()
            .filter(Instruction.Monitor.class::isInstance)
            .findFirst()
            .orElseThrow()
            .id();
    int index =
        java.util.stream.IntStream.range(0, trace.events().size())
            .filter(i -> trace.events().get(i).operation() == outer)
            .findFirst()
            .orElseThrow();
    Path malformed = copy(directory, run, "malformed");
    var events = new ArrayList<>(trace.events());
    events.set(index, new ExecutionTrace.Event(outer, ExecutionTrace.Kind.OPERATION, 1));
    new ExecutionTrace(token, false, events.subList(0, index + 1))
        .write(malformed.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(malformed).verdict());
    Path missing = copy(directory, run, "missing");
    Files.delete(missing.resolve("traces/execution.gtrace"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(missing).verdict());
    corruptPayload(missing);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(missing).verdict());
    var control = JsonFiles.read(missing.resolve("traces/control.json"));
    control.getAsJsonArray("messages").remove(2);
    JsonFiles.write(missing.resolve("traces/control.json"), control);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(missing).verdict());
  }

  @Tag("hotspot")
  @Test
  void nullEntryIsInvalidAndInteriorCaptureRequiresBindingProfile() throws IOException {
    String source =
        "gir 1; shape N object 0 0; root r=null;"
            + "program { read-root owner N r; monitor owner {} }";
    assertEquals(
        RunReport.Outcome.INVALID_WORKLOAD,
        RunEngine.run(input(directory, source), selected(21, "jvmti"), directory.resolve("null"))
            .execution());
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, PROGRAM), profile(directory, 21), directory.resolve("named"))
            .execution());
    source =
        "gir 1; shape N object 0 0; object 1 N [] []; root r=1;"
            + " program { scope { read-root owner N r; monitor owner {} } checkpoint; }";
    assertPass(
        RunEngine.run(
            input(directory, source), profile(directory, 21), directory.resolve("outside")));
  }

  @Tag("hotspot")
  @Test
  void authenticatedUnsupportedSourcePrecedesMissingExecutionEvidence() throws IOException {
    Path original = ordinaryRun();
    int index = 0;
    for (String extra :
        List.of(
            "read-volatile-root shared N main;",
            "repeat 0 { poll-queue; }",
            "parallel { worker first {} worker second {} }")) {
      for (String report : List.of("missing", "malformed", "null")) {
        Path run = copy(directory, original, "authenticated-" + index++);
        Files.writeString(run.resolve("case.gir"), unsupportedSource(extra));
        // This copied archived-input fixture has an explicit, matching executable inventory.
        // Source-only tampering is checked separately and cannot reach support classification.
        refreshInventory(run);
        if (report.equals("missing")) Files.delete(run.resolve("execution.json"));
        else Files.writeString(run.resolve("execution.json"), report.equals("null") ? "null" : "{");
        assertEquals(RunReport.Outcome.UNSUPPORTED, RunEngine.verify(run).execution());
      }
    }
  }

  @Tag("hotspot")
  @Test
  void sourceDamageCannotReplaceArtifactErrorsOrRetainedFailures() throws IOException {
    Path original = ordinaryRun();
    int index = 0;
    for (String source : List.of("gir 1; program { invalid; }", unsupportedSource("shape"))) {
      for (var outcome : List.of(RunReport.Outcome.COMPLETED, RunReport.Outcome.CRASH)) {
        Path run = copy(directory, original, "source-damage-" + index++);
        Files.writeString(run.resolve("case.gir"), source);
        if (outcome == RunReport.Outcome.CRASH)
          JsonFiles.write(
              run.resolve("execution.json"), RunReport.failure(outcome, "retained crash"));
        assertEquals(
            outcome == RunReport.Outcome.COMPLETED ? RunReport.Outcome.EXTRACTION_ERROR : outcome,
            RunEngine.verify(run).execution());
      }
      Path missing = copy(directory, original, "missing-report-" + index++);
      Files.writeString(missing.resolve("case.gir"), source);
      Files.delete(missing.resolve("execution.json"));
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(missing).execution());
    }
    Path missingSource = copy(directory, original, "missing-source");
    Files.delete(missingSource.resolve("case.gir"));
    JsonFiles.write(
        missingSource.resolve("execution.json"),
        RunReport.failure(RunReport.Outcome.CRASH, "retained crash"));
    assertEquals(RunReport.Outcome.CRASH, RunEngine.verify(missingSource).execution());

    Path invalid = copy(directory, original, "authenticated-invalid");
    Files.writeString(invalid.resolve("case.gir"), "gir 1; program { invalid; }");
    refreshInventory(invalid);
    Files.delete(invalid.resolve("execution.json"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(invalid).execution());

    Path preflight = directory.resolve("unlaunched");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(
                input(directory, unsupportedSource("shape")), selected(21, "jvmti"), preflight)
            .execution());
    Files.delete(preflight.resolve("execution.json"));
    assertEquals(RunReport.Outcome.EXTRACTION_ERROR, RunEngine.verify(preflight).execution());
  }

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(strings = {"jvmti", "retained-class"})
  void invalidQueueBindingContextCannotRecoverSameCutNamedFacts(String metadata)
      throws IOException {
    String source =
        """
        gir 1; shape N object 0 1; shape W weak; queue ready W;
        object 1 N [] [7]; root main=1; root box=null;
        program { before: checkpoint; allocate-ref wrapper W null ready;
          write-root box wrapper; enqueue-ref accepted wrapper; inside: checkpoint; }
        """;
    Path original = directory.resolve("queue-context");
    assertPass(RunEngine.run(input(directory, source), selected(21, metadata), original));
    Path payload = copy(directory, original, "queue-payload");
    corruptPayload(payload, 2);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(payload).verdict());
    Path context = copy(directory, original, "queue-empty-context");
    long runToken = token(context);
    var evidence = BindingEvidence.read(context.resolve("snapshots/2.gbind"), runToken, 2);
    new BindingEvidence(runToken, 2, evidence.thread(), 0, 1, List.of())
        .write(context.resolve("snapshots/2.gbind"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(context).verdict());
    corruptPayload(context, 2);
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(context).verdict());
    corruptPayload(context);
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(context).verdict());
  }

  private Path ordinaryRun() throws IOException {
    Path run = directory.resolve("ordinary");
    String source = "gir 1; shape N object 0 0; object 1 N [] []; root main=1; program {}";
    assertPass(RunEngine.run(input(directory, source), profile(directory, 21), run));
    return run;
  }

  private static String unsupportedSource(String extra) {
    String declaration = extra.equals("shape") ? "shape W weak;" : "";
    String body = extra.equals("shape") ? "" : extra;
    return "gir 1; shape N object 0 0; object 1 N [] []; root main=1; "
        + declaration
        + " program { read-root owner N main; repeat 0 { monitor owner {} } "
        + body
        + " }";
  }

  private static void refreshInventory(Path run) throws IOException {
    var manifest = JsonFiles.read(run.resolve("manifest.json"));
    manifest.add("hashes", JsonFiles.GSON.toJsonTree(Artifacts.inputs(run)));
    JsonFiles.write(run.resolve("manifest.json"), manifest);
  }

  private void checkRun(Path selected) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected, run));
    assertBytecode(run);
    var events = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run)).events();
    var sites =
        GirParser.parse(PROGRAM)
            .instructions()
            .filter(Instruction.Monitor.class::isInstance)
            .map(Instruction::id)
            .toList();
    assertEquals(
        List.of(0L, 0L, 0L, 0L, 1L, 1L, 1L, 1L),
        events.stream()
            .filter(e -> sites.contains(e.operation()))
            .map(ExecutionTrace.Event::value)
            .toList());
    assertPass(RunEngine.verify(run));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertPass(RunEngine.verify(replay));
    assertArrayEquals(
        Files.readAllBytes(run.resolve("generated/classes/Case.class")),
        Files.readAllBytes(replay.resolve("generated/classes/Case.class")));
  }

  private Path selected(int feature, String metadata) throws IOException {
    Path path = profile(directory, feature);
    var settings = JsonFiles.read(path);
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", metadata);
    JsonFiles.write(path, settings);
    return path;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }

  private static long ownerTag(HeapSnapshot raw) {
    var type =
        raw.classes().stream()
            .filter(c -> c.signature().equals("LCase$S0;"))
            .findFirst()
            .orElseThrow();
    return raw.records().stream()
        .filter(r -> r.kind() == HeapSnapshot.Kind.OBJECT && r.b() == type.id())
        .findFirst()
        .orElseThrow()
        .a();
  }

  private static void corruptPayload(Path run) throws IOException {
    corruptPayload(run, 1);
  }

  private static void corruptPayload(Path run, int checkpoint) throws IOException {
    Path file = run.resolve("snapshots/" + checkpoint + ".gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(file));
    long owner = ownerTag(raw);
    int field =
        raw.classes().stream()
            .filter(c -> c.signature().equals("LCase$S0;"))
            .findFirst()
            .orElseThrow()
            .fields()
            .stream()
            .filter(f -> f.name().equals("d0"))
            .findFirst()
            .orElseThrow()
            .index();
    var records = new ArrayList<>(raw.records());
    int index =
        java.util.stream.IntStream.range(0, records.size())
            .filter(
                i -> {
                  var r = records.get(i);
                  return r.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                      && r.a() == owner
                      && r.b() == field;
                })
            .findFirst()
            .orElseThrow();
    var r = records.get(index);
    records.set(
        index, new HeapSnapshot.Record(r.kind(), r.a(), r.b(), r.c(), 99, r.e(), r.f(), r.g()));
    Files.write(file, SnapshotCodec.write(withRecords(raw, records)));
  }

  private static HeapSnapshot withRecords(HeapSnapshot raw, List<HeapSnapshot.Record> records) {
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
        raw.classes(),
        raw.arrays(),
        raw.methods(),
        records);
  }

  private static void assertBytecode(Path run) {
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
    var lines = output.toString().lines().toList();
    assertEquals(
        4, lines.stream().filter(l -> l.contains("monitorenter")).count(), output.toString());
    assertEquals(
        8, lines.stream().filter(l -> l.contains("monitorexit")).count(), output.toString());
    assertTrue(output.toString().contains("Exception table:"), output.toString());
    assertTrue(output.toString().contains("athrow"), output.toString());
    assertTrue(
        lines.stream().anyMatch(l -> l.strip().matches("[0-9]+\\s+[0-9]+\\s+[0-9]+\\s+any")),
        output.toString());
  }
}
