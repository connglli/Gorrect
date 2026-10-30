package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {
  private byte[] golden() throws IOException {
    try (var input = getClass().getResourceAsStream("/wire/snapshot-v1.hex")) {
      return HexFormat.of()
          .parseHex(
              new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
                  .replaceAll("\\s", ""));
    }
  }

  @Test
  void readsAndWritesTheSameGoldenBytesAsNativeCode() throws IOException {
    var snapshot =
        new HeapSnapshot(
            17,
            23,
            HeapSnapshot.Status.COMPLETE,
            0,
            1,
            10,
            10,
            10,
            10,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new HeapSnapshot.Record(
                    HeapSnapshot.Kind.PRIMITIVE_FIELD, 7, 3, 74, -9, -1, 0, 0)));
    assertEquals(snapshot, SnapshotCodec.read(golden(), 17, 23));
    assertArrayEquals(golden(), SnapshotCodec.write(snapshot));
  }

  @Test
  void rejectsEveryTruncatedGoldenPrefixAndWrongProvenance() throws IOException {
    byte[] complete = golden();
    for (int length = 0; length < complete.length; length++) {
      byte[] prefix = Arrays.copyOf(complete, length);
      assertThrows(IOException.class, () -> SnapshotCodec.read(prefix));
    }
    assertThrows(IOException.class, () -> SnapshotCodec.read(complete, 18, 23));
    assertThrows(IOException.class, () -> SnapshotCodec.read(complete, 17, 24));
    assertThrows(
        IOException.class, () -> SnapshotCodec.read(Arrays.copyOf(complete, complete.length + 1)));
  }

  @Test
  void rejectsUnknownFormatKindsAndUnboundedCounts() throws IOException {
    for (int offset : List.of(0, 4, 24, 72, 84, 88)) {
      byte[] bytes = golden();
      Arrays.fill(bytes, offset, offset + 4, (byte) 0x7f);
      assertThrows(IOException.class, () -> SnapshotCodec.read(bytes));
    }
  }
}
