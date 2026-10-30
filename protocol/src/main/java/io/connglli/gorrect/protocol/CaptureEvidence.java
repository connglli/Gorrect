package io.connglli.gorrect.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Primitive participants at one coordinated worker cut. Every mutator is paused until capture and
 * acknowledgement finish. Presence does not establish an object's physical root.
 */
public record CaptureEvidence(
    long run,
    long checkpoint,
    int group,
    long round,
    int cut,
    int phase,
    List<Participant> participants) {
  public static final int MAX_PARTICIPANTS = 17;
  private static final int MAGIC = 0x47435531;
  private static final long END = 0x454e444355543031L;
  private static final long MAX_BYTES =
      128L + MAX_PARTICIPANTS * 48L + BindingEvidence.MAX_BINDINGS * 8L;

  /**
   * Caller first, followed by workers in declaration order. Caller operation/occurrence identify
   * the group visit; workers identify their checkpoint site and global per-site visit. Activation
   * is one for the caller and the group visit for each worker. Empty binding lists remain
   * mandatory.
   */
  public record Participant(
      int worker,
      int operation,
      long occurrence,
      long thread,
      long activation,
      List<BindingEvidence.Binding> bindings) {
    public Participant {
      bindings = List.copyOf(bindings);
      if (worker < 0
          || worker > 64
          || operation <= 0
          || occurrence <= 0
          || thread <= 0
          || activation <= 0) throw new IllegalArgumentException("Invalid capture participant");
    }
  }

  public CaptureEvidence {
    participants = List.copyOf(participants);
    if (run <= 0
        || checkpoint <= 0
        || group <= 0
        || round <= 0
        || cut <= 0
        || phase < 0
        || participants.size() < 2
        || participants.size() > MAX_PARTICIPANTS)
      throw new IllegalArgumentException("Invalid coordinated capture context");
    var workers = new HashSet<Integer>();
    var threads = new HashSet<Long>();
    var declarations = new HashSet<Integer>();
    for (int index = 0; index < participants.size(); index++) {
      var participant = participants.get(index);
      if (!workers.add(participant.worker())
          || !threads.add(participant.thread())
          || (participant.worker() == 0) != (index == 0)
          || participant.activation() != (index == 0 ? 1 : round)
          || index == 0 && (participant.operation() != group || participant.occurrence() != round))
        throw new IllegalArgumentException("Duplicate or inconsistent capture participant");
      for (var binding : participant.bindings())
        if (binding.declaration() <= 0
            || !declarations.add(binding.declaration())
            || declarations.size() > BindingEvidence.MAX_BINDINGS)
          throw new IllegalArgumentException("Duplicate, invalid, or excessive capture binding");
    }
  }

  /** Writes version 1 evidence, replacing any existing file. Failure must abort the cut. */
  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(checkpoint);
      out.writeInt(group);
      out.writeLong(round);
      out.writeInt(cut);
      out.writeInt(phase);
      out.writeInt(participants.size());
      for (var participant : participants) {
        out.writeInt(participant.worker());
        out.writeInt(participant.operation());
        out.writeLong(participant.occurrence());
        out.writeLong(participant.thread());
        out.writeLong(participant.activation());
        out.writeInt(participant.bindings().size());
        for (var binding : participant.bindings()) {
          out.writeInt(binding.declaration());
          out.writeInt(binding.present() ? 1 : 0);
        }
      }
      out.writeLong(END);
    }
  }

  /** Checks bounded framing, participant uniqueness, and the expected run/capture identity. */
  public static CaptureEvidence read(Path path, long run, long checkpoint) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("Capture evidence exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (in.readInt() != MAGIC
          || in.readInt() != 1
          || run <= 0
          || checkpoint <= 0
          || in.readLong() != run
          || in.readLong() != checkpoint)
        throw new IOException("Capture evidence identity mismatch");
      int group = in.readInt();
      long round = in.readLong();
      int cut = in.readInt(), phase = in.readInt();
      int count = count(in, MAX_PARTICIPANTS);
      int remaining = BindingEvidence.MAX_BINDINGS;
      var participants = new ArrayList<Participant>();
      try {
        for (int index = 0; index < count; index++) {
          int worker = in.readInt(), operation = in.readInt();
          long occurrence = in.readLong(), thread = in.readLong(), activation = in.readLong();
          int size = count(in, remaining);
          remaining -= size;
          var bindings = new ArrayList<BindingEvidence.Binding>();
          for (int binding = 0; binding < size; binding++) {
            int declaration = in.readInt(), present = in.readInt();
            if (present < 0 || present > 1)
              throw new IOException("Invalid capture binding presence");
            bindings.add(new BindingEvidence.Binding(declaration, present == 1));
          }
          participants.add(
              new Participant(worker, operation, occurrence, thread, activation, bindings));
        }
        if (in.readLong() != END || in.read() != -1)
          throw new IOException("Damaged capture footer");
        return new CaptureEvidence(run, checkpoint, group, round, cut, phase, participants);
      } catch (IllegalArgumentException exception) {
        throw new IOException(exception.getMessage(), exception);
      }
    }
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > maximum) throw new IOException("Invalid capture evidence count");
    return count;
  }
}
