package io.connglli.gorrect.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded raw OpenJ9 stack witnesses. Frame kinds come from the VM, not installation counters. */
public record StackEvidence(long run, long pid, long vmStart, List<Witness> witnesses) {
  public static final int MAX_WITNESSES = 100_000, MAX_FRAMES = 100_000;
  private static final int MAGIC = 0x47525354;
  private static final long END = 0x454e44535441434bL;

  /** OpenJ9 extended-frame kind: 0 interpreted, 1 compiled, 2 inlined. */
  public record Frame(String owner, String method, String descriptor, int kind) {}

  public record Witness(
      int worker,
      int group,
      long round,
      int phase,
      int point,
      long collection,
      long thread,
      List<Frame> frames) {
    public Witness {
      frames = List.copyOf(frames);
    }
  }

  public StackEvidence {
    witnesses = List.copyOf(witnesses);
  }

  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(pid);
      out.writeLong(vmStart);
      out.writeInt(witnesses.size());
      for (var witness : witnesses) {
        out.writeInt(witness.worker());
        out.writeInt(witness.group());
        out.writeLong(witness.round());
        out.writeInt(witness.phase());
        out.writeInt(witness.point());
        out.writeLong(witness.collection());
        out.writeLong(witness.thread());
        out.writeInt(witness.frames().size());
        for (var frame : witness.frames()) {
          out.writeUTF(frame.owner());
          out.writeUTF(frame.method());
          out.writeUTF(frame.descriptor());
          out.writeInt(frame.kind());
        }
      }
      out.writeLong(END);
    }
  }

  public static StackEvidence read(Path path, long run, long pid, long vmStart) throws IOException {
    if (Files.size(path) > 64L * 1024 * 1024)
      throw new IOException("Stack evidence exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (in.readInt() != MAGIC
          || in.readInt() != 1
          || run <= 0
          || pid <= 0
          || vmStart <= 0
          || in.readLong() != run
          || in.readLong() != pid
          || in.readLong() != vmStart)
        throw new IOException("Stack evidence VM or run identity mismatch");
      var witnesses = new ArrayList<Witness>();
      int frameCount = 0;
      for (int count = count(in, MAX_WITNESSES); count > 0; count--) {
        int worker = in.readInt(), group = in.readInt();
        long round = in.readLong();
        int phase = in.readInt(), point = in.readInt();
        long collection = in.readLong(), thread = in.readLong();
        if (worker < 1
            || worker > 64
            || group <= 0
            || round <= 0
            || phase < 0
            || thread <= 0
            || point < ExecutionEvidence.ENTER
            || point > ExecutionEvidence.AFTER_COLLECTION
            || collection < 0
            || ((point < ExecutionEvidence.BEFORE_COLLECTION) != (collection == 0)))
          throw new IOException("Invalid stack witness context");
        var frames = new ArrayList<Frame>();
        int size = count(in, 64);
        frameCount += size;
        if (frameCount > MAX_FRAMES) throw new IOException("Stack frame count exceeds limit");
        for (; size > 0; size--) {
          var frame = new Frame(in.readUTF(), in.readUTF(), in.readUTF(), in.readInt());
          if (frame.kind() < 0
              || frame.kind() > 2
              || frame.owner().isEmpty()
              || frame.method().isEmpty()
              || frame.descriptor().isEmpty())
            throw new IOException("Invalid OpenJ9 frame metadata");
          frames.add(frame);
        }
        witnesses.add(new Witness(worker, group, round, phase, point, collection, thread, frames));
      }
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Incomplete stack evidence");
      return new StackEvidence(run, pid, vmStart, witnesses);
    }
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > maximum) throw new IOException("Invalid stack evidence count");
    return count;
  }
}
