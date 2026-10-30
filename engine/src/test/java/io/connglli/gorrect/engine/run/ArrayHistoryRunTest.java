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
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** End-to-end array identities, reference processing, atomic elements, and binding coverage. */
@Tag("hotspot")
class ArrayHistoryRunTest {
  @TempDir Path directory;

  private static final String REFERENCES =
      """
      gir 1; shape Slots refs 3; shape Data data 2; shape Node object 0 1;
      object 1 Slots [weak(2), soft(3), null] [];
      object 2 Data [] [7, 9]; object 3 Node [] [11];
      root main = 1; root guard = 2; root owner = 3; root saved = null;
      program { scope {
        read-root slots Slots main;
        acquire data Data slots 0 { read-data value data 1; write-root saved data; }
        allocate fresh Data; write-data fresh 0 42; write-weak slots 2 fresh;
        acquire acquired Data slots 2 {
          same-object same acquired fresh; read-data value acquired 0;
        }
        clear-ref slots 0; acquire absent Data slots 0 { write-data absent 0 99; }
        collect;
      } checkpoint; }
      """;

  private static final String ATOMICS =
      """
      gir 1; shape Slots refs 2; shape Data data 2;
      object 1 Slots [null, 2] []; object 2 Data [] [5, 0];
      root main = 1; root values = 2; root saved = null;
      program { scope {
        read-volatile-root slots Slots main; read-root data Data values;
        write-volatile-ref slots 0 data; read-volatile-ref alias Data slots 0;
        compare-set-ref changed slots 0 alias null;
        exchange-ref absent Data slots 0 data;
        exchange-ref prior Data slots 0 null;
        write-root saved prior;
        read-volatile-data value data 0; compare-set-data won data 0 value 6;
        exchange-data old data 0 42; write-volatile-data data 1 old;
        same-object same prior alias;
      } checkpoint; }
      """;

  @ParameterizedTest
  @CsvSource({
    "17, named-roots-diagnostic",
    "21, named-roots-diagnostic",
    "17, roots-diagnostic",
    "21, roots-diagnostic"
  })
  void verifiesArrayReferentsAndAtomicElements(int feature, String observation) throws IOException {
    Path selected = selected(feature, observation);
    for (int i = 0; i < 2; i++) {
      Path run = directory.resolve("case-" + i);
      assertPass(RunEngine.run(input(directory, i == 0 ? REFERENCES : ATOMICS), selected, run));
      assertPass(RunEngine.verify(run));
      assertPass(RunEngine.replay(run, directory.resolve("replay-" + i)));
      long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
      var history = ReferenceHistory.read(run.resolve("traces/references.ghistory"), token);
      if (i == 0)
        assertTrue(
            history.events().stream()
                .anyMatch(
                    event ->
                        event.target() != null
                            && event.target().worker() == 0
                            && event.target().site() > 0));
      else
        assertTrue(
            history.events().stream()
                .anyMatch(
                    event ->
                        event.target() != null
                            && event.target().worker() == 0
                            && event.target().site() == 0
                            && event.target().occurrence() == 2));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void observesLiveCallerBindingsDuringReferenceAndAtomicHistories(int feature) throws IOException {
    String program =
        """
        gir 1; shape N object 1 1;
        object 1 N [weak(2)] [7]; object 2 N [null] [9]; root main = 1; root guard = 2;
        program { scope {
          read-root owner N main;
          initial: checkpoint;
          acquire held N owner 0 { same-object self held held; }
          read-volatile-root target N guard;
          compare-set-ref linked target 0 null owner;
          linked: checkpoint;
        } }
        """;
    Path run = directory.resolve("bindings");
    assertPass(
        RunEngine.run(input(directory, program), selected(feature, "roots-diagnostic"), run));
    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("bindings-replay")));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var original = BindingEvidence.read(run.resolve("snapshots/1.gbind"), token, 1);
    assertEquals(1, original.bindings().size());
    Path damaged = copy(directory, run, "missing-binding");
    new BindingEvidence(token, 1, original.thread(), 0, 1, List.of())
        .write(damaged.resolve("snapshots/1.gbind"));
    assertEquals(ExactVerifier.Verdict.INCONCLUSIVE, RunEngine.verify(damaged).verdict());
  }

  @Test
  void unusedArrayDeclarationsDoNotRejectAnOrdinaryObjectHistory() throws IOException {
    var program =
        "gir 1; shape Unused refs 4; shape N object 0 0; root r = null; "
            + "program { compare-set-root changed r null null; }";
    assertPass(
        RunEngine.run(
            input(directory, program),
            selected(21, "named-roots-diagnostic"),
            directory.resolve("unused")));
  }

  @Test
  void rejectsOrdinaryArrayRacesBeforeCompilation() throws IOException {
    var program =
        """
        gir 1; shape D data 1; object 1 D [] [0]; root r = 1;
        program { parallel {
          worker a { read-root d D r; write-data d 0 1; }
          worker b { read-root d D r; read-volatile-data value d 0; }
        } }
        """;
    Path run = directory.resolve("racy");
    assertEquals(
        RunReport.Outcome.UNSUPPORTED,
        RunEngine.run(input(directory, program), selected(21, "named-roots-diagnostic"), run)
            .execution());
    assertFalse(Files.exists(run.resolve("logs/compiler-command.json")));
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void verifiesContendedReferenceAndLongArrayElements(int feature) throws IOException {
    var programs =
        List.of(
            """
            gir 1; shape R refs 1; shape N object 0 1;
            object 1 R [null] []; object 2 N [] [0]; object 3 N [] [0];
            root shared = 1; root left = 2; root right = 3;
            program { parallel {
              worker a { read-root slots R shared; read-root n N left;
                compare-set-ref won slots 0 null n; write-data n 0 won; }
              worker b { read-root slots R shared; read-root n N right;
                compare-set-ref won slots 0 null n; write-data n 0 won; }
            } checkpoint; }
            """,
            """
            gir 1; shape D data 1; object 1 D [] [0]; root shared = 1;
            program { parallel {
              worker a { read-root values D shared; compare-set-data won values 0 0 1; }
              worker b { read-root values D shared; compare-set-data won values 0 0 1; }
            } checkpoint; }
            """);
    for (int i = 0; i < programs.size(); i++) {
      Path run = directory.resolve("contended-" + i);
      assertPass(
          RunEngine.run(
              input(directory, programs.get(i)), selected(feature, "named-roots-diagnostic"), run));
      assertPass(RunEngine.verify(run));
    }
  }

  private Path selected(int feature, String observation) throws IOException {
    Path selected = profile(directory, feature);
    var raw = JsonFiles.read(selected);
    raw.addProperty("observation", observation);
    JsonFiles.write(selected, raw);
    return selected;
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void recordsArrayCreatorIndependentlyOfPublicationOrder(int feature) throws IOException {
    var program =
        """
        gir 1; shape D data 1; root left = null; root right = null;
        program { parallel {
          worker a { allocate mine D; write-root left mine; read-volatile-root alias D left; }
          worker b { allocate mine D; write-root right mine; read-volatile-root alias D right; }
        } checkpoint; }
        """;
    Path run = directory.resolve("worker-arrays");
    assertPass(
        RunEngine.run(input(directory, program), selected(feature, "named-roots-diagnostic"), run));
    long token = JsonFiles.read(run.resolve("manifest.json")).get("run").getAsLong();
    var history = ReferenceHistory.read(run.resolve("traces/references.ghistory"), token);
    for (int worker : List.of(1, 2))
      assertTrue(
          history.events().stream()
              .anyMatch(
                  event ->
                      event.worker() == worker
                          && event.target() != null
                          && event.target().worker() == worker
                          && event.target().site() > 0
                          && event.target().occurrence() == 1));
  }
}
