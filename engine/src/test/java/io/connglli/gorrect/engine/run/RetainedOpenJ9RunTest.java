package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.RetainedLocalMetadata;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CaptureEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Selected OpenJ9 conformance; retained scopes and actual compiled execution are separate proofs.
 */
@Tag("openj9")
class RetainedOpenJ9RunTest {
  @TempDir Path directory;

  private static final String SCOPES =
      """
      gir 1; shape N object 0 1; root main = null;
      program { scope { allocate caller N; write-data caller 0 7;
        read-root empty N main; checkpoint; release caller; release empty;
        repeat 2 { parallel {
          worker a { allocate held N; allocate other N; checkpoint;
            release held; release other; checkpoint; }
          worker b { read-root empty N main; checkpoint; release empty; checkpoint; }
        } }
      } checkpoint; }
      """;

  @Test
  void verifiesAndReplaysTheGeneratedCallerAndRepeatedWorkerCuts() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, SCOPES), selected(false), run));
    assertPass(RunEngine.verify(run));
    assertTrue(
        assertRawCoordinates(run)
            .containsAll(
                Set.of("execute", "worker1", "worker2", "main", "non-null", "null", "repeated")));
    Path replay = directory.resolve("replay");
    assertPass(RunEngine.replay(run, replay));
    assertNotEquals(token(run), token(replay));
    assertPass(RunEngine.verify(replay));
    assertRawCoordinates(replay);
    rejectDamagedCoordinates(run);
  }

  @Test
  void varHandleWorkerCompilationRequiresActualFramesWithLocalAccessAbsent() throws IOException {
    String program =
        """
        gir 1; require compiled-execution;
        shape N object 0 1; object 1 N [] [7]; root main = 1;
        program { repeat 3 { parallel {
          worker a { read-root held N main; read-volatile-data value held 0; release held; checkpoint; }
          worker b { read-root held N main; read-volatile-data value held 0; release held; checkpoint; }
        } } }
        """;
    Path run = directory.resolve("compiled");
    assertPass(RunEngine.run(input(directory, program), selected(true), run));
    assertPass(RunEngine.verify(run));
    assertRawCoordinates(run);
    long token = JsonFiles.number(JsonFiles.read(run.resolve("manifest.json")).get("run"));
    assertEquals(
        0, Artifacts.snapshot(run, token, 2).capabilities() & HeapSnapshot.LOCAL_VARIABLES);
    assertTrue(
        VmDriver.compiledFrames(
                io.connglli.gorrect.engine.config.TargetProfile.parse(
                    JsonFiles.read(run.resolve("manifest.json")).getAsJsonObject("profile"), run),
                run,
                token)
            .stream()
            .anyMatch(io.connglli.gorrect.protocol.ExecutionEvidence.Frame::compiled));
    Path replay = directory.resolve("compiled-replay");
    assertPass(RunEngine.replay(run, replay));
    assertRawCoordinates(replay);
  }

  @Test
  void compilationCannotRepairAbsentPhysicalBindingOrigins() throws IOException {
    String program =
        """
        gir 1; require compiled-execution;
        shape N object 0 1; object 1 N [] [7]; root main = 1;
        program { repeat 3 { parallel {
          worker a { read-root held N main; read-volatile-data value held 0; checkpoint; }
          worker b { read-root held N main; read-volatile-data value held 0; checkpoint; }
        } } }
        """;
    Path run = directory.resolve("compiled-live");
    var result = RunEngine.run(input(directory, program), selected(true), run);
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), result.toString());
    assertTrue(result.unmetRequirements().isEmpty(), result.toString());
    assertFalse(result.problems().isEmpty());
    assertTrue(
        result.problems().stream()
            .allMatch(problem -> problem.contains("physical root for binding")),
        result.toString());
    assertEquals(result, RunEngine.verify(run));
  }

  static Set<String> assertRawCoordinates(Path run) throws IOException {
    long token = token(run);
    var code =
        CodeEvidence.read(run.resolve("traces/code.gcode"), token, CodeEvidence.Stage.STARTUP);
    assertArrayEquals(Files.readAllBytes(run.resolve("generated/classes/Case.class")), code.code());
    var metadata = RetainedLocalMetadata.read(code.code(), "LCase;");
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var coverage = new HashSet<String>();
    long captures;
    try (var snapshots = Files.list(run.resolve("snapshots"))) {
      captures = snapshots.filter(p -> p.getFileName().toString().endsWith(".gsnap")).count();
    }
    for (int checkpoint = 1; checkpoint <= captures; checkpoint++) {
      var raw = Artifacts.snapshot(run, token, checkpoint);
      assertEquals(0, raw.capabilities() & HeapSnapshot.LOCAL_VARIABLES);
      var frames =
          FrameEvidence.read(run.resolve("snapshots/" + checkpoint + ".gframe"), token, checkpoint);
      assertEquals(CodeEvidence.Status.VALID, frames.status());
      assertEquals(code.epoch(), frames.beforeEpoch());
      assertEquals(code.epoch(), frames.afterEpoch());
      List<BindingEvidence> bindings;
      Path cut = run.resolve("snapshots/" + checkpoint + ".gcut");
      if (Files.exists(cut)) {
        var capture = CaptureEvidence.read(cut, token, checkpoint);
        bindings =
            capture.participants().stream()
                .map(
                    p ->
                        new BindingEvidence(
                            token,
                            raw.checkpoint(),
                            p.thread(),
                            p.worker(),
                            p.activation(),
                            p.bindings()))
                .toList();
        if (capture.round() == 2) coverage.add("repeated");
      } else {
        bindings =
            List.of(
                BindingEvidence.read(
                    run.resolve("snapshots/" + checkpoint + ".gbind"), token, checkpoint));
      }
      assertEquals(bindings.size(), frames.participants().size());
      for (int index = 0; index < bindings.size(); index++) {
        var binding = bindings.get(index);
        var participant = frames.participants().get(index);
        assertEquals(binding.thread(), participant.thread());
        assertEquals(binding.worker(), participant.worker());
        String name =
            checkpoint == captures
                ? "main"
                : binding.worker() == 0 ? "execute" : "worker" + binding.worker();
        var role =
            checkpoint == captures
                ? FrameEvidence.Role.MAIN
                : binding.worker() == 0 ? FrameEvidence.Role.EXECUTE : FrameEvidence.Role.WORKER;
        assertEquals(role, participant.role());
        coverage.add(name);
        var method = code.methods().stream().filter(m -> m.name().equals(name)).toList();
        assertEquals(1, method.size());
        long methodToken = method.getFirst().token();
        var before = participant.before().stream().filter(f -> f.method() == methodToken).toList();
        var after = participant.after().stream().filter(f -> f.method() == methodToken).toList();
        assertEquals(1, before.size());
        assertEquals(before, after);
        var frame = before.getFirst();
        var scopes =
            metadata.methods().stream()
                .filter(
                    m ->
                        m.name().equals(name)
                            && m.descriptor().equals(method.getFirst().descriptor()))
                .findFirst()
                .orElseThrow()
                .code()
                .orElseThrow();
        assertTrue(frame.bci() >= 0 && frame.bci() < scopes.length());
        for (var present : binding.bindings()) {
          var declaration =
              layout.bindings().stream()
                  .filter(b -> b.declaration() == present.declaration())
                  .findFirst()
                  .orElseThrow();
          assertEquals(name, declaration.method());
          var locals =
              scopes.locals().stream()
                  .filter(
                      l ->
                          l.name().equals(declaration.variable())
                              && l.descriptor().equals(declaration.type())
                              && frame.bci() >= l.start()
                              && frame.bci() - l.start() < l.length())
                  .toList();
          assertEquals(1, locals.size());
          var roots =
              raw.records().stream()
                  .filter(
                      r ->
                          r.kind() == HeapSnapshot.Kind.ROOT
                              && r.b() == 24
                              && r.c() == binding.thread()
                              && r.f() == methodToken
                              && r.e() == locals.getFirst().slot())
                  .distinct()
                  .toList();
          assertEquals(present.present() ? 1 : 0, roots.size());
          for (var root : roots) {
            assertEquals(frame.depth(), root.d());
            assertTrue(root.g() == -1 || root.g() == frame.bci());
          }
          coverage.add(present.present() ? "non-null" : "null");
        }
        if (checkpoint == captures) assertTrue(binding.bindings().isEmpty());
      }
    }
    RetainedMetadata.completion(run, token);
    return Set.copyOf(coverage);
  }

  private void rejectDamagedCoordinates(Path original) throws IOException {
    long token = token(original);
    var raw = Artifacts.snapshot(original, token, 2);
    var root = nonNullWorkerRoot(original, raw);
    long otherTag =
        raw.records().stream()
            .filter(
                r ->
                    r.kind() == HeapSnapshot.Kind.ROOT
                        && r.b() == 24
                        && r.c() == root.c()
                        && r.f() == root.f()
                        && r.a() != root.a())
            .findFirst()
            .orElseThrow()
            .a();
    assertTrue(
        raw.records().stream()
            .anyMatch(r -> r.kind() == HeapSnapshot.Kind.OBJECT && r.a() == otherTag));
    for (String damage :
        List.of(
            "missing",
            "slot",
            "depth",
            "bci",
            "method",
            "thread",
            "ambiguous",
            "frame",
            "terminal",
            "code")) {
      Path changed = copy(directory, original, "damaged-" + damage);
      if (damage.equals("frame")) Files.delete(changed.resolve("snapshots/2.gframe"));
      else if (damage.equals("terminal")) Files.delete(changed.resolve("traces/code-final.gcode"));
      else if (damage.equals("code")) {
        Path path = changed.resolve("generated/classes/Case.class");
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
      } else {
        var records = new ArrayList<>(raw.records());
        if (!damage.equals("ambiguous")) records.removeIf(root::equals);
        if (!damage.equals("missing"))
          records.add(
              new HeapSnapshot.Record(
                  root.kind(),
                  damage.equals("ambiguous") ? otherTag : root.a(),
                  root.b(),
                  root.c() + (damage.equals("thread") ? 1 : 0),
                  root.d() + (damage.equals("depth") ? 1 : 0),
                  root.e() + (damage.equals("slot") ? 1 : 0),
                  root.f() + (damage.equals("method") ? 1 : 0),
                  root.g() + (damage.equals("bci") ? 1 : 0)));
        Files.write(
            changed.resolve("snapshots/2.gsnap"),
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
      var result = RunEngine.verify(changed);
      assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, result.verdict(), damage + ": " + result);
      assertFalse(result.problems().isEmpty(), damage);
      assertNotEquals(0, result.exitStatus(), damage);
      if (damage.equals("ambiguous"))
        assertTrue(
            result.problems().contains("Conflicting physical stack-slot evidence"),
            result.toString());
    }
  }

  private static HeapSnapshot.Record nonNullWorkerRoot(Path run, HeapSnapshot raw)
      throws IOException {
    var participant =
        CaptureEvidence.read(run.resolve("snapshots/2.gcut"), raw.run(), 2).participants().get(1);
    var present =
        participant.bindings().stream()
            .filter(BindingEvidence.Binding::present)
            .findFirst()
            .orElseThrow();
    var layout =
        JsonFiles.GSON.fromJson(
            JsonFiles.read(run.resolve("generated/layout.json")), ObservationLayout.class);
    var binding =
        layout.bindings().stream()
            .filter(b -> b.declaration() == present.declaration())
            .findFirst()
            .orElseThrow();
    assertEquals(participant.worker(), binding.worker());
    var code =
        CodeEvidence.read(run.resolve("traces/code.gcode"), raw.run(), CodeEvidence.Stage.STARTUP);
    var method =
        code.methods().stream()
            .filter(
                m ->
                    m.name().equals(binding.method())
                        && m.descriptor().equals(binding.descriptor()))
            .findFirst()
            .orElseThrow();
    var frame =
        FrameEvidence.read(run.resolve("snapshots/2.gframe"), raw.run(), 2)
            .participants()
            .get(1)
            .before()
            .stream()
            .filter(f -> f.method() == method.token())
            .findFirst()
            .orElseThrow();
    var scopes =
        RetainedLocalMetadata.read(code.code(), "LCase;").methods().stream()
            .filter(
                m -> m.name().equals(method.name()) && m.descriptor().equals(method.descriptor()))
            .findFirst()
            .orElseThrow()
            .code()
            .orElseThrow();
    var locals =
        scopes.locals().stream()
            .filter(
                l ->
                    l.name().equals(binding.variable())
                        && l.descriptor().equals(binding.type())
                        && frame.bci() >= l.start()
                        && frame.bci() - l.start() < l.length())
            .toList();
    assertEquals(1, locals.size());
    var roots =
        raw.records().stream()
            .filter(
                r ->
                    r.kind() == HeapSnapshot.Kind.ROOT
                        && r.b() == 24
                        && r.c() == participant.thread()
                        && r.f() == method.token()
                        && r.e() == locals.getFirst().slot()
                        && r.d() == frame.depth()
                        && (r.g() == -1 || r.g() == frame.bci()))
            .distinct()
            .toList();
    assertEquals(1, roots.size());
    return roots.getFirst();
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.number(JsonFiles.read(run.resolve("manifest.json")).get("run"));
  }

  private Path selected(boolean jit) throws IOException {
    var settings =
        TestProfiles.create("openj9", System.getProperty("gorrect.openj9.java"), "gencon");
    settings.addProperty("observation", "roots-diagnostic");
    settings.addProperty("bindingMetadata", "retained-class");
    settings.add(
        "vmArgs",
        JsonFiles.GSON.toJsonTree(jit ? List.of("-Xmx128m") : List.of("-Xmx128m", "-Xint")));
    settings.add(
        "requirements",
        JsonFiles.GSON.toJsonTree(jit ? List.of("tagging") : List.of("tagging", "local-bindings")));
    if (jit) settings.add("jit", new JsonObject());
    var limits = new JsonObject();
    limits.addProperty("startupSeconds", 60);
    limits.addProperty("runSeconds", 120);
    limits.addProperty("historyTransitions", 1_000_000);
    settings.add("limits", limits);
    Path path = directory.resolve("profile.json");
    JsonFiles.write(path, settings);
    return path;
  }
}
