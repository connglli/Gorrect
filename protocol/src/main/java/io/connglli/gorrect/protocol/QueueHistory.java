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

/** Actual constructor/API observations for explicitly selected main-only named queue programs. */
public record QueueHistory(long run, boolean complete, List<Event> events) {
  private static final int MAGIC = 0x47514831;
  private static final long END = 0x454e445155455531L;
  public static final int MAX_QUEUES = 64;

  public enum Kind {
    CREATE,
    ENQUEUE,
    POLL
  }

  /**
   * One actual operation linked to a one-based scalar position. Queue zero denotes no queue; minus
   * one preserves an unrecognized actual resource so semantic checking can reject it. An absent
   * wrapper is an all-zero identity, never a predicted member. Presence/result and source
   * queue/type agreement are semantic checks, not wire-format assumptions.
   */
  public record Event(
      int worker,
      int sequence,
      int operation,
      Kind kind,
      int queue,
      int result,
      ReferenceHistory.Identity wrapper) {
    public Event {
      if (worker != 0
          || sequence <= 0
          || operation <= 0
          || kind == null
          || queue < -1
          || queue > MAX_QUEUES
          || result < 0
          || result > 1) throw new IllegalArgumentException("Invalid queue event");
    }
  }

  public QueueHistory {
    events = List.copyOf(events);
    if (run <= 0 || events.size() > ExecutionTrace.MAX_EVENTS)
      throw new IllegalArgumentException("Invalid queue history");
    int previous = 0;
    for (var event : events) {
      if (event.sequence() <= previous)
        throw new IllegalArgumentException("Unordered queue events");
      previous = event.sequence();
    }
  }

  /** Writes the complete version 1 envelope, including explicit recording completion. */
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
        out.writeInt(event.operation());
        out.writeInt(event.kind().ordinal());
        out.writeInt(event.queue());
        out.writeInt(event.result());
        var id = event.wrapper();
        out.writeInt(id == null ? 0 : id.worker());
        out.writeInt(id == null ? 0 : id.site());
        out.writeLong(id == null ? 0 : id.occurrence());
        out.writeInt(id == null ? 0 : id.slot());
      }
      out.writeLong(END);
    }
  }

  /** Bounded strict framing; no legacy reference-history or missing-file fallback is permitted. */
  public static QueueHistory read(Path path, long run) throws IOException {
    if (Files.size(path) > 64L * ExecutionTrace.MAX_EVENTS + 64)
      throw new IOException("Queue history exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || run <= 0 || in.readLong() != run)
        throw new IOException("Queue history identity mismatch");
      int flag = in.readUnsignedByte(), count = in.readInt();
      if (flag > 1 || count < 0 || count > ExecutionTrace.MAX_EVENTS)
        throw new IOException("Invalid queue history header");
      var events = new ArrayList<Event>();
      try {
        for (int i = 0; i < count; i++) {
          int worker = in.readInt(), sequence = in.readInt(), operation = in.readInt();
          int kind = in.readInt(), queue = in.readInt(), result = in.readInt();
          int creator = in.readInt(), site = in.readInt();
          long occurrence = in.readLong();
          int slot = in.readInt();
          if (kind < 0 || kind >= Kind.values().length)
            throw new IOException("Invalid queue event kind");
          ReferenceHistory.Identity id = null;
          if (occurrence == 0) {
            if (creator != 0 || site != 0 || slot != 0)
              throw new IOException("Invalid absent queue wrapper");
          } else id = new ReferenceHistory.Identity(creator, site, occurrence, slot);
          events.add(
              new Event(worker, sequence, operation, Kind.values()[kind], queue, result, id));
        }
        if (in.readLong() != END || in.read() != -1)
          throw new IOException("Damaged queue history footer");
        return new QueueHistory(run, flag == 1, events);
      } catch (IllegalArgumentException malformed) {
        throw new IOException(malformed.getMessage(), malformed);
      }
    }
  }
}
