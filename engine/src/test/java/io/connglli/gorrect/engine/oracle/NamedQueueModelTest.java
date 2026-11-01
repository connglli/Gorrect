package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirFormatter;
import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapQueue;
import io.connglli.gorrect.engine.model.HeapReference;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.model.WrapperId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NamedQueueModelTest {
  @Test
  void syntaxRoundTripsWithoutUniqueUserInstructionIds() {
    var program =
        GirParser.parse(
            """
            gir 1; shape W weak; queue ready W;
            program { allocate-ref r W null ready; enqueue-ref added r;
              poll-queue held ready; poll-queue; }
            """);
    assertEquals(program, GirParser.parse(GirFormatter.format(program)));
    assertEquals("ready", ((Instruction.AllocateReference) program.body().getFirst()).queue());
    assertTrue(((Instruction.PollQueue) program.body().get(2)).named());
    assertFalse(((Instruction.PollQueue) program.body().get(3)).named());
  }

  @Test
  void skippedCodeStillRejectsNonNullConstructionAndWrongNominalQueues() {
    for (String body :
        new String[] {
          "repeat 0 { allocate-ref r W null; allocate-ref bad W r ready; }",
          "allocate-ref bad S null ready;",
          "poll-queue bad missing;"
        }) {
      assertThrows(
          GirException.class,
          () ->
              GirParser.parse(
                  "gir 1; shape W weak; shape S soft; queue ready W; program {" + body + "}"));
    }
  }

  @Test
  void duplicateAndConsumedInsertionDoNotChangeMembershipAndForksKeepHistory() {
    var p =
        GirParser.parse(
            """
            gir 1; shape W weak; queue a W; queue b W;
            program { allocate-ref r W null a; enqueue-ref first r; enqueue-ref again r;
              poll-queue held a; enqueue-ref consumed r; }
            """);
    var m = new GraphMachine(p, OracleLimits.DEFAULT);
    var frontier = new ReferenceFrontier(HistoryLimits.DEFAULT);
    var id = new WrapperId(0, 1, 1, -1);
    m.apply((Instruction.Operation) p.body().get(0));
    frontier.register(m.referenceValue("r", -1), "a");
    assertEquals(
        Map.of("a", new HeapQueue(1, Set.of()), "b", new HeapQueue(2, Set.of())),
        m.graph().queues());
    assertTrue(frontier.enqueue(id, true));
    m.enqueueResult((Instruction.EnqueueReference) p.body().get(1), true);
    assertEquals(new Value.Scalar(1), m.bound("first"));
    assertTrue(frontier.enqueue(id, false));
    m.enqueueResult((Instruction.EnqueueReference) p.body().get(2), false);
    assertEquals(new Value.Scalar(0), m.bound("again"));
    var fork = new ReferenceFrontier(frontier);
    assertTrue(fork.poll("a", id));
    assertTrue(fork.enqueue(id, false));
    assertTrue(fork.states().iterator().next().members().isEmpty());
    assertFalse(frontier.states().iterator().next().members().isEmpty());
    assertNotEquals(frontier.key(), fork.key());
    // The shared machine stores resource identity and construction facts, never member predictions.
    assertTrue(m.graph().queues().get("a").members().isEmpty());
  }

  @Test
  void namedPollRejectsWrongConstructorQueueBeforeChangingBindingsOrCounters() {
    var p =
        GirParser.parse(
            """
            gir 1; shape W weak; queue a W; queue b W;
            program { allocate-ref r W null a; poll-queue held a; }
            """);
    var m = new GraphMachine(p, OracleLimits.DEFAULT);
    m.apply((Instruction.Operation) p.body().get(0));
    Graph graph = m.graph();
    long steps = m.steps();
    Object key = m.key();
    assertThrows(
        GirException.class,
        () -> m.pollNamed(new Instruction.PollQueue(2, "held", "b"), new WrapperId(0, 1, 1, -1)));
    assertEquals(graph, m.graph());
    assertEquals(steps, m.steps());
    assertEquals(key, m.key());
  }

  @Test
  void missingPhysicalQueueEvidenceCannotRemoveItsProtectionDuringPartialComparison() {
    var p =
        GirParser.parse(
            """
            gir 1; shape W weak; queue a W;
            program { allocate-ref inner W null a; allocate-ref witness W inner;
              enqueue-ref added inner; release inner; }
            """);
    var m = new GraphMachine(p, OracleLimits.DEFAULT);
    var f = new ReferenceFrontier(HistoryLimits.DEFAULT);
    m.apply((Instruction.Operation) p.body().get(0));
    f.register(m.referenceValue("inner", -1), "a");
    m.apply((Instruction.Operation) p.body().get(1));
    f.register(m.referenceValue("witness", -1), false);
    assertTrue(f.enqueue(new WrapperId(0, 1, 1, -1), true));
    m.enqueueResult((Instruction.EnqueueReference) p.body().get(2), true);
    m.apply((Instruction.Operation) p.body().get(3));
    f.expand(m.graph());
    var inner = new WrapperId(0, 1, 1, -1);
    var witness = new WrapperId(0, 2, 1, -1);
    var shape = p.shapes().getFirst();
    var observed =
        new Graph(
            Map.of(),
            Map.of(),
            m.graph().handles(),
            Map.of(inner, new HeapReference(shape, null), witness, new HeapReference(shape, null)),
            Map.of());
    assertFalse(f.matchCovered(m.graph(), observed).isEmpty());
    assertEquals(0, f.size());
  }

  @Test
  void coveredWrongIdentityRefutesEvenWhenItsMembershipIsUncovered() {
    var p = GirParser.parse("gir 1; shape W weak; queue a W; program { }");
    var m = new GraphMachine(p, OracleLimits.DEFAULT);
    var f = new ReferenceFrontier(HistoryLimits.DEFAULT);
    var observed =
        new Graph(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("a", new HeapQueue(9, null)));
    assertFalse(f.matchCovered(m.graph(), observed).isEmpty());
  }

  @Test
  void coveredNullResourceDiffersFromPresentEmptyQueue() {
    var p = GirParser.parse("gir 1; shape W weak; queue a W; program { }");
    var m = new GraphMachine(p, OracleLimits.DEFAULT);
    var f = new ReferenceFrontier(HistoryLimits.DEFAULT);
    var observed =
        new Graph(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("a", new HeapQueue(0, Set.of())));
    assertFalse(f.matchCovered(m.graph(), observed).isEmpty());
  }
}
