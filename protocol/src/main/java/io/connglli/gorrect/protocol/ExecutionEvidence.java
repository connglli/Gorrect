package io.connglli.gorrect.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Coarse worker windows, completed joins, and VM control results; none establishes graph values.
 */
public record ExecutionEvidence(
    long run, boolean complete, List<Group> groups, List<Window> windows) {
  public static final int FRAME_VERSION = 1;
  public static final int ENTER = 0, EXIT = 1, BEFORE_COLLECTION = 2, AFTER_COLLECTION = 3;
  private static final int MAGIC = 0x47524531;
  private static final long END = 0x454e444556494431L;

  public record Compilation(
      int worker, String method, int requested, int installed, String problem) {}

  /** Decoded VM stack evidence, distinct from target-provided installation or witness labels. */
  public record Frame(
      int worker,
      int group,
      long round,
      int phase,
      int point,
      long collection,
      long thread,
      boolean compiled) {}

  public record Group(
      int operation,
      long round,
      List<Integer> workers,
      boolean joined,
      long gcBefore,
      long gcAfter,
      List<Compilation> compilations) {
    public Group {
      workers = List.copyOf(workers);
      compilations = List.copyOf(compilations);
    }
  }

  /**
   * Times share nanoTime's target clock; thread is Thread.getId, not a JVMTI tag. GC fields retain
   * the phase's last request with pause progress; -1 counters and ordinal zero mean none retained.
   */
  public record Window(
      int worker,
      int group,
      long round,
      int phase,
      long thread,
      long begin,
      long end,
      long gcBefore,
      long gcAfter,
      long collection) {}

  public static String workerMethod(int worker) {
    return "worker" + worker;
  }

  public ExecutionEvidence {
    groups = List.copyOf(groups);
    windows = List.copyOf(windows);
  }

  public void write(Path file) throws IOException {
    try (var out = new DataOutputStream(Files.newOutputStream(file))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeBoolean(complete);
      out.writeInt(groups.size());
      for (var group : groups) {
        out.writeInt(group.operation());
        out.writeLong(group.round());
        out.writeBoolean(group.joined());
        out.writeLong(group.gcBefore());
        out.writeLong(group.gcAfter());
        out.writeInt(group.workers().size());
        for (int worker : group.workers()) out.writeInt(worker);
        out.writeInt(group.compilations().size());
        for (var compilation : group.compilations()) {
          out.writeInt(compilation.worker());
          out.writeUTF(compilation.method());
          out.writeInt(compilation.requested());
          out.writeInt(compilation.installed());
          out.writeUTF(compilation.problem());
        }
      }
      out.writeInt(windows.size());
      for (var window : windows) {
        out.writeInt(window.worker());
        out.writeInt(window.group());
        out.writeLong(window.round());
        out.writeInt(window.phase());
        out.writeLong(window.thread());
        out.writeLong(window.begin());
        out.writeLong(window.end());
        out.writeLong(window.gcBefore());
        out.writeLong(window.gcAfter());
        out.writeLong(window.collection());
      }
      out.writeLong(END);
    }
  }

  public static ExecutionEvidence read(Path file, long run) throws IOException {
    if (Files.size(file) > 64L * ExecutionTrace.MAX_EVENTS)
      throw new IOException("Worker evidence exceeds byte limit");
    try (var in = new DataInputStream(Files.newInputStream(file))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || run <= 0 || in.readLong() != run)
        throw new IOException("Worker evidence identity mismatch");
      boolean complete = flag(in);
      var groups = new ArrayList<Group>();
      for (int count = count(in, ExecutionTrace.MAX_EVENTS); count > 0; count--) {
        int operation = in.readInt();
        long round = in.readLong();
        boolean joined = flag(in);
        long before = in.readLong(), after = in.readLong();
        var workers = new ArrayList<Integer>();
        for (int size = count(in, 16); size > 0; size--) workers.add(in.readInt());
        var compilations = new ArrayList<Compilation>();
        for (int size = count(in, 16); size > 0; size--)
          compilations.add(
              new Compilation(
                  in.readInt(), in.readUTF(), in.readInt(), in.readInt(), in.readUTF()));
        if (operation <= 0
            || round <= 0
            || workers.isEmpty()
            || workers.stream().anyMatch(id -> id < 1 || id > 64)
            || workers.stream().distinct().count() != workers.size())
          throw new IOException("Invalid worker group");
        groups.add(new Group(operation, round, workers, joined, before, after, compilations));
      }
      var windows = new ArrayList<Window>();
      for (int count = count(in, ExecutionTrace.MAX_EVENTS); count > 0; count--) {
        var window =
            new Window(
                in.readInt(),
                in.readInt(),
                in.readLong(),
                in.readInt(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong());
        if (window.worker() < 1
            || window.worker() > 64
            || window.group() <= 0
            || window.round() <= 0
            || window.phase() < 0
            || window.thread() <= 0
            || window.end() < window.begin()) throw new IOException("Invalid worker window");
        windows.add(window);
      }
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Incomplete worker evidence envelope");
      return new ExecutionEvidence(run, complete, groups, windows);
    }
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int value = in.readInt();
    if (value < 0 || value > maximum) throw new IOException("Invalid worker evidence count");
    return value;
  }

  private static boolean flag(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if (value > 1) throw new IOException("Invalid worker evidence flag");
    return value == 1;
  }
}
