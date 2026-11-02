package io.connglli.gorrect.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Actual run-owned JNI slot brackets around one graph walk; handle values are opaque tokens. */
public record NativeRootEvidence(
    long run,
    long checkpoint,
    int site,
    long visit,
    boolean cleanup,
    long generationLimit,
    long startsBefore,
    long finishesBefore,
    long startsAfter,
    long finishesAfter,
    List<Slot> before,
    List<Slot> after) {
  private static final int MAGIC = 0x474e5231, FIXED_BYTES = 89, ROW_BYTES = 32;
  private static final long END = 0x454e444e524f4f31L;

  /** Empty slots retain their last acquisition generation and have zero handle, kind and tag. */
  public record Slot(int slot, long generation, long handle, int kind, long tag) {
    public Slot {
      if (slot < 0
          || slot >= NativeRootPlan.MAX_SLOTS
          || generation < 0
          || generation > NativeRootPlan.MAX_GENERATIONS
          || tag < 0
          || (handle == 0 ? kind != 0 || tag != 0 : kind != 2 || tag == 0 || generation == 0))
        throw new IllegalArgumentException("Invalid native root slot evidence");
    }
  }

  public NativeRootEvidence {
    before = List.copyOf(before);
    after = List.copyOf(after);
    if (run <= 0
        || checkpoint <= 0
        || site < 0
        || visit <= 0
        || generationLimit <= 0
        || generationLimit > NativeRootPlan.MAX_GENERATIONS
        || startsBefore < 0
        || finishesBefore < 0
        || startsAfter < startsBefore
        || finishesAfter < finishesBefore
        || before.isEmpty()
        || before.size() > NativeRootPlan.MAX_SLOTS
        || after.size() != before.size())
      throw new IllegalArgumentException("Invalid native root capture context");
    for (var rows : List.of(before, after)) {
      var generations = new HashSet<Long>();
      for (var slot : rows)
        if (slot.generation() != 0 && !generations.add(slot.generation()))
          throw new IllegalArgumentException("Duplicate native acquisition generation");
    }
    for (int i = 0; i < before.size(); i++)
      if (before.get(i).slot() != i
          || after.get(i).slot() != i
          || before.get(i).generation() > generationLimit
          || after.get(i).generation() > generationLimit)
        throw new IllegalArgumentException("Unordered or excessive native slot evidence");
  }

  public boolean complete() {
    return before.equals(after)
        && (!cleanup || before.stream().allMatch(slot -> slot.handle() == 0));
  }

  public byte[] encode() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(checkpoint);
      out.writeInt(site);
      out.writeLong(visit);
      out.writeBoolean(cleanup);
      out.writeLong(generationLimit);
      out.writeLong(startsBefore);
      out.writeLong(finishesBefore);
      out.writeLong(startsAfter);
      out.writeLong(finishesAfter);
      out.writeInt(before.size());
      for (var rows : List.of(before, after))
        for (var slot : rows) {
          out.writeInt(slot.slot());
          out.writeLong(slot.generation());
          out.writeLong(slot.handle());
          out.writeInt(slot.kind());
          out.writeLong(slot.tag());
        }
      out.writeLong(END);
    }
    return bytes.toByteArray();
  }

  public static NativeRootEvidence read(Path path, long run, long checkpoint) throws IOException {
    if (Files.size(path) > FIXED_BYTES + 2 * ROW_BYTES * NativeRootPlan.MAX_SLOTS)
      throw new IOException("Native root evidence exceeds byte limit");
    return read(Files.readAllBytes(path), run, checkpoint);
  }

  public static NativeRootEvidence read(byte[] bytes, long run, long checkpoint)
      throws IOException {
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC
          || in.readInt() != 1
          || in.readLong() != run
          || in.readLong() != checkpoint)
        throw new IOException("Native root evidence identity or version differs");
      int site = in.readInt();
      long visit = in.readLong();
      int cleanupFlag = in.readUnsignedByte();
      if (cleanupFlag > 1) throw new IOException("Invalid native root cleanup flag");
      boolean cleanup = cleanupFlag == 1;
      long limit = in.readLong(),
          starts = in.readLong(),
          finishes = in.readLong(),
          startsAfter = in.readLong(),
          finishesAfter = in.readLong();
      int count = in.readInt();
      if (count <= 0
          || count > NativeRootPlan.MAX_SLOTS
          || bytes.length != FIXED_BYTES + 2 * ROW_BYTES * count)
        throw new IOException("Invalid native root evidence size");
      var before = rows(in, count);
      var after = rows(in, count);
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Damaged native root evidence footer");
      return new NativeRootEvidence(
          run,
          checkpoint,
          site,
          visit,
          cleanup,
          limit,
          starts,
          finishes,
          startsAfter,
          finishesAfter,
          before,
          after);
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }

  private static List<Slot> rows(DataInputStream in, int count) throws IOException {
    var rows = new ArrayList<Slot>();
    for (int i = 0; i < count; i++)
      rows.add(new Slot(in.readInt(), in.readLong(), in.readLong(), in.readInt(), in.readLong()));
    return rows;
  }
}
