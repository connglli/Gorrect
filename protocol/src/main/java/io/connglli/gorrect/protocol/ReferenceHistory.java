package io.connglli.gorrect.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Sparse reference observations keyed to one-based positions in the main scalar trace. */
public record ReferenceHistory(long run, boolean complete, List<Event> events) {
  private static final int MAGIC = 0x47524631;
  private static final long END = 0x454e445245465331L;

  public enum Kind {
    INSTALL,
    ACQUIRE,
    STATE,
    CLEAR,
    QUEUE
  }

  public record Identity(int worker, int site, long occurrence, int slot) {
    public Identity {
      if (worker < 0
          || site < 0
          || occurrence <= 0
          || (site == 0 ? worker != 0 || slot < 0 : slot != -1))
        throw new IllegalArgumentException("Invalid reference incarnation");
    }
  }

  public record Target(int worker, int site, long occurrence) {
    public Target {
      if (worker < 0 || site < 0 || occurrence <= 0 || (site == 0 && worker != 0))
        throw new IllegalArgumentException("Invalid referent identity");
    }
  }

  /** Strength is 1 for soft, 2 for weak, and 0 only for an empty queue observation. */
  public record Event(int sequence, Kind kind, int strength, Identity wrapper, Target target) {}

  public ReferenceHistory {
    events = List.copyOf(events);
  }

  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(Files.newOutputStream(path))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeBoolean(complete);
      out.writeInt(events.size());
      for (var event : events) {
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

  public static ReferenceHistory read(Path path, long run) throws IOException {
    if (Files.size(path) > 64L * ExecutionTrace.MAX_EVENTS)
      throw new IOException("Reference history exceeds byte limit");
    try (var in = new DataInputStream(Files.newInputStream(path))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || run <= 0 || in.readLong() != run)
        throw new IOException("Reference history identity mismatch");
      int flag = in.readUnsignedByte(), count = in.readInt();
      if (flag > 1 || count < 0 || count > ExecutionTrace.MAX_EVENTS)
        throw new IOException("Invalid reference history header");
      var events = new ArrayList<Event>();
      int previous = 0;
      for (int index = 0; index < count; index++) {
        int sequence = in.readInt(), kind = in.readUnsignedByte(), strength = in.readUnsignedByte();
        int worker = in.readInt(), site = in.readInt();
        long occurrence = in.readLong();
        int slot = in.readInt();
        int targetWorker = in.readInt(), targetSite = in.readInt();
        long targetOccurrence = in.readLong();
        if (sequence <= previous
            || sequence > ExecutionTrace.MAX_EVENTS
            || kind >= Kind.values().length
            || strength > 2) throw new IOException("Invalid reference history entry");
        Identity wrapper = strength == 0 ? null : new Identity(worker, site, occurrence, slot);
        Target target =
            targetOccurrence == 0 ? null : new Target(targetWorker, targetSite, targetOccurrence);
        if ((wrapper == null
                && (worker != 0
                    || site != 0
                    || occurrence != 0
                    || slot != 0
                    || kind != Kind.QUEUE.ordinal()))
            || (target == null && (targetWorker != 0 || targetSite != 0))
            || (target != null && kind != Kind.INSTALL.ordinal() && kind != Kind.ACQUIRE.ordinal()))
          throw new IOException("Inconsistent reference history entry");
        events.add(new Event(sequence, Kind.values()[kind], strength, wrapper, target));
        previous = sequence;
      }
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Incomplete reference history envelope");
      return new ReferenceHistory(run, flag == 1, events);
    } catch (IllegalArgumentException failure) {
      throw new IOException("Invalid reference identity", failure);
    }
  }
}
