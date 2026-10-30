package io.connglli.gorrect.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Versioned, big-endian snapshot codec. Structural damage is an extraction error. */
public final class SnapshotCodec {
  public static final int MAGIC = 0x47525331;
  public static final int VERSION = 1;
  public static final long END = 0x454e44534e415031L;
  public static final int MAX_BYTES = 128 * 1024 * 1024;
  public static final int MAX_RECORDS = 1_000_000;
  private static final int MAX_METADATA = 65_536;
  private static final HeapSnapshot.Status[] STATUSES = HeapSnapshot.Status.values();
  private static final HeapSnapshot.ClassKind[] CLASS_KINDS = HeapSnapshot.ClassKind.values();
  private static final HeapSnapshot.Kind[] RECORD_KINDS = HeapSnapshot.Kind.values();

  private SnapshotCodec() {}

  /**
   * Checks framing, bounds, and encoding, including for partial captures. Successful decoding does
   * not establish semantic validity or enough coverage for graph verification.
   */
  public static HeapSnapshot read(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Snapshot exceeds byte limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC || in.readInt() != VERSION)
        throw new IOException("Unsupported snapshot format");
      long run = in.readLong();
      long checkpoint = in.readLong();
      if (run <= 0 || checkpoint <= 0) throw new IOException("Invalid capture identity");
      var status = value(STATUSES, in.readInt());
      int error = in.readInt();
      long capabilities = in.readLong();
      long startsBefore = in.readLong();
      long finishesBefore = in.readLong();
      long startsAfter = in.readLong();
      long finishesAfter = in.readLong();
      var classes = new ArrayList<HeapSnapshot.ClassInfo>();
      int classCount = count(in, MAX_METADATA);
      for (int i = 0; i < classCount; i++) {
        int id = in.readInt();
        var kind = value(CLASS_KINDS, in.readInt());
        String signature = string(in);
        int fieldCount = count(in, MAX_METADATA);
        var fields = new ArrayList<HeapSnapshot.FieldInfo>();
        for (int j = 0; j < fieldCount; j++)
          fields.add(
              new HeapSnapshot.FieldInfo(in.readInt(), in.readInt(), string(in), string(in)));
        classes.add(new HeapSnapshot.ClassInfo(id, kind, signature, fields));
      }
      var arrays = new ArrayList<HeapSnapshot.ArrayIdentity>();
      int arrayCount = count(in, MAX_METADATA);
      for (int i = 0; i < arrayCount; i++)
        arrays.add(
            new HeapSnapshot.ArrayIdentity(
                in.readLong(), string(in), in.readInt(), in.readInt(), in.readLong()));
      var methods = new ArrayList<HeapSnapshot.MethodInfo>();
      int methodCount = count(in, MAX_METADATA);
      for (int i = 0; i < methodCount; i++) {
        long token = in.readLong();
        String owner = string(in), name = string(in), descriptor = string(in);
        var locals = new ArrayList<HeapSnapshot.LocalVariable>();
        if ((capabilities & HeapSnapshot.LOCAL_VARIABLES) != 0) {
          int localCount = count(in, MAX_METADATA);
          for (int j = 0; j < localCount; j++) locals.add(local(in));
        }
        methods.add(new HeapSnapshot.MethodInfo(token, owner, name, descriptor, locals));
      }
      var records = new ArrayList<HeapSnapshot.Record>();
      int recordCount = count(in, MAX_RECORDS);
      for (int i = 0; i < recordCount; i++)
        records.add(
            new HeapSnapshot.Record(
                value(RECORD_KINDS, in.readLong()),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong()));
      if (in.readLong() != END || in.read() != -1)
        throw new IOException("Missing footer or trailing snapshot data");
      return new HeapSnapshot(
          run,
          checkpoint,
          status,
          error,
          capabilities,
          startsBefore,
          finishesBefore,
          startsAfter,
          finishesAfter,
          classes,
          arrays,
          methods,
          records);
    }
  }

  /** Rejects an intact capture from another run or checkpoint. */
  public static HeapSnapshot read(byte[] bytes, long run, long checkpoint) throws IOException {
    var snapshot = read(bytes);
    if (snapshot.run() != run || snapshot.checkpoint() != checkpoint)
      throw new IOException("Capture identity mismatch");
    return snapshot;
  }

  /** Encodes evidence, preserving its status, and checks the reader's structural limits. */
  public static byte[] write(HeapSnapshot snapshot) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeLong(snapshot.run());
      out.writeLong(snapshot.checkpoint());
      out.writeInt(snapshot.status().ordinal());
      out.writeInt(snapshot.jvmtiError());
      out.writeLong(snapshot.capabilities());
      out.writeLong(snapshot.gcStartsBefore());
      out.writeLong(snapshot.gcFinishesBefore());
      out.writeLong(snapshot.gcStartsAfter());
      out.writeLong(snapshot.gcFinishesAfter());
      out.writeInt(snapshot.classes().size());
      for (var type : snapshot.classes()) {
        out.writeInt(type.id());
        out.writeInt(type.kind().ordinal());
        string(out, type.signature());
        out.writeInt(type.fields().size());
        for (var field : type.fields()) {
          out.writeInt(field.index());
          out.writeInt(field.modifiers());
          string(out, field.name());
          string(out, field.descriptor());
        }
      }
      out.writeInt(snapshot.arrays().size());
      for (var array : snapshot.arrays()) {
        out.writeLong(array.tag());
        string(out, array.shape());
        out.writeInt(array.worker());
        out.writeInt(array.site());
        out.writeLong(array.occurrence());
      }
      out.writeInt(snapshot.methods().size());
      for (var method : snapshot.methods()) {
        out.writeLong(method.token());
        string(out, method.owner());
        string(out, method.name());
        string(out, method.descriptor());
        if ((snapshot.capabilities() & HeapSnapshot.LOCAL_VARIABLES) != 0) {
          out.writeInt(method.locals().size());
          for (var local : method.locals()) {
            out.writeLong(local.start());
            out.writeInt(local.length());
            out.writeInt(local.slot());
            string(out, local.name());
            string(out, local.descriptor());
          }
        } else if (!method.locals().isEmpty())
          throw new IOException("Local metadata lacks capability");
      }
      out.writeInt(snapshot.records().size());
      for (var record : snapshot.records()) {
        out.writeLong(record.kind().ordinal());
        out.writeLong(record.a());
        out.writeLong(record.b());
        out.writeLong(record.c());
        out.writeLong(record.d());
        out.writeLong(record.e());
        out.writeLong(record.f());
        out.writeLong(record.g());
      }
      out.writeLong(END);
    }
    byte[] result = bytes.toByteArray();
    // Enforce the same bounds and enum checks as the native-data reader.
    read(result);
    return result;
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int value = in.readInt();
    if (value < 0 || value > maximum) throw new IOException("Invalid snapshot count");
    return value;
  }

  private static HeapSnapshot.LocalVariable local(DataInputStream in) throws IOException {
    long start = in.readLong();
    int length = in.readInt(), slot = in.readInt();
    if (start < 0 || length < 0 || slot < 0 || slot > 65_535)
      throw new IOException("Invalid local-variable scope or slot");
    return new HeapSnapshot.LocalVariable(start, length, slot, string(in), string(in));
  }

  private static String string(DataInputStream in) throws IOException {
    int size = count(in, 1_048_576);
    byte[] bytes = in.readNBytes(size);
    if (bytes.length != size) throw new IOException("Truncated metadata string");
    // Replacement characters could silently change field names or class signatures.
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void string(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static <T> T value(T[] values, long index) throws IOException {
    if (index < 0 || index >= values.length)
      throw new IOException("Unknown snapshot enum value " + index);
    return values[(int) index];
  }
}
