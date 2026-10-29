package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.protocol.ReferenceHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceHistoryTest {
  @TempDir Path directory;

  @Test
  void boundsAndValidatesSparseObservations() throws IOException {
    Path file = directory.resolve("references.ghistory");
    var value =
        new ReferenceHistory(
            17,
            true,
            List.of(
                new ReferenceHistory.Event(
                    2,
                    ReferenceHistory.Kind.ACQUIRE,
                    1,
                    new ReferenceHistory.Identity(0, 0, 1, 0),
                    new ReferenceHistory.Target(0, 0, 2)),
                new ReferenceHistory.Event(7, ReferenceHistory.Kind.QUEUE, 0, null, null)));
    value.write(file);
    assertEquals(value, ReferenceHistory.read(file, 17));
    assertThrows(IOException.class, () -> ReferenceHistory.read(file, 18));
    byte[] bytes = Files.readAllBytes(file);
    for (int length = 0; length < bytes.length; length++) {
      Files.write(file, java.util.Arrays.copyOf(bytes, length));
      assertThrows(IOException.class, () -> ReferenceHistory.read(file, 17));
    }
    Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length + 1));
    assertThrows(IOException.class, () -> ReferenceHistory.read(file, 17));
    new ReferenceHistory(17, true, List.of(value.events().getLast(), value.events().getFirst()))
        .write(file);
    assertThrows(IOException.class, () -> ReferenceHistory.read(file, 17));
  }
}
