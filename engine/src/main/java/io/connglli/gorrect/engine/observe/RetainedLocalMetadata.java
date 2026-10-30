package io.connglli.gorrect.engine.observe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prototype reader for local metadata in retained Java 17 compiler output. This does not establish
 * runtime class-loader identity, bytecode provenance, frame activation, or physical root coverage.
 */
public final class RetainedLocalMetadata {
  /** Prototype resource ceiling, independent of the classfile format's limits. */
  public static final int MAX_BYTES = 16 * 1024 * 1024;

  /** Aggregate metadata ceiling across all methods in one class. */
  public static final int MAX_LOCAL_ENTRIES = 65_536;

  public record ClassInfo(String signature, List<MethodInfo> methods) {
    public ClassInfo {
      methods = List.copyOf(methods);
    }
  }

  public record MethodInfo(String name, String descriptor, Optional<CodeInfo> code) {}

  /** An absent table differs from a present table with no entries. */
  public record CodeInfo(
      int length, int maxLocals, boolean hasLocalVariableTable, List<LocalVariable> locals) {
    public CodeInfo {
      locals = List.copyOf(locals);
    }
  }

  /** Compiler scope is the half-open bytecode interval [start, start + length). */
  public record LocalVariable(int start, int length, int slot, String name, String descriptor) {}

  private RetainedLocalMetadata() {}

  /**
   * Rejects inputs larger than {@link #MAX_BYTES}, unsupported versions, damaged framing, invalid
   * metadata, and an owner signature different from expectedSignature with IOException. Matching
   * the signature alone does not prove that the VM loaded these bytes.
   */
  public static ClassInfo read(Path path, String expectedSignature) throws IOException {
    try (var input = Files.newInputStream(path)) {
      return read(input.readNBytes(MAX_BYTES + 1), expectedSignature);
    }
  }

  /**
   * Reads a caller-owned byte array without retaining it; the caller must not mutate it during this
   * call. This checks metadata and structural lengths, not JVM bytecode verification or all
   * constant pool references in skipped structures.
   */
  public static ClassInfo read(byte[] bytes, String expectedSignature) throws IOException {
    Objects.requireNonNull(expectedSignature);
    if (bytes.length > MAX_BYTES) throw new IOException("Retained classfile exceeds byte limit");
    return new Reader(bytes).read(expectedSignature);
  }

  private record MethodIdentity(String name, String descriptor) {}

  private static final class Reader {
    private final Cursor input;
    private String[] strings;
    private int[] classNames;
    private int localEntries;

    Reader(byte[] bytes) {
      input = new Cursor(bytes, 0, bytes.length);
    }

    ClassInfo read(String expectedSignature) throws IOException {
      require(input.u4() == 0xcafebabeL, "Invalid classfile magic");
      require(input.u2() == 0 && input.u2() == 61, "Expected Java 17 classfile version 61.0");
      constantPool();
      input.u2(); // Class access flags are not local metadata.
      String owner = className(input.u2());
      String signature = "L" + owner + ";";
      require(signature.equals(expectedSignature), "Retained classfile owner mismatch");
      int parent = input.u2();
      if (parent != 0) className(parent);
      int interfaces = input.u2();
      for (int index = 0; index < interfaces; index++) className(input.u2());
      int fields = input.u2();
      for (int index = 0; index < fields; index++) {
        input.u2();
        localName(utf8(input.u2()));
        fieldWidth(utf8(input.u2()));
        skipAttributes(input);
      }
      var methods = new ArrayList<MethodInfo>();
      var identities = new HashSet<MethodIdentity>();
      int count = input.u2();
      for (int index = 0; index < count; index++) {
        MethodInfo method = method();
        require(
            identities.add(new MethodIdentity(method.name(), method.descriptor())),
            "Duplicate method identity");
        methods.add(method);
      }
      skipAttributes(input);
      input.end();
      return new ClassInfo(signature, methods);
    }

    private void constantPool() throws IOException {
      int count = input.u2();
      require(count > 0, "Empty constant pool");
      strings = new String[count];
      classNames = new int[count];
      for (int index = 1; index < count; index++) {
        switch (input.u1()) {
          case 1 -> strings[index] = input.utf8();
          case 3, 4 -> input.skip(4);
          case 5, 6 -> {
            require(index + 1 < count, "Missing second constant-pool slot");
            input.skip(8);
            index++;
          }
          case 7 -> classNames[index] = input.u2();
          case 8, 16, 19, 20 -> input.skip(2);
          case 9, 10, 11, 12, 17, 18 -> input.skip(4);
          case 15 -> input.skip(3);
          default -> throw new IOException("Unknown constant-pool tag");
        }
      }
    }

    private MethodInfo method() throws IOException {
      int flags = input.u2();
      String name = utf8(input.u2()), descriptor = utf8(input.u2());
      if (!name.equals("<init>") && !name.equals("<clinit>")) {
        localName(name);
        require(name.indexOf('<') < 0 && name.indexOf('>') < 0, "Invalid method name");
      }
      int parameters = methodParameters(descriptor) + ((flags & 0x0008) == 0 ? 1 : 0);
      require(parameters <= 255, "Method parameter slot limit exceeded");
      CodeInfo code = null;
      int attributes = input.u2();
      for (int index = 0; index < attributes; index++) {
        String attribute = utf8(input.u2());
        Cursor body = input.section(input.u4());
        if (attribute.equals("Code")) {
          require(code == null, "Duplicate Code attribute");
          code = code(body);
          require(code.maxLocals() >= parameters, "Parameters exceed local slots");
        }
      }
      boolean executable = (flags & (0x0100 | 0x0400)) == 0;
      require(executable == (code != null), "Missing or forbidden Code attribute");
      return new MethodInfo(name, descriptor, Optional.ofNullable(code));
    }

    private CodeInfo code(Cursor code) throws IOException {
      code.u2(); // max_stack does not locate locals.
      int maxLocals = code.u2();
      long length = code.u4();
      require(length > 0 && length <= 65_535, "Invalid Code length");
      code.skip(length);
      int handlers = code.u2();
      for (int index = 0; index < handlers; index++) {
        int start = code.u2(), end = code.u2(), handler = code.u2(), type = code.u2();
        require(start < end && end <= length && handler < length, "Invalid exception scope");
        if (type != 0) className(type);
      }
      boolean hasTable = false;
      var locals = new ArrayList<LocalVariable>();
      var entries = new HashSet<LocalVariable>();
      int attributes = code.u2();
      for (int index = 0; index < attributes; index++) {
        String name = utf8(code.u2());
        Cursor body = code.section(code.u4());
        if (!name.equals("LocalVariableTable")) continue;
        hasTable = true;
        int count = body.u2();
        require(count <= MAX_LOCAL_ENTRIES - localEntries, "Local-variable entry limit exceeded");
        localEntries += count;
        for (int entry = 0; entry < count; entry++) {
          int start = body.u2(), scope = body.u2();
          String variable = utf8(body.u2()), descriptor = utf8(body.u2());
          int slot = body.u2(), width = fieldWidth(descriptor);
          localName(variable);
          require(start < length && start + scope <= length, "Invalid local-variable scope");
          require(slot + width <= maxLocals, "Local variable exceeds local slots");
          var local = new LocalVariable(start, scope, slot, variable, descriptor);
          require(entries.add(local), "Duplicate local-variable entry");
          locals.add(local);
        }
        body.end();
      }
      code.end();
      return new CodeInfo((int) length, maxLocals, hasTable, locals);
    }

    private void skipAttributes(Cursor cursor) throws IOException {
      int count = cursor.u2();
      for (int index = 0; index < count; index++) {
        utf8(cursor.u2());
        cursor.skip(cursor.u4());
      }
    }

    private String utf8(int index) throws IOException {
      require(
          index > 0 && index < strings.length && strings[index] != null, "Expected UTF-8 constant");
      return strings[index];
    }

    private String className(int index) throws IOException {
      require(
          index > 0 && index < classNames.length && classNames[index] != 0,
          "Expected class constant");
      String name = utf8(classNames[index]);
      internalName(name);
      return name;
    }
  }

  private static int methodParameters(String descriptor) throws IOException {
    require(descriptor.startsWith("("), "Invalid method descriptor");
    int offset = 1, slots = 0;
    while (offset < descriptor.length() && descriptor.charAt(offset) != ')') {
      slots += width(descriptor.charAt(offset));
      offset = typeEnd(descriptor, offset);
    }
    require(offset < descriptor.length(), "Invalid method descriptor");
    offset++;
    if (offset < descriptor.length() && descriptor.charAt(offset) == 'V') offset++;
    else offset = typeEnd(descriptor, offset);
    require(offset == descriptor.length(), "Invalid method descriptor");
    return slots;
  }

  private static int fieldWidth(String descriptor) throws IOException {
    require(typeEnd(descriptor, 0) == descriptor.length(), "Invalid local-variable descriptor");
    return width(descriptor.charAt(0));
  }

  private static int width(char type) {
    return type == 'J' || type == 'D' ? 2 : 1;
  }

  private static int typeEnd(String descriptor, int start) throws IOException {
    int offset = start;
    while (offset < descriptor.length() && descriptor.charAt(offset) == '[') offset++;
    require(offset - start <= 255 && offset < descriptor.length(), "Invalid field descriptor");
    char type = descriptor.charAt(offset++);
    if ("BCDFIJSZ".indexOf(type) >= 0) return offset;
    require(type == 'L', "Invalid field descriptor");
    int end = descriptor.indexOf(';', offset);
    require(end >= 0, "Invalid object descriptor");
    internalName(descriptor.substring(offset, end));
    return end + 1;
  }

  private static void internalName(String name) throws IOException {
    for (String component : name.split("/", -1)) localName(component);
  }

  private static void localName(String name) throws IOException {
    require(
        !name.isEmpty()
            && name.indexOf('.') < 0
            && name.indexOf(';') < 0
            && name.indexOf('[') < 0
            && name.indexOf('/') < 0,
        "Invalid unqualified name");
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }

  private static final class Cursor {
    private final byte[] bytes;
    private final int limit;
    private int offset;

    Cursor(byte[] bytes, int offset, int limit) {
      this.bytes = bytes;
      this.offset = offset;
      this.limit = limit;
    }

    int u1() throws IOException {
      require(offset < limit, "Truncated classfile");
      return Byte.toUnsignedInt(bytes[offset++]);
    }

    int u2() throws IOException {
      return (u1() << 8) | u1();
    }

    long u4() throws IOException {
      return ((long) u2() << 16) | u2();
    }

    String utf8() throws IOException {
      int start = offset, length = u2();
      skip(length);
      try (var input = new DataInputStream(new ByteArrayInputStream(bytes, start, length + 2))) {
        String value = input.readUTF();
        // readUTF accepts noncanonical encodings, including raw NUL, forbidden in CONSTANT_Utf8.
        var canonical = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(canonical)) {
          output.writeUTF(value);
        }
        byte[] encoded = canonical.toByteArray();
        require(
            Arrays.equals(bytes, start, offset, encoded, 0, encoded.length),
            "Noncanonical modified UTF-8 constant");
        return value;
      }
    }

    void skip(long length) throws IOException {
      require(length >= 0 && length <= limit - offset, "Truncated classfile section");
      offset += (int) length;
    }

    Cursor section(long length) throws IOException {
      int start = offset;
      skip(length);
      return new Cursor(bytes, start, offset);
    }

    void end() throws IOException {
      require(offset == limit, "Trailing bytes in classfile section");
    }
  }
}
