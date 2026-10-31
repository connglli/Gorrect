package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.ObserverPlan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedEvidenceTest {
  @TempDir Path directory;

  @Test
  void matchesNativeGoldenCodeAndCopiesItsMutableBytes() throws IOException {
    byte[] bytes = HexFormat.of().parseHex("cafebabe");
    var evidence =
        new CodeEvidence(
            17,
            CodeEvidence.Stage.STARTUP,
            CodeEvidence.Status.VALID,
            0,
            2,
            1,
            1,
            15,
            bytes,
            List.of(new CodeEvidence.Method(23, "execute", "()V")));
    bytes[0] = 0;
    Path path = directory.resolve("code");
    evidence.write(path);
    assertArrayEquals(golden("code-v1.hex"), Files.readAllBytes(path));
    var read = CodeEvidence.read(path, 17, CodeEvidence.Stage.STARTUP);
    byte[] copy = read.code();
    copy[0] = 0;
    assertArrayEquals(HexFormat.of().parseHex("cafebabe"), read.code());
    assertEquals(evidence.methods(), read.methods());
    assertThrows(UnsupportedOperationException.class, () -> read.methods().clear());
  }

  @Test
  void matchesNativeGoldenFramesAndPreservesUnavailableBci() throws IOException {
    var frame = new FrameEvidence.Frame(23, 0, 5);
    var evidence =
        new FrameEvidence(
            17,
            3,
            CodeEvidence.Status.VALID,
            0,
            2,
            2,
            List.of(
                new FrameEvidence.Participant(
                    0, 7, FrameEvidence.Role.EXECUTE, List.of(frame), List.of(frame))));
    Path path = directory.resolve("frame");
    evidence.write(path);
    assertArrayEquals(golden("frame-v1.hex"), Files.readAllBytes(path));
    assertEquals(evidence, FrameEvidence.read(path, 17, 3));
    assertEquals(-1, new FrameEvidence.Frame(23, 0, -1).bci());
  }

  @Test
  void rejectsWrongIdentityStageFooterAndEveryTruncatedGoldenPrefix() throws IOException {
    byte[] code = golden("code-v1.hex"), frame = golden("frame-v1.hex");
    assertThrows(IOException.class, () -> CodeEvidence.read(code, 18, CodeEvidence.Stage.STARTUP));
    assertThrows(IOException.class, () -> CodeEvidence.read(code, 17, CodeEvidence.Stage.FINAL));
    assertThrows(IOException.class, () -> FrameEvidence.read(frame, 17, 4));
    for (int size = 0; size < code.length; size++) {
      byte[] prefix = Arrays.copyOf(code, size);
      assertThrows(
          IOException.class, () -> CodeEvidence.read(prefix, 17, CodeEvidence.Stage.STARTUP));
    }
    for (int size = 0; size < frame.length; size++) {
      byte[] prefix = Arrays.copyOf(frame, size);
      assertThrows(IOException.class, () -> FrameEvidence.read(prefix, 17, 3));
    }
    assertThrows(
        IOException.class,
        () ->
            CodeEvidence.read(
                Arrays.copyOf(code, code.length + 1), 17, CodeEvidence.Stage.STARTUP));
    assertThrows(
        IOException.class, () -> FrameEvidence.read(Arrays.copyOf(frame, frame.length + 1), 17, 3));
  }

  @Test
  void rejectsUnboundedCountsUnknownStatusesAndDuplicateParticipants() throws IOException {
    byte[] code = golden("code-v1.hex");
    ByteBuffer.wrap(code).putInt(56, CodeEvidence.MAX_CODE_BYTES + 1);
    assertThrows(IOException.class, () -> CodeEvidence.read(code, 17, CodeEvidence.Stage.STARTUP));
    byte[] frame = golden("frame-v1.hex");
    ByteBuffer.wrap(frame).putInt(24, 5);
    assertThrows(IOException.class, () -> FrameEvidence.read(frame, 17, 3));
    var participant =
        new FrameEvidence.Participant(0, 7, FrameEvidence.Role.EXECUTE, List.of(), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrameEvidence(
                17, 3, CodeEvidence.Status.VALID, 0, 2, 2, List.of(participant, participant)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrameEvidence.Participant(1, 7, FrameEvidence.Role.EXECUTE, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrameEvidence.Participant(
                0,
                7,
                FrameEvidence.Role.EXECUTE,
                List.of(new FrameEvidence.Frame(23, 1, 5)),
                List.of()));
  }

  @Test
  void internalObserverPlanRejectsDuplicateAndUnknownSelection() throws IOException {
    Path plan = directory.resolve("observer.properties");
    new ObserverPlan("retained-class").write(plan);
    assertEquals("retained-class", ObserverPlan.read(plan).bindingMetadata());
    for (String text :
        List.of(
            "version=1\nbindingMetadata=jvmti\nbindingMetadata=retained-class\n",
            "version=1\nbindingMetadata=unknown\n",
            "version=1\nbindingMetadata=jvmti\nextra=true\n")) {
      Files.writeString(plan, text);
      assertThrows(IOException.class, () -> ObserverPlan.read(plan));
    }
  }

  private static byte[] golden(String name) throws IOException {
    try (var input = RetainedEvidenceTest.class.getResourceAsStream("/wire/" + name)) {
      if (input == null) throw new IOException("Missing wire fixture " + name);
      return HexFormat.of()
          .parseHex(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip());
    }
  }
}
