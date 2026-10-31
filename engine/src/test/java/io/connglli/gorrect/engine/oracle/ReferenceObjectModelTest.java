package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapId;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceObjectModelTest {
  @TempDir Path directory;
  private static final ObjectId OBJECT = ObjectId.initial(1);
  private static final WrapperId A = new WrapperId(0, 10, 1, -1), B = new WrapperId(0, 11, 1, -1);

  @Test
  void wrappersAndOrdinaryObjectsHaveSeparateCanonicalNamespaces() {
    var ordinary = new ObjectId(0, 10, 1);
    assertTrue(!HeapId.object(ordinary).equals(HeapId.wrapper(A)));
    var graph =
        new Graph(
            Map.of(),
            Map.of("a", Value.Reference.toWrapper(A)),
            Map.of(),
            Map.of(A, new HeapReference(new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0), null)));
    assertEquals(Set.of(A), graph.accessible().wrappers().keySet());
    assertThrows(IllegalArgumentException.class, graph::required);
  }

  @Test
  void constructorBudgetAndInvalidReceiverLeaveTheMachineUnchanged() {
    var program =
        GirParser.parse(
            "gir 1;shape N object 0 0;shape W weak;program {allocate n N;allocate-ref w W n;}");
    var machine = new GraphMachine(program, new OracleLimits(100, 1, 100, 100));
    machine.apply((Instruction.Operation) program.body().getFirst());
    Graph before = machine.graph();
    long steps = machine.steps();
    assertThrows(
        OracleLimitException.class,
        () -> machine.apply((Instruction.Operation) program.body().get(1)));
    assertEquals(before, machine.graph());
    assertEquals(steps, machine.steps());
    assertThrows(
        RuntimeException.class, () -> machine.apply(new Instruction.ClearReference(3, "n", -1)));
    assertEquals(before, machine.graph());
    assertEquals(steps, machine.steps());
  }

  @Test
  void typedEnvelopePreservesFullTargetAndCannotUseLegacyWriterOrReader() throws IOException {
    var target = new ReferenceHistory.Target(0, 11, 1, 1, -1);
    var event =
        new ReferenceHistory.Event(
            0,
            1,
            ReferenceHistory.Kind.CREATE,
            2,
            new ReferenceHistory.Identity(0, 10, 1, -1),
            target);
    var history = new ReferenceHistory(7, true, List.of(event));
    Path file = directory.resolve("typed");
    history.writeTyped(file);
    assertEquals(history, ReferenceHistory.readTyped(file, 7));
    assertThrows(IOException.class, () -> ReferenceHistory.read(file, 7));
    assertThrows(IOException.class, () -> history.write(directory.resolve("legacy")));
    byte[] bytes = Files.readAllBytes(file);
    Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
    assertThrows(IOException.class, () -> ReferenceHistory.readTyped(file, 7));
  }

  @Test
  void legacyEnvelopeBytesStayExactAndUnknownTypedTagsAreRejected() throws IOException {
    var legacy =
        new ReferenceHistory(
            7,
            true,
            List.of(
                new ReferenceHistory.Event(
                    0,
                    1,
                    ReferenceHistory.Kind.ACQUIRE,
                    2,
                    new ReferenceHistory.Identity(0, 10, 1, -1),
                    new ReferenceHistory.Target(0, 2, 1))));
    Path file = directory.resolve("legacy-bytes");
    legacy.write(file);
    var bytes = new java.io.ByteArrayOutputStream();
    try (var out = new java.io.DataOutputStream(bytes)) {
      out.writeInt(0x47524631);
      out.writeInt(1);
      out.writeLong(7);
      out.writeBoolean(true);
      out.writeInt(1);
      out.writeInt(0);
      out.writeInt(1);
      out.writeByte(1);
      out.writeByte(2);
      out.writeInt(0);
      out.writeInt(10);
      out.writeLong(1);
      out.writeInt(-1);
      out.writeInt(0);
      out.writeInt(2);
      out.writeLong(1);
      out.writeLong(0x454e445245465331L);
    }
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        bytes.toByteArray(), Files.readAllBytes(file));
    legacy.writeTyped(file);
    byte[] tagged = Files.readAllBytes(file);
    tagged[67] = 2;
    Files.write(file, tagged);
    assertThrows(IOException.class, () -> ReferenceHistory.readTyped(file, 7));
  }

  @Test
  void legacyAndNoQueueWeakReferencesShareClearingButNotNotification() {
    var legacy = new WrapperId(0, 0, 1, 0);
    var owner =
        new HeapObject(
            new Shape("Owner", Shape.Kind.OBJECT, 1, 0),
            List.of(new Value.Reference(Value.ReferenceKind.WEAK, OBJECT, legacy)),
            List.of());
    var ownerId = ObjectId.initial(2);
    var graph =
        new Graph(
            Map.of(
                OBJECT,
                new HeapObject(new Shape("N", Shape.Kind.OBJECT, 0, 0), List.of(), List.of()),
                ownerId,
                owner),
            Map.of(
                "owner", Value.Reference.strong(ownerId), "wrapper", Value.Reference.toWrapper(A)),
            Map.of(),
            Map.of(
                A,
                new HeapReference(
                    new Shape("W", Shape.Kind.WEAK_REFERENCE, 0, 0), HeapId.object(OBJECT))));
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    frontier.register(new Value.Reference(Value.ReferenceKind.WEAK, OBJECT, legacy));
    frontier.register(edge(A, 2, 0), false);
    frontier.expand(graph);
    assertEquals(
        Set.of(0, 3),
        frontier.states().stream()
            .map(state -> mask(state.cleared()))
            .collect(java.util.stream.Collectors.toSet()));
    for (var state : frontier.states())
      assertEquals(state.cleared().get(0), state.pending().get(0));
    assertTrue(frontier.states().stream().noneMatch(state -> state.pending().get(1)));
  }

  @Test
  void cohortFrontierAgreesWithIndependentBatchAndMatrixModel() {
    // Vertex0 is an ordinary holder; vertices1/2 are canonical wrappers. Enumerate constructor
    // DAGs and holder cycles. Expected transitions enumerate whole clear batches using an
    // independent all-pairs relation rather than the production seed/reverse traversal.
    for (int kindA = 1; kindA <= 2; kindA++)
      for (int kindB = 1; kindB <= 2; kindB++)
        for (int targetA = -1; targetA <= 0; targetA++)
          for (int targetB = -1; targetB <= 1; targetB++)
            for (int holder = -1; holder <= 2; holder++)
              for (int roots = 0; roots < 8; roots++) {
                int[] kinds = {kindA, kindB}, targets = {targetA, targetB};
                var graph = graph(kinds, targets, holder, roots);
                var frontier = new ReferenceFrontier(new HistoryLimits(4096, 1_000_000));
                frontier.register(edge(A, kindA, targetA), false);
                frontier.register(edge(B, kindB, targetB), false);
                frontier.expand(graph);
                var actual = new HashSet<Integer>();
                for (var state : frontier.states()) {
                  actual.add(mask(state.cleared()));
                  assertTrue(state.pending().isEmpty());
                }
                assertEquals(
                    expected(kinds, targets, holder, roots),
                    actual,
                    List.of(kindA, kindB, targetA, targetB, holder, roots).toString());
              }
  }

  private static int mask(BitSet bits) {
    return (bits.get(0) ? 1 : 0) | (bits.get(1) ? 2 : 0);
  }

  private static HeapId id(int vertex) {
    return vertex < 0
        ? null
        : vertex == 0 ? HeapId.object(OBJECT) : HeapId.wrapper(vertex == 1 ? A : B);
  }

  private static Value.Reference edge(WrapperId wrapper, int kind, int target) {
    return new Value.Reference(
        kind == 1 ? Value.ReferenceKind.SOFT : Value.ReferenceKind.WEAK,
        target == 0 ? OBJECT : null,
        wrapper,
        target > 0 ? (target == 1 ? A : B) : null);
  }

  private static Graph graph(int[] kinds, int[] targets, int holder, int roots) {
    var ordinary =
        new HeapObject(
            new Shape("N", Shape.Kind.OBJECT, 1, 0),
            List.of(Value.Reference.strongHeap(id(holder))),
            List.of());
    var named = new HashMap<String, Value.Reference>();
    for (int i = 0; i < 3; i++)
      if ((roots & (1 << i)) != 0) named.put("r" + i, Value.Reference.strongHeap(id(i)));
    return new Graph(
        Map.of(OBJECT, ordinary),
        named,
        Map.of(),
        Map.of(
            A,
                new HeapReference(
                    new Shape(
                        "A",
                        kinds[0] == 1 ? Shape.Kind.SOFT_REFERENCE : Shape.Kind.WEAK_REFERENCE,
                        0,
                        0),
                    id(targets[0])),
            B,
                new HeapReference(
                    new Shape(
                        "B",
                        kinds[1] == 1 ? Shape.Kind.SOFT_REFERENCE : Shape.Kind.WEAK_REFERENCE,
                        0,
                        0),
                    id(targets[1]))));
  }

  private static Set<Integer> expected(int[] kinds, int[] targets, int holder, int roots) {
    int initial = (targets[0] < 0 ? 1 : 0) | (targets[1] < 0 ? 2 : 0);
    var found = new HashSet<Integer>();
    var queue = new ArrayDeque<Integer>();
    found.add(initial);
    queue.add(initial);
    while (!queue.isEmpty()) {
      int state = queue.remove();
      for (int strength = 1; strength <= 2; strength++)
        for (int delta = 1; delta < 4; delta++) {
          if ((delta & state) != 0) continue;
          boolean[][] strong = closure(kinds, targets, holder, state, 0),
              soft = closure(kinds, targets, holder, state, 1);
          boolean[][] predecessors = closure(kinds, targets, holder, state, strength == 1 ? 0 : 1);
          boolean[] protectedSet = reachable(strength == 1 ? strong : soft, roots);
          boolean valid = true;
          for (int i = 0; i < 2; i++)
            if ((delta & (1 << i)) != 0) {
              if (kinds[i] != strength || targets[i] < 0 || protectedSet[targets[i]]) {
                valid = false;
                break;
              }
              // Every unprotected predecessor connected by the specified edge relation contributes
              // all same-strength references to the clearing cohort.
              boolean[] eligible =
                  reachable(
                      closure(kinds, targets, holder, state, strength), roots | (1 << targets[i]));
              for (int j = 0; j < 2; j++)
                if (kinds[j] == strength
                    && targets[j] >= 0
                    && eligible[targets[j]]
                    && !protectedSet[targets[j]]
                    && predecessors[targets[j]][targets[i]]
                    && (state & (1 << j)) == 0
                    && (delta & (1 << j)) == 0) valid = false;
            }
          if (valid && found.add(state | delta)) queue.add(state | delta);
        }
    }
    return found;
  }

  private static boolean[][] closure(
      int[] kinds, int[] targets, int holder, int state, int strength) {
    boolean[][] paths = new boolean[3][3];
    for (int i = 0; i < 3; i++) paths[i][i] = true;
    if (holder >= 0) paths[0][holder] = true;
    for (int i = 0; i < 2; i++)
      if (targets[i] >= 0 && (state & (1 << i)) == 0 && kinds[i] <= strength)
        paths[i + 1][targets[i]] = true;
    for (int k = 0; k < 3; k++)
      for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) paths[i][j] |= paths[i][k] && paths[k][j];
    return paths;
  }

  private static boolean[] reachable(boolean[][] relation, int roots) {
    boolean[] result = new boolean[3];
    for (int i = 0; i < 3; i++)
      if ((roots & (1 << i)) != 0) for (int j = 0; j < 3; j++) result[j] |= relation[i][j];
    return result;
  }
}
