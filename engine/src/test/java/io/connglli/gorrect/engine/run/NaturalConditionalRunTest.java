package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual optional visits and independent heap evidence under fence-free conditional capture. */
@Tag("hotspot")
class NaturalConditionalRunTest {
  @TempDir Path directory;
  private static final String PROGRAM =
      """
      gir 1; shape N object 0 1;
      object 1 N [] [7]; root stable = 1; root gate = null;
      program { repeat 3 {
        allocate unused N; read-root selected N gate;
        if-present selected {
          collect; checkpoint; read-data value selected 0; same-object self selected selected;
        }
        read-root reset N stable; write-root gate reset;
      } }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void followsActualOptionalVisitsWithoutRootingUnusedCandidates(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    long token = token(run);
    for (int sequence = 1; sequence <= 2; sequence++) {
      var evidence =
          BindingEvidence.read(run.resolve("snapshots/" + sequence + ".gbind"), token, sequence);
      assertEquals(2, evidence.bindings().size());
      assertTrue(evidence.bindings().stream().allMatch(BindingEvidence.Binding::present));
    }
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    assertTrue(layout.lexicalCandidates());
    String source = Files.readString(run.resolve("generated/Case.java"));
    assertFalse(source.contains("reachabilityFence"));
    assertFalse(source.contains("long visit"));
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void checksBothScalarArmsWithTheirOwnLexicalCandidates(int feature) throws IOException {
    String program =
        """
        gir 1; shape N object 0 1; object 1 N [] [0]; root main = 1;
        program { repeat 2 { read-root owner N main; read-data flag owner 0;
          if-nonzero flag { allocate dead N; collect; checkpoint;
            read-data observed owner 0; write-data owner 0 0;
          } else { allocate dead N; collect; checkpoint;
            read-data observed owner 0; write-data owner 0 1;
          }
        } }
        """;
    Path run = directory.resolve("arms");
    assertPass(RunEngine.run(input(directory, program), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    var first = BindingEvidence.read(run.resolve("snapshots/1.gbind"), token(run), 1);
    var second = BindingEvidence.read(run.resolve("snapshots/2.gbind"), token(run), 2);
    assertEquals(2, first.bindings().size());
    assertEquals(2, second.bindings().size());
    assertFalse(first.bindings().equals(second.bindings()));
  }

  @Test
  void candidateAndOriginDamageCannotPassOrHideNamedRootRefutations() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    var parsed = GirParser.parse(PROGRAM);
    int unused =
        parsed
            .instructions()
            .filter(Instruction.Allocate.class::isInstance)
            .findFirst()
            .orElseThrow()
            .id();
    long token = token(run);
    var evidence = BindingEvidence.read(run.resolve("snapshots/1.gbind"), token, 1);

    for (String damage :
        List.of("missing", "extra", "thread", "unused-nullness", "origins", "origins-and-data")) {
      Path changed = copy(directory, run, damage);
      Path file = changed.resolve("snapshots/1.gbind");
      var bindings = new ArrayList<>(evidence.bindings());
      if (damage.equals("missing")) bindings.removeFirst();
      if (damage.equals("extra")) bindings.add(new BindingEvidence.Binding(999, false));
      if (damage.equals("unused-nullness")) {
        bindings.replaceAll(
            binding ->
                binding.declaration() == unused
                    ? new BindingEvidence.Binding(unused, false)
                    : binding);
      }
      new BindingEvidence(
              token, 1, evidence.thread() + (damage.equals("thread") ? 1 : 0), 0, 1, bindings)
          .write(file);
      if (damage.startsWith("origins")) damageSnapshot(changed, true, damage.endsWith("data"));
      var result = RunEngine.verify(changed);
      assertEquals(
          damage.equals("unused-nullness") || damage.endsWith("data")
              ? ExactVerifier.Verdict.VIOLATED
              : ExactVerifier.Verdict.INCONCLUSIVE,
          result.verdict(),
          damage + ": " + result);
    }
  }

  @Test
  void earlierHeapOrScalarRefutationsSurviveLaterMissingCandidateEvidence() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    for (boolean scalar : List.of(false, true)) {
      Path changed = copy(directory, run, "changed-" + scalar);
      if (scalar) {
        Path trace = changed.resolve("traces/execution.gtrace");
        var old = ExecutionTrace.read(trace, token(run));
        var events = new ArrayList<>(old.events());
        for (int i = 0; i < events.size(); i++) {
          var event = events.get(i);
          if (event.kind() != ExecutionTrace.Kind.BRANCH) continue;
          events.set(
              i,
              new ExecutionTrace.Event(
                  event.operation(),
                  event.kind(),
                  1 - event.value(),
                  event.worker(),
                  event.group(),
                  event.round(),
                  event.phase()));
          break;
        }
        new ExecutionTrace(old.run(), old.complete(), events).write(trace);
      } else damageSnapshot(changed, false, true);
      Files.delete(changed.resolve("snapshots/2.gbind"));
      var result = RunEngine.verify(changed);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
      assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict(), result.toString());
    }
  }

  @Test
  void unknownOrMissingConditionalSelectionMetadataCannotBeGuessed() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    for (String mode : List.of("missing", "unknown", "required")) {
      Path changed = copy(directory, run, mode);
      Path file = changed.resolve("generated/layout.json");
      var layout = JsonFiles.read(file);
      if (mode.equals("missing")) layout.remove("bindingSelection");
      else layout.addProperty("bindingSelection", mode);
      JsonFiles.write(file, layout);
      var manifest = JsonFiles.read(changed.resolve("manifest.json"));
      manifest.add("hashes", JsonFiles.GSON.toJsonTree(Artifacts.inputs(changed)));
      JsonFiles.write(changed.resolve("manifest.json"), manifest);
      var result = RunEngine.verify(changed);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    }
  }

  @Test
  void incompleteScalarSuffixDoesNotInventDeadLocalsOrEraseNamedContradictions()
      throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    int point =
        GirParser.parse(PROGRAM)
            .instructions()
            .filter(Instruction.Checkpoint.class::isInstance)
            .findFirst()
            .orElseThrow()
            .id();
    var trace = ExecutionTrace.read(run.resolve("traces/execution.gtrace"), token(run));
    int end = 0;
    while (trace.events().get(end).operation() != point) end++;
    for (String damage : List.of("prefix", "missing", "truncated"))
      for (boolean refuted : List.of(false, true)) {
        Path changed = copy(directory, run, damage + "-" + refuted);
        Path file = changed.resolve("traces/execution.gtrace");
        if (damage.equals("missing")) Files.delete(file);
        else if (damage.equals("truncated")) Files.write(file, new byte[] {1, 2});
        else new ExecutionTrace(trace.run(), false, trace.events().subList(0, end + 1)).write(file);
        if (refuted) damageSnapshot(changed, false, true);
        var result = RunEngine.verify(changed);
        assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
        assertEquals(
            refuted ? ExactVerifier.Verdict.VIOLATED : ExactVerifier.Verdict.INCONCLUSIVE,
            result.verdict(),
            result.toString());
      }
  }

  @Test
  void trustedControlPrefixRetainsAnEarlierNamedRefutation() throws IOException {
    Path run = directory.resolve("original");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    for (String damage : List.of("missing-complete", "missing-later", "later-run", "first-site")) {
      Path changed = copy(directory, run, damage);
      Path file = changed.resolve("traces/control.json");
      var transcript = JsonFiles.read(file);
      var messages = transcript.getAsJsonArray("messages");
      switch (damage) {
        case "missing-complete" -> messages.remove(messages.size() - 1);
        case "missing-later" -> {
          while (messages.size() > 2) messages.remove(messages.size() - 1);
        }
        case "later-run" -> messages.get(2).getAsJsonObject().addProperty("run", token(run) + 1);
        case "first-site" -> messages.get(1).getAsJsonObject().addProperty("operation", 999);
        default -> throw new AssertionError(damage);
      }
      JsonFiles.write(file, transcript);
      damageSnapshot(changed, false, true);
      var result = RunEngine.verify(changed);
      assertEquals(RunReport.Outcome.EXTRACTION_ERROR, result.execution(), result.toString());
      assertEquals(
          damage.equals("first-site")
              ? ExactVerifier.Verdict.INCONCLUSIVE
              : ExactVerifier.Verdict.VIOLATED,
          result.verdict(),
          damage + ": " + result);
    }
  }

  private static void damageSnapshot(Path directory, boolean removeOrigins, boolean wrongData)
      throws IOException {
    Path file = directory.resolve("snapshots/1.gsnap");
    var raw = SnapshotCodec.read(Files.readAllBytes(file));
    var records =
        raw.records().stream()
            .filter(record -> !removeOrigins || record.kind() != HeapSnapshot.Kind.ROOT)
            .map(
                record ->
                    wrongData
                            && record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                            && record.d() == 7
                        ? new HeapSnapshot.Record(
                            record.kind(),
                            record.a(),
                            record.b(),
                            record.c(),
                            99,
                            record.e(),
                            record.f(),
                            record.g())
                        : record)
            .toList();
    Files.write(
        file,
        SnapshotCodec.write(
            new HeapSnapshot(
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
                records)));
  }

  private Path selected(int feature) throws IOException {
    Path selected = profile(directory, feature);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", "roots-natural");
    raw.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(
            List.of("tagging", "named-roots", "local-bindings", "semantic-reads")));
    JsonFiles.write(selected, raw);
    return selected;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
  }
}
