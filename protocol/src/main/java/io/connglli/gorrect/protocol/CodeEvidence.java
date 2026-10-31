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

/** Loaded Case certificate. VALID describes provenance, never physical roots or execution. */
public record CodeEvidence(
    long run,
    Stage stage,
    Status status,
    int jvmtiError,
    long epoch,
    long classToken,
    long loaderToken,
    int flags,
    byte[] code,
    List<Method> methods) {
  /** Shared native/JVMTI allocation failure discriminator; explicit capacity limits use LIMIT/0. */
  public static final int OUT_OF_MEMORY = 110;

  public static final int MAX_CODE_BYTES = 16 * 1024 * 1024;
  public static final int MAX_METHODS = 256;
  public static final int PREPARED = 1,
      INTENDED_BOUND = 2,
      SYSTEM_LOADER = 4,
      JVMBCI = 8,
      BOOTSTRAP_LOADER = 16;
  private static final int MAGIC = 0x47434431;
  private static final long END = 0x454e44434f444531L;
  private static final long MAX_BYTES = MAX_CODE_BYTES + 2L * MAX_METHODS * 65_535 + 16_384;

  /** Startup carries bytes/method mappings; final carries the terminal epoch/status only. */
  public enum Stage {
    STARTUP,
    FINAL
  }

  /** Wire ordinals shared by code and frame certificates; a failed status cannot certify a pass. */
  public enum Status {
    VALID,
    UNAVAILABLE,
    INVALIDATED,
    LIMIT,
    JVMTI_ERROR
  }

  /** Opaque process-local method identity, using the same token as raw stack roots. */
  public record Method(long token, String name, String descriptor) {
    public Method {
      if (token == 0
          || name == null
          || name.isEmpty()
          || descriptor == null
          || descriptor.isEmpty()) throw new IllegalArgumentException("Invalid certified method");
    }
  }

  public CodeEvidence {
    code = code.clone();
    methods = List.copyOf(methods);
    if (run <= 0
        || stage == null
        || status == null
        || jvmtiError < 0
        || epoch < 0
        || classToken < 0
        || loaderToken < 0
        || (flags & ~31) != 0
        || code.length > MAX_CODE_BYTES
        || methods.size() > MAX_METHODS)
      throw new IllegalArgumentException("Invalid code certificate");
    if (stage == Stage.FINAL && (code.length != 0 || !methods.isEmpty()))
      throw new IllegalArgumentException("Final code certificate carries startup payload");
    if (status == Status.VALID
        && (jvmtiError != 0
            || epoch == 0
            || classToken == 0
            || stage == Stage.STARTUP && (code.length == 0 || methods.isEmpty())))
      throw new IllegalArgumentException("Incomplete valid code certificate");
    var tokens = new HashSet<Long>();
    var identities = new HashSet<List<String>>();
    for (var method : methods)
      if (!tokens.add(method.token())
          || !identities.add(List.of(method.name(), method.descriptor())))
        throw new IllegalArgumentException("Duplicate certified method");
  }

  /** Returns a copy: certificate bytes cannot change after validation. */
  @Override
  public byte[] code() {
    return code.clone();
  }

  /** Writes the bounded version 1 envelope, replacing an existing file. */
  public void write(Path path) throws IOException {
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeLong(run);
      out.writeInt(stage.ordinal());
      out.writeInt(status.ordinal());
      out.writeInt(jvmtiError);
      out.writeLong(epoch);
      out.writeLong(classToken);
      out.writeLong(loaderToken);
      out.writeInt(flags);
      out.writeInt(code.length);
      out.write(code);
      out.writeInt(methods.size());
      for (var method : methods) {
        out.writeLong(method.token());
        out.writeUTF(method.name());
        out.writeUTF(method.descriptor());
      }
      out.writeLong(END);
    }
  }

  /** Decodes bounded framing and identity; byte provenance and final agreement are host checks. */
  public static CodeEvidence read(Path path, long run, Stage stage) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("Code evidence exceeds byte limit");
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      return read(in, run, stage);
    }
  }

  /** Decodes native JNI output with the same bounds and EOF requirements as retained files. */
  public static CodeEvidence read(byte[] bytes, long run, Stage stage) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Code evidence exceeds byte limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return read(in, run, stage);
    }
  }

  private static CodeEvidence read(DataInputStream in, long run, Stage stage) throws IOException {
    if (in.readInt() != MAGIC
        || in.readInt() != 1
        || run <= 0
        || in.readLong() != run
        || in.readInt() != stage.ordinal())
      throw new IOException("Code evidence identity mismatch");
    int status = count(in, Status.values().length - 1);
    int error = in.readInt();
    long epoch = in.readLong(), type = in.readLong(), loader = in.readLong();
    int flags = in.readInt();
    byte[] code = new byte[count(in, MAX_CODE_BYTES)];
    in.readFully(code);
    var methods = new ArrayList<Method>();
    try {
      for (int remaining = count(in, MAX_METHODS); remaining > 0; remaining--)
        methods.add(new Method(in.readLong(), in.readUTF(), in.readUTF()));
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Damaged code evidence footer");
      return new CodeEvidence(
          run, stage, Status.values()[status], error, epoch, type, loader, flags, code, methods);
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > maximum) throw new IOException("Invalid code evidence count");
    return count;
  }
}
