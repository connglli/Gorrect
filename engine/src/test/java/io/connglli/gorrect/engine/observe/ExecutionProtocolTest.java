package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionProtocolTest {
  @TempDir Path directory;

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
    Files.write(file, Arrays.copyOf(bytes, bytes.length + 1));
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
    bytes[16] = 2; // Completion is a single canonical 0/1 byte after magic, version, and run.
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> ExecutionTrace.read(file, 42));
  }
}
