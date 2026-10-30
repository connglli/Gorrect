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
 * Primitive binding presence at one quiescent checkpoint; no object identities or references.
 * Activation identifies a generated method invocation within its worker, independently of loop
 * iterations and checkpoint visits. Presence cannot establish a non-null object's physical root.
 */
public record BindingEvidence(
    long run, long checkpoint, long thread, int worker, long activation, List<Binding> bindings) {
  public static final int MAX_BINDINGS = 65_536;
  private static final int MAGIC = 0x47424e31;
  private static final long END = 0x454e4442494e4431L;

  public record Binding(int declaration, boolean present) {}

  public BindingEvidence {
    bindings = List.copyOf(bindings);
    if (run <= 0
        || checkpoint <= 0
        || thread <= 0
        || worker < 0
        || activation <= 0
        || bindings.size() > MAX_BINDINGS)
      throw new IllegalArgumentException("Invalid binding context");
    var ids = new HashSet<Integer>();
    for (var binding : bindings)
      if (binding.declaration() <= 0 || !ids.add(binding.declaration()))
        throw new IllegalArgumentException("Duplicate or invalid binding declaration");
  }

  /** Checks framing, unique declarations, and capture identity; presence is not root coverage. */
  public static BindingEvidence read(Path path, long run, long checkpoint) throws IOException {
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (in.readInt() != MAGIC || in.readInt() != 1)
        throw new IOException("Unsupported binding format");
      long actualRun = in.readLong(), actualCheckpoint = in.readLong(), thread = in.readLong();
      int worker = in.readInt();
      long activation = in.readLong();
      int count = in.readInt();
      if (actualRun != run || actualCheckpoint != checkpoint || count < 0 || count > MAX_BINDINGS)
        throw new IOException("Invalid binding evidence identity or count");
      var bindings = new ArrayList<Binding>();
      for (int i = 0; i < count; i++) {
        int id = in.readInt(), present = in.readInt();
        if (present < 0 || present > 1) throw new IOException("Invalid binding presence");
        bindings.add(new Binding(id, present == 1));
      }
      if (in.readLong() != END || in.read() != -1) throw new IOException("Damaged binding footer");
      try {
        return new BindingEvidence(run, checkpoint, thread, worker, activation, bindings);
      } catch (IllegalArgumentException exception) {
        throw new IOException(exception.getMessage(), exception);
      }
    }
  }

  /** Overwrites path with version 1 evidence; I/O failure must abort checkpoint completion. */
  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeLong(checkpoint);
      out.writeLong(thread);
      out.writeInt(worker);
      out.writeLong(activation);
      out.writeInt(bindings.size());
      for (var binding : bindings) {
        out.writeInt(binding.declaration());
        out.writeInt(binding.present() ? 1 : 0);
      }
      out.writeLong(END);
    }
  }
}
