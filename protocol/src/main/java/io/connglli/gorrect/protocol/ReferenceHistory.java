package io.connglli.gorrect.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Sparse reference observations keyed to worker and one-based worker-local scalar-trace position.
 */
public record ReferenceHistory(long run, boolean complete, List<Event> events) {
  private static final int MAGIC = 0x47524631;
  private static final long END = 0x454e445245465331L;

  public enum Kind {
    INSTALL,
    ACQUIRE,
    STATE,
    CLEAR,
    QUEUE,
    STRONG_READ
  }

  /**
   * Wrapper incarnation, expressed using its creator worker. Initial wrappers use worker/site zero,
   * the declared owner object ID as occurrence, and a nonnegative reference slot. Installed
   * wrappers use their write instruction as site, its one-based occurrence, and slot -1.
   */
  public record Identity(int worker, int site, long occurrence, int slot) {
    public Identity {
      if (worker < 0
          || site < 0
          || occurrence <= 0
          || (site == 0 ? worker != 0 || slot < 0 : slot != -1))
        throw new IllegalArgumentException("Invalid reference incarnation");
    }
  }

  /**
   * Logical referent identity. Initial objects use worker/site zero and their declared object ID as
   * occurrence; allocations use the creator worker, allocation instruction, and its occurrence.
   */
  public record Target(int worker, int site, long occurrence) {
    public Target {
      if (worker < 0 || site < 0 || occurrence <= 0 || (site == 0 && worker != 0))
        throw new IllegalArgumentException("Invalid referent identity");
    }
  }

  /**
   * Identity observation linked to a one-based scalar-trace position in the observing worker.
   * Strength is one for soft, two for weak, and zero for a strong read or empty queue poll. Wrapper
   * is absent for those strength-zero cases. Target is nullable; only install, acquire, and
   * strong-read kinds may carry a non-null target.
   */
  public record Event(
      int worker, int sequence, Kind kind, int strength, Identity wrapper, Target target) {
    public Event(int sequence, Kind kind, int strength, Identity wrapper, Target target) {
      this(0, sequence, kind, strength, wrapper, target);
    }
  }

  public ReferenceHistory {
    events = List.copyOf(events);
  }

  /** Writes the version 1 envelope in record order, replacing any existing file. */
  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeBoolean(complete);
      out.writeInt(events.size());

      for (var event : events) {
        out.writeInt(event.worker());
        out.writeInt(event.sequence());
        out.writeByte(event.kind().ordinal());
        out.writeByte(event.strength());
        var wrapper = event.wrapper();
        out.writeInt(wrapper == null ? 0 : wrapper.worker());
        out.writeInt(wrapper == null ? 0 : wrapper.site());
        out.writeLong(wrapper == null ? 0 : wrapper.occurrence());
        out.writeInt(wrapper == null ? 0 : wrapper.slot());
        var target = event.target();
        out.writeInt(target == null ? 0 : target.worker());
        out.writeInt(target == null ? 0 : target.site());
        out.writeLong(target == null ? 0 : target.occurrence());
      }

      out.writeLong(END);
    }
  }

  /**
   * Decodes a bounded complete envelope for the expected run; invalid evidence raises IOException.
   */
  public static ReferenceHistory read(Path path, long run) throws IOException {
    if (Files.size(path) > 64L * ExecutionTrace.MAX_EVENTS)
      throw new IOException("Reference history exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || run <= 0 || in.readLong() != run)
        throw new IOException("Reference history identity mismatch");
      int flag = in.readUnsignedByte(), count = in.readInt();
      if (flag > 1 || count < 0 || count > ExecutionTrace.MAX_EVENTS)
        throw new IOException("Invalid reference history header");

      var events = new ArrayList<Event>();
      var previous = new HashMap<Integer, Integer>();
      var types = Kind.values();

      for (int index = 0; index < count; index++) {
        int recordingWorker = in.readInt(),
            sequence = in.readInt(),
            kind = in.readUnsignedByte(),
            strength = in.readUnsignedByte();
        int worker = in.readInt(), site = in.readInt();
        long occurrence = in.readLong();
        int slot = in.readInt();
        int targetWorker = in.readInt(), targetSite = in.readInt();
        long targetOccurrence = in.readLong();
        if (recordingWorker < 0
            || recordingWorker > ExecutionTrace.MAX_WORKERS
            || sequence <= previous.getOrDefault(recordingWorker, 0)
            || sequence > ExecutionTrace.MAX_EVENTS
            || kind >= types.length
            || strength > 2) throw new IOException("Invalid reference history entry");
        Identity wrapper = strength == 0 ? null : new Identity(worker, site, occurrence, slot);
        Target target =
            targetOccurrence == 0 ? null : new Target(targetWorker, targetSite, targetOccurrence);
        if ((wrapper == null
                && (worker != 0
                    || site != 0
                    || occurrence != 0
                    || slot != 0
                    || (kind != Kind.QUEUE.ordinal() && kind != Kind.STRONG_READ.ordinal())))
            || (target == null && (targetWorker != 0 || targetSite != 0))
            || (target != null
                && kind != Kind.INSTALL.ordinal()
                && kind != Kind.ACQUIRE.ordinal()
                && kind != Kind.STRONG_READ.ordinal())
            || (kind == Kind.STRONG_READ.ordinal() && strength != 0))
          throw new IOException("Inconsistent reference history entry");
        events.add(new Event(recordingWorker, sequence, types[kind], strength, wrapper, target));
        previous.put(recordingWorker, sequence);
      }

      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Incomplete reference history envelope");

      return new ReferenceHistory(run, flag == 1, events);
    } catch (IllegalArgumentException failure) {
      throw new IOException("Invalid reference identity", failure);
    }
  }
}
