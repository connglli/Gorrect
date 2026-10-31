package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.copy;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.ReferenceHistory;
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

@Tag("hotspot")
class ReferenceObjectRunTest {
  @TempDir Path directory;
  private static final String PROGRAM =
      """
      gir 1;
      shape Node object 0 1; shape Box object 1 0; shape Weak weak; shape Soft soft;
      root saved = null;
      program {
        allocate value Node; allocate box Box;
        allocate-ref inner Soft value; allocate-ref outer Weak inner;
        write-ref box 0 outer; read-ref alias Weak box 0; write-ref box 0 null;
        acquire held Soft alias {
          acquire node Node held { write-data node 0 7; }
        }
        clear-ref alias; is-cleared cleared outer;
        write-root saved outer; checkpoint;
      }
      """;

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void observesAliasesAndWrapperReferentsThroughRealJavaApis(int feature) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
    var history = ReferenceHistory.readTyped(run.resolve("traces/references.gtyped"), token(run));
    assertEquals(
        2, history.events().stream().filter(e -> e.kind() == ReferenceHistory.Kind.CREATE).count());
    assertTrue(
        history.events().stream().anyMatch(e -> e.target() != null && e.target().kind() == 1));
    assertFalse(Files.exists(run.resolve("traces/references.ghistory")));
    String source = Files.readString(run.resolve("generated/Case.java"));
    assertTrue(source.contains("extends java.lang.ref.WeakReference<Object>"));
    assertTrue(source.contains("extends java.lang.ref.SoftReference<Object>"));
    assertTrue(source.contains(".refersTo(null)"));
    assertFalse(source.contains("public final Object target"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-typed", "legacy-fallback", "target-namespace", "subject"})
  void damagedTypedEvidenceCannotPassOrFallback(String damage) throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    Path changed = copy(directory, run, "changed");
    Path file = changed.resolve("traces/references.gtyped");
    var history = ReferenceHistory.readTyped(file, token(changed));
    if (damage.equals("missing-typed") || damage.equals("legacy-fallback")) {
      Files.delete(file);
      if (damage.equals("legacy-fallback"))
        new ReferenceHistory(token(changed), true, List.of())
            .write(changed.resolve("traces/references.ghistory"));
    } else {
      var events = new ArrayList<>(history.events());
      for (int i = 0; i < events.size(); i++) {
        var e = events.get(i);
        if (damage.equals("target-namespace") && e.target() != null && e.target().kind() == 1) {
          var t = e.target();
          events.set(
              i,
              new ReferenceHistory.Event(
                  e.worker(),
                  e.sequence(),
                  e.kind(),
                  e.strength(),
                  e.wrapper(),
                  new ReferenceHistory.Target(t.worker(), t.site(), t.occurrence())));
          break;
        }
        if (damage.equals("subject") && e.wrapper() != null) {
          var w = e.wrapper();
          events.set(
              i,
              new ReferenceHistory.Event(
                  e.worker(),
                  e.sequence(),
                  e.kind(),
                  e.strength(),
                  new ReferenceHistory.Identity(
                      w.worker(), w.site() + 100, w.occurrence(), w.slot()),
                  e.target()));
          break;
        }
      }
      new ReferenceHistory(token(changed), true, events).writeTyped(file);
    }
    var result = RunEngine.verify(changed);
    assertTrue(result.exitStatus() != 0, result.toString());
    assertEquals(
        damage.contains("fallback") || damage.equals("missing-typed")
            ? ExactVerifier.Verdict.INCONCLUSIVE
            : ExactVerifier.Verdict.VIOLATED,
        result.verdict(),
        result.toString());
  }

  @Test
  void anAliasClearConstrainsTheCanonicalObservedReferent() throws IOException {
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, PROGRAM), selected(21), run));
    Path file = run.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(file));
    var weak =
        snapshot.classes().stream()
            .filter(c -> c.signature().equals("LCase$S2;"))
            .findFirst()
            .orElseThrow();
    int referent =
        weak.fields().stream()
            .filter(f -> f.name().equals("referent"))
            .findFirst()
            .orElseThrow()
            .index();
    long wrapper =
        snapshot.records().stream()
            .filter(e -> e.kind() == HeapSnapshot.Kind.OBJECT && e.b() == weak.id())
            .findFirst()
            .orElseThrow()
            .a();
    var soft =
        snapshot.classes().stream()
            .filter(c -> c.signature().equals("LCase$S3;"))
            .findFirst()
            .orElseThrow();
    long target =
        snapshot.records().stream()
            .filter(e -> e.kind() == HeapSnapshot.Kind.OBJECT && e.b() == soft.id())
            .findFirst()
            .orElseThrow()
            .a();
    var records = new ArrayList<>(snapshot.records());
    records.add(
        new HeapSnapshot.Record(
            HeapSnapshot.Kind.REFERENCE, wrapper, target, 2, referent, -weak.id(), 0, 0));
    Files.write(
        file,
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                snapshot.checkpoint(),
                snapshot.status(),
                snapshot.jvmtiError(),
                snapshot.capabilities(),
                snapshot.gcStartsBefore(),
                snapshot.gcFinishesBefore(),
                snapshot.gcStartsAfter(),
                snapshot.gcFinishesAfter(),
                snapshot.classes(),
                snapshot.arrays(),
                snapshot.methods(),
                records)));
    assertEquals(ExactVerifier.Verdict.VIOLATED, RunEngine.verify(run).verdict());
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void keepsWrapperIdentityAcrossArraysAndRealCollection(int feature) throws IOException {
    String program =
        """
        gir 1; shape N object 0 1; shape R refs 1; shape W weak;
        root saved = null;
        program {
          scope {
            allocate value N; allocate array R; allocate-ref wrapper W value;
            write-ref array 0 wrapper; read-ref alias W array 0;
            same-object equal wrapper alias;
            write-root saved wrapper;
          }
          collect;
          read-root wrapper W saved;
          is-cleared state wrapper;
          acquire held N wrapper { read-data data held 0; }
          checkpoint;
        }
        """;
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, program), selected(feature), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  @Test
  void retainsLegacySlotStateAndPollsInTheTypedProgram() throws IOException {
    String program =
        """
        gir 1; shape N object 1 0; shape B object 1 0; shape W weak;
        object 1 N [weak(2)] []; object 2 N [null] []; root owner = 1;
        program { read-root n N owner; allocate box B; allocate-ref w W n;
          write-ref box 0 w; read-ref alias W box 0;
          clear-ref n 0; is-cleared old n 0; poll-queue;
          is-cleared fresh alias; checkpoint;
        }
        """;
    Path run = directory.resolve("run");
    assertPass(RunEngine.run(input(directory, program), selected(21), run));
    assertPass(RunEngine.verify(run));
  }

  private Path selected(int feature) throws IOException {
    Path path = profile(directory, feature);
    var json = JsonFiles.read(path);
    json.addProperty("observation", "roots-diagnostic");
    JsonFiles.write(path, json);
    return path;
  }

  private static long token(Path run) throws IOException {
    return JsonFiles.number(
        JsonFiles.required(JsonFiles.read(run.resolve("manifest.json")), "run"));
  }
}
