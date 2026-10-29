package io.connglli.gorrect.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Small synchronous control frames; snapshots and traces travel as files. */
public record ControlMessage(
    Kind kind, long run, long sequence, int operation, long occurrence, String detail) {
  private static final int MAGIC = 0x47524331;

  // Ordinals are wire values; the writer and reader must use the same ordering.
  public enum Kind {
    HELLO,
    ACK,
    CHECKPOINT,
    COMPLETE,
    FAILED
  }

  public void write(DataOutputStream out) throws IOException {
    out.writeInt(MAGIC);
    out.writeInt(1);
    out.writeInt(kind.ordinal());
    out.writeLong(run);
    out.writeLong(sequence);
    out.writeInt(operation);
    out.writeLong(occurrence);
    out.writeUTF(detail);
    out.flush();
  }

  public static ControlMessage read(DataInputStream in, long expectedRun) throws IOException {
    if (in.readInt() != MAGIC || in.readInt() != 1)
      throw new IOException("Unsupported control frame");
    int kind = in.readInt();
    long run = in.readLong();
    long sequence = in.readLong();
    int operation = in.readInt();
    long occurrence = in.readLong();
    String detail = in.readUTF();
    if (kind < 0
        || kind >= Kind.values().length
        || run <= 0
        || run != expectedRun
        || sequence < 0
        || operation < 0
        || occurrence < 0) throw new IOException("Invalid control frame identity");
    return new ControlMessage(Kind.values()[kind], run, sequence, operation, occurrence, detail);
  }
}
