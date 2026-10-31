package io.connglli.gorrect.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Explicit version 1 same-walk resource policy; snapshot records retain their physical meanings.
 */
public record QueueCapture(HeapSnapshot snapshot) {
  private static final int MAGIC = 0x47514331;
  private static final long END = 0x454e445143415031L;

  public QueueCapture {
    if (snapshot == null) throw new IllegalArgumentException("Missing queue resource capture");
  }

  public byte[] encode() throws IOException {
    byte[] body = SnapshotCodec.write(snapshot);
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeInt(QueuePlan.POLICY);
      out.writeInt(body.length);
      out.write(body);
      out.writeLong(END);
    }
    return bytes.toByteArray();
  }

  public void write(Path path) throws IOException {
    Files.write(path, encode());
  }

  public static QueueCapture read(Path path, long run, long checkpoint) throws IOException {
    if (Files.size(path) > SnapshotCodec.MAX_BYTES + 24L)
      throw new IOException("Queue capture exceeds byte limit");
    return read(Files.readAllBytes(path), run, checkpoint);
  }

  /** A wrong policy, identity, quota or EOF is never interpreted as an empty queue. */
  public static QueueCapture read(byte[] bytes, long run, long checkpoint) throws IOException {
    if (bytes.length > SnapshotCodec.MAX_BYTES + 24L)
      throw new IOException("Queue capture exceeds byte limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || in.readInt() != QueuePlan.POLICY)
        throw new IOException("Unknown queue capture policy");
      int count = in.readInt();
      if (count < 0 || count > SnapshotCodec.MAX_BYTES || count != bytes.length - 24)
        throw new IOException("Invalid queue capture size");
      byte[] body = new byte[count];
      in.readFully(body);
      if (in.readLong() != END || in.read() != -1) throw new IOException("Damaged queue capture");
      return new QueueCapture(SnapshotCodec.read(body, run, checkpoint));
    }
  }
}
