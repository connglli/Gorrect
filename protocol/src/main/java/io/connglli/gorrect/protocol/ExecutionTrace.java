package io.connglli.gorrect.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sequential operation evidence. Values contain scalars only and cannot retain workload objects.
 */
public record ExecutionTrace(long run, boolean complete, List<Event> events) {
  public static final int MAX_EVENTS = 1_000_000;
  private static final int MAGIC = 0x47525431;
  private static final long END = 0x454e445452414331L;

  // These ordinals also select the target recorder's primitive kind codes.
  public enum Kind {
    OPERATION,
    ITERATION,
    SCALAR_READ
  }

  public record Event(int operation, Kind kind, long value) {}

  public ExecutionTrace {
    events = List.copyOf(events);
  }

  public static ExecutionTrace read(Path path, long run) throws IOException {
    if (Files.size(path) > 32L * MAX_EVENTS) throw new IOException("Trace exceeds byte limit");
    try (var in = new DataInputStream(Files.newInputStream(path))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || in.readLong() != run)
        throw new IOException("Trace format or run identity mismatch");
      int completion = in.readUnsignedByte();
      if (run <= 0 || completion > 1)
        throw new IOException("Invalid trace completion or run token");
      boolean complete = completion == 1;
      int count = in.readInt();
      if (count < 0 || count > MAX_EVENTS) throw new IOException("Invalid trace count");
      var events = new ArrayList<Event>();
      for (int i = 0; i < count; i++) {
        int operation = in.readInt();
        int kind = in.readUnsignedByte();
        long value = in.readLong();
        if (operation <= 0 || kind >= Kind.values().length)
          throw new IOException("Invalid trace event");
        events.add(new Event(operation, Kind.values()[kind], value));
      }
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Incomplete trace envelope");
      return new ExecutionTrace(run, complete, events);
    }
  }

  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(Files.newOutputStream(path))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeBoolean(complete);
      out.writeInt(events.size());
      for (var event : events) {
        out.writeInt(event.operation());
        out.writeByte(event.kind().ordinal());
        out.writeLong(event.value());
      }
      out.writeLong(END);
    }
  }
}
