package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionProtocolTest {
  @TempDir Path directory;

  @Test
  void initialFormatPreservesMainAndWorkerEvents() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(0x47525431);
      out.writeInt(1);
      out.writeLong(42);
      out.writeBoolean(true);
      out.writeInt(2);
      out.writeInt(7);
      out.writeByte(2);
      out.writeLong(Long.MIN_VALUE);
      out.writeInt(0);
      out.writeInt(0);
      out.writeLong(0);
      out.writeInt(0);
      out.writeInt(9);
      out.writeByte(4);
      out.writeLong(0);
      out.writeInt(1);
      out.writeInt(8);
      out.writeLong(3);
      out.writeInt(0);
      out.writeLong(0x454e445452414331L);
    }
    Path file = directory.resolve("initial");
    Files.write(file, bytes.toByteArray());
    var events =
        List.of(
            new ExecutionTrace.Event(7, ExecutionTrace.Kind.SCALAR_READ, Long.MIN_VALUE),
            new ExecutionTrace.Event(9, ExecutionTrace.Kind.BARRIER, 0, 1, 8, 3, 0));
    assertEquals(events, ExecutionTrace.read(file, 42).events());
    new ExecutionTrace(42, true, events).write(file);
    assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(file));
  }

  @Test
  void rejectsTruncatedWorkerEvidenceAndPreservesAllFields() throws IOException {
    Path file = directory.resolve("workers");
    var group =
        new ExecutionEvidence.Group(
            2,
            3,
            List.of(1),
            true,
            4,
            5,
            List.of(new ExecutionEvidence.Compilation(1, "worker1", 4, 4, "")));
    var window = new ExecutionEvidence.Window(1, 2, 3, 0, 11, 12, 13, 4, 5, 1);
    var evidence = new ExecutionEvidence(42, true, List.of(group), List.of(window));
    evidence.write(file);
    assertEquals(evidence, ExecutionEvidence.read(file, 42));
    byte[] bytes = Files.readAllBytes(file);
    for (int length = 0; length < bytes.length; length++) {
      Files.write(file, Arrays.copyOf(bytes, length));
      assertThrows(IOException.class, () -> ExecutionEvidence.read(file, 42));
    }
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> ExecutionEvidence.read(file, 99));
    Files.write(file, Arrays.copyOf(bytes, bytes.length + 1));
    assertThrows(IOException.class, () -> ExecutionEvidence.read(file, 42));
  }

  @Test
  void rejectsEveryTruncatedControlFrameAndWrongRun() throws IOException {
    var bytes = new ByteArrayOutputStream();
    new ControlMessage(ControlMessage.Kind.CHECKPOINT, 42, 2, 7, 3, "")
        .write(new DataOutputStream(bytes));
    byte[] frame = bytes.toByteArray();
    for (int length = 0; length < frame.length; length++) {
      byte[] prefix = Arrays.copyOf(frame, length);
      assertThrows(
          IOException.class,
          () -> ControlMessage.read(new DataInputStream(new ByteArrayInputStream(prefix)), 42));
    }
    assertThrows(
        IOException.class,
        () -> ControlMessage.read(new DataInputStream(new ByteArrayInputStream(frame)), 99));
    assertEquals(
        7,
        ControlMessage.read(new DataInputStream(new ByteArrayInputStream(frame)), 42).operation());
  }

  @Test
  void distinguishesPartialTraceFromDamagedEnvelope() throws IOException {
    Path file = directory.resolve("trace");
    var event = new ExecutionTrace.Event(7, ExecutionTrace.Kind.SCALAR_READ, Long.MIN_VALUE);
    new ExecutionTrace(42, false, List.of(event)).write(file);
    var trace = ExecutionTrace.read(file, 42);
    assertFalse(trace.complete());
    assertEquals(List.of(event), trace.events());
    byte[] bytes = Files.readAllBytes(file);
    for (int length = 0; length < bytes.length; length++) {
      Files.write(file, Arrays.copyOf(bytes, length));
      assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
    }
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 99));
    byte[] unsupported = bytes.clone();
    ByteBuffer.wrap(unsupported).putInt(4, 2);
    Files.write(file, unsupported);
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
    Files.write(file, Arrays.copyOf(bytes, bytes.length + 1));
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
    bytes[16] = 2; // Completion is a single canonical 0/1 byte after magic, version, and run.
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
  }
}
