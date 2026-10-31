package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.protocol.QueueCapture;
import io.connglli.gorrect.protocol.QueueHistory;
import io.connglli.gorrect.protocol.QueuePlan;
import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueProtocolTest {
  @TempDir Path directory;

  @Test
  void resourceGoldenHasStrictPolicyIdentityAndEof() throws Exception {
    byte[] bytes;
    try (var in = getClass().getResourceAsStream("/wire/queue-capture-v1.hex")) {
      bytes = HexFormat.of().parseHex(new String(in.readAllBytes()).replaceAll("\\s", ""));
    }
    assertArrayEquals(bytes, QueueCapture.read(bytes, 1, 1).encode());
    assertThrows(IOException.class, () -> QueueCapture.read(bytes, 2, 1));
    assertThrows(
        IOException.class, () -> QueueCapture.read(Arrays.copyOf(bytes, bytes.length + 1), 1, 1));
    var policy = bytes.clone();
    ByteBuffer.wrap(policy).putInt(8, 2);
    assertThrows(IOException.class, () -> QueueCapture.read(policy, 1, 1));
    var length = bytes.clone();
    ByteBuffer.wrap(length).putInt(12, Integer.MAX_VALUE);
    assertThrows(IOException.class, () -> QueueCapture.read(length, 1, 1));
  }

  @Test
  void queuePlanIsCanonicalAndSupportsExplicitZeroResourceSelection() throws Exception {
    for (var plan :
        List.of(
            new QueuePlan(List.of()),
            new QueuePlan(List.of(new QueuePlan.Resource(1, "ready", "W"))))) {
      var path = directory.resolve("queues.properties");
      plan.write(path);
      assertEquals(plan, QueuePlan.read(path));
      Files.writeString(path, Files.readString(path) + "count=0\n");
      assertThrows(IOException.class, () -> QueuePlan.read(path));
    }
  }

  @Test
  void queueHistoryRetainsActualUnknownsAndRejectsTrailingOrUnorderedData() throws Exception {
    var id = new ReferenceHistory.Identity(0, 1, 1, -1);
    var history =
        new QueueHistory(
            1, true, List.of(new QueueHistory.Event(0, 1, 1, QueueHistory.Kind.CREATE, -1, 0, id)));
    var path = directory.resolve("queue.qhistory");
    history.write(path);
    assertEquals(history, QueueHistory.read(path, 1));
    Files.write(path, Arrays.copyOf(Files.readAllBytes(path), (int) Files.size(path) + 1));
    assertThrows(IOException.class, () -> QueueHistory.read(path, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new QueueHistory(
                1, true, List.of(history.events().getFirst(), history.events().getFirst())));
  }
}
