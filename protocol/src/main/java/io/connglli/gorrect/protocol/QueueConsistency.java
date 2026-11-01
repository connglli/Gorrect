package io.connglli.gorrect.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Native custody around one paired walk, without membership predictions. Thread is the actual Java
 * caller ID; threadTag is that Thread object's shared physical tag. Counters bind the paired walk.
 */
public record QueueConsistency(
    long run,
    long checkpoint,
    long thread,
    long threadTag,
    long startsBefore,
    long finishesBefore,
    long startsAfter,
    long finishesAfter,
    List<Lock> locks) {
  public static final int HOTSPOT17_JNI_MONITOR = 1;
  // Fixed framing includes the 76-byte header and eight-byte footer.
  private static final int MAGIC = 0x47514c31, FIXED_BYTES = 84, ROW_BYTES = 60;
  private static final long TAG_LIMIT = 1L << 62;
  private static final long END = 0x454e44514c4f4331L;

  /**
   * Native JNI custody uses actual owners and balanced exits; Java-frame counts are diagnostics.
   */
  public record Lock(
      int resource,
      int rootsClass,
      int rootField,
      int kind,
      long queueTag,
      long lockTag,
      long ownerBefore,
      long ownerAfter,
      int entriesBefore,
      int entriesAfter,
      int cleanup) {
    public Lock {
      if (resource <= 0
          || resource > QueueHistory.MAX_QUEUES
          || rootsClass <= 0
          || rootField < 0
          || kind != HOTSPOT17_JNI_MONITOR
          || queueTag <= 0
          || queueTag >= TAG_LIMIT
          || lockTag <= 0
          || lockTag >= TAG_LIMIT
          || ownerBefore < 0
          || ownerBefore >= TAG_LIMIT
          || ownerAfter < 0
          || ownerAfter >= TAG_LIMIT
          || entriesBefore < 0
          || entriesAfter < 0) throw new IllegalArgumentException("Invalid queue consistency row");
    }

    public boolean owned(long callerTag) {
      return callerTag > 0
          && ownerBefore == callerTag
          && ownerAfter == callerTag
          && entriesBefore >= 0
          && entriesAfter >= 0
          && cleanup == 0;
    }
  }

  public QueueConsistency {
    locks = List.copyOf(locks);
    if (run <= 0
        || checkpoint <= 0
        || thread <= 0
        || threadTag <= 0
        || threadTag >= TAG_LIMIT
        || startsBefore < 0
        || finishesBefore < 0
        || startsAfter < startsBefore
        || finishesAfter < finishesBefore
        || locks.isEmpty()
        || locks.size() > QueueHistory.MAX_QUEUES)
      throw new IllegalArgumentException("Invalid queue consistency context");
    for (int i = 0; i < locks.size(); i++)
      if (locks.get(i).resource() != i + 1)
        throw new IllegalArgumentException("Unordered or duplicate queue consistency row");
  }

  /**
   * Basic custody outcomes only; physical identity, loaded behavior and walk coverage need checks.
   */
  public boolean complete() {
    return locks.stream().allMatch(lock -> lock.owned(threadTag));
  }

  public byte[] encode() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(checkpoint);
      out.writeLong(thread);
      out.writeLong(threadTag);
      out.writeLong(startsBefore);
      out.writeLong(finishesBefore);
      out.writeLong(startsAfter);
      out.writeLong(finishesAfter);
      out.writeInt(locks.size());
      for (var lock : locks) {
        out.writeInt(lock.resource());
        out.writeInt(lock.rootsClass());
        out.writeInt(lock.rootField());
        out.writeInt(lock.kind());
        out.writeLong(lock.queueTag());
        out.writeLong(lock.lockTag());
        out.writeLong(lock.ownerBefore());
        out.writeLong(lock.ownerAfter());
        out.writeInt(lock.entriesBefore());
        out.writeInt(lock.entriesAfter());
        out.writeInt(lock.cleanup());
      }
      out.writeLong(END);
    }
    return bytes.toByteArray();
  }

  public static QueueConsistency read(Path path, long run, long checkpoint) throws IOException {
    if (Files.size(path) > FIXED_BYTES + ROW_BYTES * QueueHistory.MAX_QUEUES)
      throw new IOException("Queue consistency exceeds byte limit");
    return read(Files.readAllBytes(path), run, checkpoint);
  }

  public static QueueConsistency read(byte[] bytes, long run, long checkpoint) throws IOException {
    if (bytes.length > FIXED_BYTES + ROW_BYTES * QueueHistory.MAX_QUEUES)
      throw new IOException("Queue consistency exceeds byte limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC
          || in.readInt() != 1
          || in.readLong() != run
          || in.readLong() != checkpoint)
        throw new IOException("Queue consistency identity or version differs");
      long thread = in.readLong(), tag = in.readLong();
      long starts = in.readLong(),
          finishes = in.readLong(),
          startsAfter = in.readLong(),
          finishesAfter = in.readLong();
      int count = in.readInt();
      if (count <= 0
          || count > QueueHistory.MAX_QUEUES
          || bytes.length != FIXED_BYTES + ROW_BYTES * count)
        throw new IOException("Invalid queue consistency size");
      var locks = new ArrayList<Lock>();
      for (int i = 0; i < count; i++)
        locks.add(
            new Lock(
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readInt(),
                in.readInt(),
                in.readInt()));
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Damaged queue consistency footer");
      return new QueueConsistency(
          run, checkpoint, thread, tag, starts, finishes, startsAfter, finishesAfter, locks);
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }
}
