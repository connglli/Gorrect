package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.protocol.QueueConsistency;
import io.connglli.gorrect.protocol.QueuePlan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueConsistencyProtocolTest {
  @TempDir Path directory;

  @Test
  void optionalSourceSelectionPreservesOldPlanBytes() throws Exception {
    var file = directory.resolve("queues.properties");
    var resources = List.of(new QueuePlan.Resource(1, "ready", "W"));
    new QueuePlan(resources).write(file);
    assertEquals("version=1\ncount=1\nqueue.1=ready,W\n", Files.readString(file));
    assertFalse(QueuePlan.read(file).automatic());
    var automatic = new QueuePlan(resources, true);
    automatic.write(file);
    assertEquals("version=1\ncount=1\nautomatic=true\nqueue.1=ready,W\n", Files.readString(file));
    assertEquals(automatic, QueuePlan.read(file));
    for (String damaged :
        List.of(
            "version=1\ncount=1\nautomatic=false\nqueue.1=ready,W\n",
            "version=1\ncount=0\nautomatic=true\n")) {
      Files.writeString(file, damaged);
      assertThrows(IOException.class, () -> QueuePlan.read(file));
    }
  }

  @Test
  void qualifiedJniOwnershipDoesNotInventJavaFrameEntries() throws Exception {
    var owned = new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 20, 20, 0, 0, 0);
    assertTrue(owned.owned(20));
    assertFalse(owned.owned(0));
    assertFalse(new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 0, 20, 1, 1, 0).owned(20));
    assertFalse(new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 21, 20, 1, 1, 0).owned(20));
    assertFalse(new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 20, 20, 0, 0, -1).owned(20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 20, 20, -1, 0, 0));
    var proof = new QueueConsistency(7, 2, 1, 20, 0, 0, 0, 0, List.of(owned));
    assertTrue(QueueConsistency.read(proof.encode(), 7, 2).complete());
  }

  @Test
  void witnessRetainsActualFailuresButRejectsWrongFramingIdentityAndQuota() throws Exception {
    var row = new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 20, 20, 1, 1, 0);
    var proof = new QueueConsistency(7, 2, 1, 20, 4, 4, 4, 4, List.of(row));
    var bytes = proof.encode();
    assertEquals(144, bytes.length);
    assertTrue(proof.complete());
    assertEquals(proof, QueueConsistency.read(bytes, 7, 2));
    assertArrayEquals(bytes, QueueConsistency.read(bytes, 7, 2).encode());
    assertThrows(IOException.class, () -> QueueConsistency.read(bytes, 8, 2));
    for (byte[] damaged :
        List.of(
            Arrays.copyOf(bytes, bytes.length - 1),
            Arrays.copyOf(bytes, bytes.length + 1),
            new byte[3925]))
      assertThrows(IOException.class, () -> QueueConsistency.read(damaged, 7, 2));
    for (int offset : new int[] {4, 72, 76, 88}) {
      var damaged = bytes.clone();
      ByteBuffer.wrap(damaged).putInt(offset, 65);
      assertThrows(IOException.class, () -> QueueConsistency.read(damaged, 7, 2));
    }
    var failed =
        new QueueConsistency(
            7,
            2,
            1,
            20,
            4,
            4,
            4,
            4,
            List.of(new QueueConsistency.Lock(1, 3, 0, 1, 10, 11, 0, 20, 0, 1, -1)));
    assertFalse(QueueConsistency.read(failed.encode(), 7, 2).complete());
  }
}
