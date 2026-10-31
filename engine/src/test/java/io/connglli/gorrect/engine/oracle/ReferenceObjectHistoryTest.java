package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceObjectHistoryTest {
  private enum Entry {
    HISTORY,
    FAILURE,
    CONCURRENT,
    AUDIT;

    void invoke(Fixture fixture, boolean complete) {
      var trace = new ExecutionTrace(1, complete, fixture.trace().events());
      var history = new ReferenceHistory(1, complete, fixture.history().events());
      switch (this) {
        case HISTORY ->
            HistoryOracle.check(
                fixture.program(), trace, history, fixture.observations(), HistoryLimits.DEFAULT);
        case FAILURE ->
            HistoryOracle.checkFailure(
                fixture.program(), trace, history, fixture.observations(), HistoryLimits.DEFAULT);
        case CONCURRENT ->
            ConcurrentHistory.check(
                fixture.program(), trace, history, fixture.observations(), HistoryLimits.DEFAULT);
        case AUDIT -> ConcurrentHistory.audit(fixture.program(), HistoryLimits.DEFAULT);
      }
    }
  }

  private record Fixture(
      GirProgram program,
      ExecutionTrace trace,
      ReferenceHistory history,
      List<HistoryOracle.Observation> observations) {}

  @ParameterizedTest
  @EnumSource(Entry.class)
  void everyPublicEntryRejectsAtomicCompositesBeforeUsingEvidence(Entry entry) {
    for (boolean allocate : List.of(false, true))
      for (boolean complete : List.of(false, true)) {
        var fixture = atomic(true, allocate, false);
        var failure =
            assertThrows(UnsupportedHistoryException.class, () -> entry.invoke(fixture, complete));
        assertTrue(failure.getMessage().contains("First-class wrapper atomic/concurrent"));
      }
  }

  @ParameterizedTest
  @EnumSource(Entry.class)
  void everyPublicEntryRejectsParallelCompositesEvenWithIncompleteEvidence(Entry entry) {
    var program = GirParser.parse("gir 1; shape W soft; program { parallel { worker only {} } }");
    var fixture =
        new Fixture(
            program,
            new ExecutionTrace(1, true, List.of()),
            new ReferenceHistory(1, true, List.of()),
            List.of());
    for (boolean complete : List.of(false, true))
      assertThrows(UnsupportedHistoryException.class, () -> entry.invoke(fixture, complete));
  }

  @ParameterizedTest
  @EnumSource(Entry.class)
  void staticErrorsTakePrecedenceOverUnsupportedCompositesAndIncompleteEvidence(Entry entry) {
    var fixture = atomic(true, false, false);
    var source = fixture.program();
    var body = new ArrayList<>(source.body());
    body.add(new Instruction.Release(body.size() + 1, "missing"));
    var invalid =
        new GirProgram(source.version(), source.shapes(), source.objects(), source.roots(), body);
    var failure =
        assertThrows(
            GirException.class,
            () ->
                entry.invoke(
                    new Fixture(
                        invalid, fixture.trace(), fixture.history(), fixture.observations()),
                    false));
    assertTrue(failure.getMessage().contains("missing"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"weak", "soft"})
  void sequentialReferencesUseTheNormalRouteAndAreRejectedByDirectConcurrentEntries(String kind) {
    var program =
        GirParser.parse(
            "gir 1; shape N object 0 0; shape W "
                + kind
                + "; program { allocate n N; allocate-ref w W n; release w; release n; }");
    var trace =
        new ExecutionTrace(
            1,
            true,
            List.of(
                new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(2, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(3, ExecutionTrace.Kind.OPERATION, 0),
                new ExecutionTrace.Event(4, ExecutionTrace.Kind.OPERATION, 0)));
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                new ReferenceHistory.Event(
                    2,
                    ReferenceHistory.Kind.CREATE,
                    kind.equals("soft") ? 1 : 2,
                    new ReferenceHistory.Identity(0, 2, 1, -1),
                    new ReferenceHistory.Target(0, 1, 1))));
    var observations =
        List.of(
            new HistoryOracle.Observation(
                Optional.of(new Graph(Map.of(), Map.of(), Map.of())), List.of()));
    var fixture = new Fixture(program, trace, history, observations);
    assertEquals(ExactVerifier.Verdict.SATISFIED, check(fixture, true).verdict());
    assertThrows(
        UnsupportedHistoryException.class,
        () -> ConcurrentHistory.audit(program, HistoryLimits.DEFAULT));
    for (boolean complete : List.of(false, true)) {
      var failure =
          assertThrows(
              UnsupportedHistoryException.class, () -> Entry.CONCURRENT.invoke(fixture, complete));
      assertTrue(failure.getMessage().contains("concurrent history backend"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryAndOpaqueLegacyAtomicHistoriesRemainSupported(boolean legacy) {
    var fixture = atomic(false, false, legacy);
    assertDoesNotThrow(() -> ConcurrentHistory.audit(fixture.program(), HistoryLimits.DEFAULT));
    for (boolean delegated : List.of(false, true))
      assertEquals(ExactVerifier.Verdict.SATISFIED, check(fixture, delegated).verdict());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void taggedCollisionsCannotSatisfyOrdinaryReadsOrLegacyAcquisitions(boolean delegated) {
    for (var fixture : List.of(atomic(false, false, false), legacyAcquisition())) {
      assertEquals(ExactVerifier.Verdict.SATISFIED, check(fixture, delegated).verdict());
      var events = new ArrayList<>(fixture.history().events());
      var original = events.getLast();
      var target = original.target();
      events.set(
          events.size() - 1,
          new ReferenceHistory.Event(
              original.worker(),
              original.sequence(),
              original.kind(),
              original.strength(),
              original.wrapper(),
              new ReferenceHistory.Target(
                  target.worker(), target.site(), target.occurrence(), 1, 0)));
      var result =
          check(
              new Fixture(
                  fixture.program(),
                  fixture.trace(),
                  new ReferenceHistory(1, true, events),
                  fixture.observations()),
              delegated);
      assertEquals(ExactVerifier.Verdict.VIOLATED, result.verdict());
      assertTrue(result.problems().getFirst().contains("namespace"));
    }
  }

  private static HistoryOracle.Result check(Fixture fixture, boolean delegated) {
    return delegated
        ? HistoryOracle.check(
            fixture.program(),
            fixture.trace(),
            fixture.history(),
            fixture.observations(),
            HistoryLimits.DEFAULT)
        : ConcurrentHistory.check(
            fixture.program(),
            fixture.trace(),
            fixture.history(),
            fixture.observations(),
            HistoryLimits.DEFAULT);
  }

  private static Fixture legacyAcquisition() {
    var source = atomic(false, false, true);
    var program = source.program();
    var body = new ArrayList<>(program.body().subList(0, 2));
    body.add(new Instruction.Acquire(3, "held", "N", "n", 0, List.of()));
    body.add(new Instruction.Release(4, "n"));
    var events = new ArrayList<>(source.trace().events().subList(0, 2));
    events.add(new ExecutionTrace.Event(3, ExecutionTrace.Kind.ACQUIRE, 1));
    events.add(new ExecutionTrace.Event(3, ExecutionTrace.Kind.ACQUIRE_END, 0));
    events.add(new ExecutionTrace.Event(4, ExecutionTrace.Kind.OPERATION, 0));
    var identities = new ArrayList<>(source.history().events());
    identities.add(
        new ReferenceHistory.Event(
            3,
            ReferenceHistory.Kind.ACQUIRE,
            2,
            new ReferenceHistory.Identity(0, 0, 1, 0),
            new ReferenceHistory.Target(0, 0, 2)));
    return new Fixture(
        new GirProgram(
            program.version(), program.shapes(), program.objects(), program.roots(), body),
        new ExecutionTrace(1, true, events),
        new ReferenceHistory(1, true, identities),
        source.observations());
  }

  /**
   * Hand-written CAS results and final payload, independent of model execution and trace replay.
   */
  private static Fixture atomic(boolean firstClass, boolean allocate, boolean legacy) {
    var program =
        GirParser.parse(
            "gir 1; shape N object "
                + (legacy ? "1" : "0")
                + " 1; "
                + (firstClass ? "shape W weak; " : "")
                + (legacy
                    ? "object 1 N [weak(2)] [0]; object 2 N [null] [7]; "
                    : "object 1 N [] [0]; ")
                + "root r = 1; program { read-root n N r; "
                + (allocate ? "allocate-ref w W n; " : "")
                + "compare-set-data won n 0 0 1; "
                + (allocate ? "release w; " : "")
                + "release n; }");
    var trace = new ArrayList<ExecutionTrace.Event>();
    var identities = new ArrayList<ReferenceHistory.Event>();
    var target = new ReferenceHistory.Target(0, 0, 1);
    trace.add(new ExecutionTrace.Event(1, ExecutionTrace.Kind.REFERENCE_READ, 1));
    identities.add(
        new ReferenceHistory.Event(1, ReferenceHistory.Kind.STRONG_READ, 0, null, target));
    int operation = 2;
    if (allocate) {
      trace.add(new ExecutionTrace.Event(operation, ExecutionTrace.Kind.OPERATION, 0));
      identities.add(
          new ReferenceHistory.Event(
              0,
              2,
              ReferenceHistory.Kind.CREATE,
              2,
              new ReferenceHistory.Identity(0, operation, 1, -1),
              target));
      operation++;
    }
    trace.add(new ExecutionTrace.Event(operation++, ExecutionTrace.Kind.COMPARE_SET, 1));
    if (allocate)
      trace.add(new ExecutionTrace.Event(operation++, ExecutionTrace.Kind.OPERATION, 0));
    trace.add(new ExecutionTrace.Event(operation, ExecutionTrace.Kind.OPERATION, 0));
    var owner = ObjectId.initial(1);
    var shape = new Shape("N", Shape.Kind.OBJECT, legacy ? 1 : 0, 1);
    var objects =
        legacy
            ? Map.of(
                owner,
                new HeapObject(
                    shape,
                    List.of(
                        new Value.Reference(
                            Value.ReferenceKind.WEAK,
                            ObjectId.initial(2),
                            new WrapperId(0, 0, 1, 0))),
                    List.of(1L)),
                ObjectId.initial(2),
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of(7L)))
            : Map.of(owner, new HeapObject(shape, List.of(), List.of(1L)));
    var graph = new Graph(objects, Map.of("r", Value.Reference.strong(owner)), Map.of());
    return new Fixture(
        program,
        new ExecutionTrace(1, true, trace),
        new ReferenceHistory(1, true, identities),
        List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())));
  }
}
