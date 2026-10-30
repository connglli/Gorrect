package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.protocol.BindingEvidence;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BindingEvidenceTest {
  @TempDir Path directory;

  @Test
  void roundTripsPresenceAndRejectsEveryTruncatedPrefixAndAnotherCapture() throws IOException {
    var evidence =
        new BindingEvidence(
            11,
            7,
            42,
            0,
            1,
            List.of(new BindingEvidence.Binding(3, false), new BindingEvidence.Binding(9, true)));
    Path path = directory.resolve("binding.gbind");
    evidence.write(path);
    assertEquals(evidence, BindingEvidence.read(path, 11, 7));
    assertThrows(IOException.class, () -> BindingEvidence.read(path, 12, 7));
    assertThrows(IOException.class, () -> BindingEvidence.read(path, 11, 8));
    byte[] bytes = Files.readAllBytes(path);
    for (int length = 0; length < bytes.length; length++) {
      Files.write(path, Arrays.copyOf(bytes, length));
      assertThrows(IOException.class, () -> BindingEvidence.read(path, 11, 7));
    }
    Files.write(path, Arrays.copyOf(bytes, bytes.length + 1));
    assertThrows(IOException.class, () -> BindingEvidence.read(path, 11, 7));
  }

  @Test
  void rejectsDuplicateDeclarationsUnknownPresenceAndUnboundedCounts() throws IOException {
    Path path = directory.resolve("binding.gbind");
    new BindingEvidence(
            11,
            7,
            42,
            0,
            1,
            List.of(new BindingEvidence.Binding(3, false), new BindingEvidence.Binding(9, true)))
        .write(path);
    byte[] original = Files.readAllBytes(path);
    for (int offset : List.of(44, 52, 56)) {
      byte[] damaged = original.clone();
      ByteBuffer.wrap(damaged).putInt(offset, offset == 56 ? 3 : Integer.MAX_VALUE);
      Files.write(path, damaged);
      assertThrows(IOException.class, () -> BindingEvidence.read(path, 11, 7));
    }
  }
}
