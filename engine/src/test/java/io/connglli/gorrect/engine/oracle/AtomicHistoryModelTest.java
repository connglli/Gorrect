package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicHistoryModelTest {
  @Test
  void agreesWithIndependentExchangePermutationModel() {
    var allowed = new HashSet<List<Integer>>();
    for (var order : permutations(List.of(0, 1))) {
      int current = 1;
      int[] reads = new int[2];
      for (int worker : order) {
        reads[worker] = current;
        current = worker + 2;
      }
      allowed.add(List.of(reads[0], reads[1], current));
    }
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 0;
            object 1 N [] []; object 2 N [] []; object 3 N [] [];
            root value = 1; root left = 2; root right = 3; root returned_left = null; root returned_right = null;
            program { parallel {
              worker a { read-volatile-root candidate N left; exchange-root old N value candidate; write-volatile-root returned_left old; }
              worker b { read-volatile-root candidate N right; exchange-root old N value candidate; write-volatile-root returned_right old; }
            } }
            """);
    var objects = new HashMap<ObjectId, HeapObject>();
    for (int id = 1; id <= 3; id++)
      objects.put(
          ObjectId.initial(id), new HeapObject(program.shapes().getFirst(), List.of(), List.of()));
    for (int left = 1; left <= 3; left++)
      for (int right = 1; right <= 3; right++)
        for (int last = 1; last <= 3; last++) {
          var events = main();
          var identities = new ArrayList<ReferenceHistory.Event>();
          for (int worker = 1; worker <= 2; worker++) {
            int operation = worker == 1 ? 2 : 5, returned = worker == 1 ? left : right;
            events.addAll(
                List.of(
                    event(worker, 1, ExecutionTrace.Kind.WORKER_START, 0),
                    event(worker, operation, ExecutionTrace.Kind.REFERENCE_READ, 1),
                    event(worker, operation + 1, ExecutionTrace.Kind.REFERENCE_READ, 1),
                    event(worker, operation + 2, ExecutionTrace.Kind.OPERATION, 0),
                    event(worker, 1, ExecutionTrace.Kind.WORKER_END, 0)));
            identities.add(identity(worker, 2, ObjectId.initial(worker + 1)));
            identities.add(identity(worker, 3, ObjectId.initial(returned)));
          }
          var graph =
              new Graph(
                  objects,
                  Map.of(
                      "value",
                      ref(last),
                      "left",
                      ref(2),
                      "right",
                      ref(3),
                      "returned_left",
                      ref(left),
                      "returned_right",
                      ref(right)),
                  Map.of());
          var result =
              ConcurrentHistory.check(
                  program,
                  new ExecutionTrace(1, true, events),
                  new ReferenceHistory(1, true, identities),
                  List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
                  LIMITS);
          assertEquals(
              allowed.contains(List.of(left, right, last))
                  ? ExactVerifier.Verdict.SATISFIED
                  : ExactVerifier.Verdict.VIOLATED,
              result.verdict(),
              result.toString());
        }
  }

  private record Cas(int left, int right, int winner) {}

  private static final HistoryLimits LIMITS = new HistoryLimits(4096, 1_000_000);

  @Test
  void agreesWithIndependentCasPermutationModel() {
    var allowed = new HashSet<Cas>();
    for (var order : permutations(List.of(0, 1))) {
      int value = 0;
      int[] successes = new int[2];
      for (int worker : order)
        if (value == 0) {
          value = worker + 1;
          successes[worker] = 1;
        }
      allowed.add(new Cas(successes[0], successes[1], value));
    }
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [0]; object 2 N [] [0];
            root left = 1; root right = 2; root winner = null;
            program { parallel {
              worker a { read-volatile-root n N left; compare-set-root won winner null n; write-data n 0 won; }
              worker b { read-volatile-root n N right; compare-set-root won winner null n; write-data n 0 won; }
            } }
            """);
    var shape = program.shapes().getFirst();
    for (int left = 0; left < 2; left++)
      for (int right = 0; right < 2; right++)
        for (int winner = 0; winner <= 2; winner++) {
          var events = main();
          for (int worker = 1; worker <= 2; worker++) {
            int read = worker == 1 ? 2 : 5;
            events.add(event(worker, 1, ExecutionTrace.Kind.WORKER_START, 0));
            events.add(event(worker, read, ExecutionTrace.Kind.REFERENCE_READ, 1));
            events.add(
                event(
                    worker, read + 1, ExecutionTrace.Kind.COMPARE_SET, worker == 1 ? left : right));
            events.add(event(worker, read + 2, ExecutionTrace.Kind.OPERATION, 0));
            events.add(event(worker, 1, ExecutionTrace.Kind.WORKER_END, 0));
          }
          var graph =
              new Graph(
                  Map.of(
                      ObjectId.initial(1),
                      new HeapObject(shape, List.of(), List.of((long) left)),
                      ObjectId.initial(2),
                      new HeapObject(shape, List.of(), List.of((long) right))),
                  Map.of("left", ref(1), "right", ref(2), "winner", ref(winner)),
                  Map.of());
          var history =
              new ReferenceHistory(
                  1,
                  true,
                  List.of(
                      identity(1, 2, ObjectId.initial(1)), identity(2, 2, ObjectId.initial(2))));
          var result =
              ConcurrentHistory.check(
                  program,
                  new ExecutionTrace(1, true, events),
                  history,
                  List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
                  LIMITS);
          assertEquals(
              allowed.contains(new Cas(left, right, winner))
                  ? ExactVerifier.Verdict.SATISFIED
                  : ExactVerifier.Verdict.VIOLATED,
              result.verdict(),
              left + "/" + right + "/" + winner + ": " + result.problems());
        }
  }

  @ParameterizedTest
  @ValueSource(strings = {"object 0 2", "data 2"})
  void agreesWithExhaustiveVolatileStoreBufferingSchedules(String layout) {
    var allowed = new HashSet<List<Integer>>();
    for (var order : permutations(List.of(0, 1, 2, 3))) {
      if (order.indexOf(0) > order.indexOf(1) || order.indexOf(2) > order.indexOf(3)) continue;
      int[] fields = new int[2], reads = new int[2];
      for (int action : order)
        switch (action) {
          case 0 -> fields[0] = 1;
          case 1 -> reads[0] = fields[1];
          case 2 -> fields[1] = 1;
          case 3 -> reads[1] = fields[0];
          default -> throw new AssertionError();
        }
      allowed.add(List.of(reads[0], reads[1]));
    }
    var program =
        GirParser.parse(
            """
            gir 1; shape N %s; object 1 N [] [0, 0]; root r = 1;
            program { parallel {
              worker a { read-volatile-root n N r; write-volatile-data n 0 1; read-volatile-data value n 1; }
              worker b { read-volatile-root n N r; write-volatile-data n 1 1; read-volatile-data value n 0; }
            } }
            """
                .formatted(layout));
    var graph =
        new Graph(
            Map.of(
                ObjectId.initial(1),
                new HeapObject(program.shapes().getFirst(), List.of(), List.of(1L, 1L))),
            Map.of("r", ref(1)),
            Map.of());
    for (int left = 0; left <= 1; left++)
      for (int right = 0; right <= 1; right++) {
        var events = main();
        for (int worker = 1; worker <= 2; worker++) {
          int read = worker == 1 ? 2 : 5;
          events.add(event(worker, 1, ExecutionTrace.Kind.WORKER_START, 0));
          events.add(event(worker, read, ExecutionTrace.Kind.REFERENCE_READ, 1));
          events.add(event(worker, read + 1, ExecutionTrace.Kind.OPERATION, 0));
          events.add(
              event(worker, read + 2, ExecutionTrace.Kind.SCALAR_READ, worker == 1 ? left : right));
          events.add(event(worker, 1, ExecutionTrace.Kind.WORKER_END, 0));
        }
        var history =
            new ReferenceHistory(
                1,
                true,
                List.of(identity(1, 2, ObjectId.initial(1)), identity(2, 2, ObjectId.initial(1))));
        var result =
            ConcurrentHistory.check(
                program,
                new ExecutionTrace(1, true, events),
                history,
                List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
                LIMITS);
        assertEquals(
            allowed.contains(List.of(left, right))
                ? ExactVerifier.Verdict.SATISFIED
                : ExactVerifier.Verdict.VIOLATED,
            result.verdict(),
            result.toString());
      }
  }

  @Test
  void publicationRequiresTheInitializedValue() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; root published = null;
            program { parallel {
              worker producer { allocate n N; write-data n 0 7; write-volatile-root published n; }
              worker consumer { read-volatile-root n N published; if-present n { read-data value n 0; } }
            } }
            """);
    var id = new ObjectId(1, 2, 1);
    var graph =
        new Graph(
            Map.of(id, new HeapObject(program.shapes().getFirst(), List.of(), List.of(7L))),
            Map.of("published", Value.Reference.strong(id)),
            Map.of());
    for (int value : new int[] {0, 7}) {
      var events = main();
      events.addAll(
          List.of(
              event(1, 1, ExecutionTrace.Kind.WORKER_START, 0),
              event(1, 2, ExecutionTrace.Kind.OPERATION, 0),
              event(1, 3, ExecutionTrace.Kind.OPERATION, 0),
              event(1, 4, ExecutionTrace.Kind.OPERATION, 0),
              event(1, 1, ExecutionTrace.Kind.WORKER_END, 0),
              event(2, 1, ExecutionTrace.Kind.WORKER_START, 0),
              event(2, 5, ExecutionTrace.Kind.REFERENCE_READ, 1),
              event(2, 6, ExecutionTrace.Kind.BRANCH, 1),
              event(2, 7, ExecutionTrace.Kind.SCALAR_READ, value),
              event(2, 1, ExecutionTrace.Kind.WORKER_END, 0)));
      var history = new ReferenceHistory(1, true, List.of(identity(2, 2, id)));
      var result =
          ConcurrentHistory.check(
              program,
              new ExecutionTrace(1, true, events),
              history,
              List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
              LIMITS);
      assertEquals(
          value == 7 ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
          result.verdict(),
          result.toString());
    }
  }

  @Test
  void anotherWorkersHandleProtectsTheReferentUntilAcknowledgement() {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 0;
            object 1 N [weak(2)] []; object 2 N [null] []; object 3 N [null] [];
            root owner = 1; root guard = 2; root done = null; root marker = 3;
            program { parallel {
              worker holder {
                read-root owner N owner;
                acquire held N owner 0 { write-volatile-root guard null; read-volatile-root acknowledgement N done; }
              }
              worker observer {
                read-root owner N owner; is-cleared cleared owner 0;
                read-root marker N marker; write-volatile-root done marker;
              }
            } }
            """);
    var shape = program.shapes().getFirst();
    var wrapper = new WrapperId(0, 0, 1, 0);
    var graph =
        new Graph(
            Map.of(
                ObjectId.initial(1),
                new HeapObject(
                    shape,
                    List.of(new Value.Reference(Value.ReferenceKind.WEAK, null, wrapper)),
                    List.of()),
                ObjectId.initial(2),
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of()),
                ObjectId.initial(3),
                new HeapObject(shape, List.of(Value.Reference.NULL), List.of())),
            Map.of("owner", ref(1), "guard", ref(0), "done", ref(3), "marker", ref(3)),
            Map.of());
    var uid = new ReferenceHistory.Identity(0, 0, 1, 0);
    var history =
        new ReferenceHistory(
            1,
            true,
            List.of(
                identity(1, 2, ObjectId.initial(1)),
                new ReferenceHistory.Event(
                    1,
                    3,
                    ReferenceHistory.Kind.ACQUIRE,
                    2,
                    uid,
                    new ReferenceHistory.Target(0, 0, 2)),
                identity(1, 5, ObjectId.initial(3)),
                identity(2, 2, ObjectId.initial(1)),
                new ReferenceHistory.Event(2, 3, ReferenceHistory.Kind.STATE, 2, uid, null),
                identity(2, 4, ObjectId.initial(3))));
    for (int cleared = 0; cleared <= 1; cleared++) {
      var events = main();
      events.addAll(
          List.of(
              event(1, 1, ExecutionTrace.Kind.WORKER_START, 0),
              event(1, 2, ExecutionTrace.Kind.REFERENCE_READ, 1),
              event(1, 3, ExecutionTrace.Kind.ACQUIRE, 1),
              event(1, 4, ExecutionTrace.Kind.OPERATION, 0),
              event(1, 5, ExecutionTrace.Kind.REFERENCE_READ, 1),
              event(1, 3, ExecutionTrace.Kind.ACQUIRE_END, 0),
              event(1, 1, ExecutionTrace.Kind.WORKER_END, 0),
              event(2, 1, ExecutionTrace.Kind.WORKER_START, 0),
              event(2, 6, ExecutionTrace.Kind.REFERENCE_READ, 1),
              event(2, 7, ExecutionTrace.Kind.REFERENCE_STATE, cleared),
              event(2, 8, ExecutionTrace.Kind.REFERENCE_READ, 1),
              event(2, 9, ExecutionTrace.Kind.OPERATION, 0),
              event(2, 1, ExecutionTrace.Kind.WORKER_END, 0)));
      // Acknowledgement is observed before acquisition exit, so the earlier clearing read
      // occurs while the holder's result or the initial guard is still strong.
      var result =
          ConcurrentHistory.check(
              program,
              new ExecutionTrace(1, true, events),
              history,
              List.of(new HistoryOracle.Observation(Optional.of(graph), List.of())),
              new HistoryLimits(4096, 10_000_000));
      assertEquals(
          cleared == 0 ? ExactVerifier.Verdict.SATISFIED : ExactVerifier.Verdict.VIOLATED,
          result.verdict(),
          result.toString());
    }
  }

  private static Value.Reference ref(int id) {
    return id == 0 ? Value.Reference.NULL : Value.Reference.strong(ObjectId.initial(id));
  }

  private static ArrayList<ExecutionTrace.Event> main() {
    return new ArrayList<>(
        List.of(
            new ExecutionTrace.Event(1, ExecutionTrace.Kind.OPERATION, 0),
            new ExecutionTrace.Event(1, ExecutionTrace.Kind.JOIN, 1),
            new ExecutionTrace.Event(1, ExecutionTrace.Kind.JOIN, 2)));
  }

  private static ExecutionTrace.Event event(
      int worker, int operation, ExecutionTrace.Kind kind, long value) {
    return new ExecutionTrace.Event(operation, kind, value, worker, 1, 1, 0);
  }

  private static ReferenceHistory.Event identity(int worker, int position, ObjectId target) {
    return new ReferenceHistory.Event(
        worker,
        position,
        ReferenceHistory.Kind.STRONG_READ,
        0,
        null,
        new ReferenceHistory.Target(target.worker(), target.site(), target.occurrence()));
  }

  private static <T> List<List<T>> permutations(List<T> values) {
    if (values.isEmpty()) return List.of(List.of());
    var result = new ArrayList<List<T>>();
    for (int index = 0; index < values.size(); index++) {
      var tail = new ArrayList<>(values);
      var head = tail.remove(index);
      for (var suffix : permutations(tail)) {
        var order = new ArrayList<T>();
        order.add(head);
        order.addAll(suffix);
        result.add(order);
      }
    }
    return result;
  }
}
