package io.connglli.gorrect.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Raw stack samples bracketing one heap walk. They supply coordinates, never binding values. */
public record FrameEvidence(
    long run,
    long checkpoint,
    CodeEvidence.Status status,
    int jvmtiError,
    long beforeEpoch,
    long afterEpoch,
    List<Participant> participants) {
  public static final int MAX_PARTICIPANTS = 17, MAX_FRAMES = 64;
  private static final int MAGIC = 0x47465231;
  private static final long END = 0x454e444652414d31L;
  private static final long MAX_BYTES = 128L + MAX_PARTICIPANTS * (32L + 2L * MAX_FRAMES * 20L);

  /** Explicit role; the host derives the expected role from validated control/cut evidence. */
  public enum Role {
    EXECUTE,
    WORKER,
    MAIN
  }

  /** Depth is the VM stack depth and BCI may be -1 when unavailable. No value is inferred. */
  public record Frame(long method, int depth, long bci) {
    public Frame {
      if (method == 0 || depth < 0 || bci < -1)
        throw new IllegalArgumentException("Invalid frame sample");
    }
  }

  /** Caller first, then declared worker order, including participants without GIR bindings. */
  public record Participant(
      int worker, long thread, Role role, List<Frame> before, List<Frame> after) {
    public Participant {
      before = List.copyOf(before);
      after = List.copyOf(after);
      if (worker < 0
          || worker > 64
          || thread <= 0
          || role == null
          || (role == Role.WORKER) != (worker != 0)
          || before.size() > MAX_FRAMES
          || after.size() > MAX_FRAMES)
        throw new IllegalArgumentException("Invalid frame participant");
      checkDepths(before);
      checkDepths(after);
    }
  }

  public FrameEvidence {
    participants = List.copyOf(participants);
    if (run <= 0
        || checkpoint <= 0
        || status == null
        || jvmtiError < 0
        || beforeEpoch < 0
        || afterEpoch < 0
        || participants.size() > MAX_PARTICIPANTS
        || status == CodeEvidence.Status.VALID
            && (jvmtiError != 0 || beforeEpoch == 0 || afterEpoch == 0))
      throw new IllegalArgumentException("Invalid frame certificate");
    var workers = new HashSet<Integer>();
    var threads = new HashSet<Long>();
    for (int index = 0; index < participants.size(); index++) {
      var participant = participants.get(index);
      if (!workers.add(participant.worker())
          || !threads.add(participant.thread())
          || (participant.worker() == 0) != (index == 0))
        throw new IllegalArgumentException("Duplicate or unordered frame participant");
    }
  }

  /** Writes version 1 samples; successful persistence is required before CHECKPOINT publication. */
  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(checkpoint);
      out.writeInt(status.ordinal());
      out.writeInt(jvmtiError);
      out.writeLong(beforeEpoch);
      out.writeLong(afterEpoch);
      out.writeInt(participants.size());
      for (var participant : participants) {
        out.writeInt(participant.worker());
        out.writeLong(participant.thread());
        out.writeInt(participant.role().ordinal());
        writeFrames(out, participant.before());
        writeFrames(out, participant.after());
      }
      out.writeLong(END);
    }
  }

  /**
   * Checks bounded framing and identity; role, epoch and physical-root matching are host checks.
   */
  public static FrameEvidence read(Path path, long run, long checkpoint) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("Frame evidence exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      return read(in, run, checkpoint);
    }
  }

  /** Reads native JNI output without accepting trailing bytes or unbounded counts. */
  public static FrameEvidence read(byte[] bytes, long run, long checkpoint) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Frame evidence exceeds byte limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return read(in, run, checkpoint);
    }
  }

  private static FrameEvidence read(DataInputStream in, long run, long checkpoint)
      throws IOException {
    if (in.readInt() != MAGIC
        || in.readInt() != 1
        || run <= 0
        || checkpoint <= 0
        || in.readLong() != run
        || in.readLong() != checkpoint) throw new IOException("Frame evidence identity mismatch");
    int status = count(in, CodeEvidence.Status.values().length - 1), error = in.readInt();
    long before = in.readLong(), after = in.readLong();
    var participants = new ArrayList<Participant>();
    try {
      for (int remaining = count(in, MAX_PARTICIPANTS); remaining > 0; remaining--) {
        int worker = in.readInt();
        long thread = in.readLong();
        int role = count(in, Role.values().length - 1);
        participants.add(
            new Participant(worker, thread, Role.values()[role], readFrames(in), readFrames(in)));
      }
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Damaged frame evidence footer");
      return new FrameEvidence(
          run,
          checkpoint,
          CodeEvidence.Status.values()[status],
          error,
          before,
          after,
          participants);
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }

  private static void writeFrames(DataOutputStream out, List<Frame> frames) throws IOException {
    out.writeInt(frames.size());
    for (var frame : frames) {
      out.writeLong(frame.method());
      out.writeInt(frame.depth());
      out.writeLong(frame.bci());
    }
  }

  private static List<Frame> readFrames(DataInputStream in) throws IOException {
    var frames = new ArrayList<Frame>();
    for (int remaining = count(in, MAX_FRAMES); remaining > 0; remaining--)
      frames.add(new Frame(in.readLong(), in.readInt(), in.readLong()));
    return frames;
  }

  private static void checkDepths(List<Frame> frames) {
    for (int index = 0; index < frames.size(); index++)
      if (frames.get(index).depth() != index)
        throw new IllegalArgumentException("Noncontiguous frame depths");
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > maximum) throw new IOException("Invalid frame evidence count");
    return count;
  }
}
