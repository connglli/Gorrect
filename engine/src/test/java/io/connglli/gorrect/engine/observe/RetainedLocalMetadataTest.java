package io.connglli.gorrect.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetainedLocalMetadataTest {
  @TempDir Path directory;

  @Test
  void readsActualJava17CompilerTablesAndOverloadedMethodsWithoutLoadingTheClass()
      throws IOException {
    var metadata = RetainedLocalMetadata.read(compile("-g"), "LCase;");
    assertEquals("LCase;", metadata.signature());
    var execute = method(metadata, "execute", "(Ljava/lang/Object;)Ljava/lang/Object;");
    var code = execute.code().orElseThrow();
    assertTrue(code.hasLocalVariableTable());
    assertEquals(8, code.maxLocals());
    assertLocal(code, "argument", "Ljava/lang/Object;", 0);
    assertLocal(code, "b7", "Ljava/lang/Object;", 1);
    assertLocal(code, "wide", "J", 2);
    assertLocal(code, "fraction", "D", 4);
    assertLocal(code, "refs", "[Ljava/lang/Object;", 6);
    for (var local : code.locals()) {
      assertTrue(local.start() >= 0 && local.length() > 0);
      assertTrue(local.start() + local.length() <= code.length());
    }
    assertTrue(method(metadata, "execute", "(I)I").code().isPresent());
    assertTrue(method(metadata, "absent", "()V").code().isEmpty());
    var reuse = method(metadata, "reuse", "(Ljava/lang/Object;)I").code().orElseThrow();
    var first = local(reuse, "first");
    var second = local(reuse, "second");
    assertEquals(first.slot(), second.slot());
    assertTrue(first.start() + first.length() <= second.start());
    assertThrows(UnsupportedOperationException.class, () -> metadata.methods().clear());
    assertThrows(UnsupportedOperationException.class, () -> code.locals().clear());
  }

  @Test
  void distinguishesMissingTablesFromPresentEmptyTablesAndMissingCode() throws IOException {
    var withoutDebug = RetainedLocalMetadata.read(compile("-g:none"), "LCase;");
    for (var method : withoutDebug.methods()) {
      if (method.code().isEmpty()) continue;
      assertFalse(method.code().orElseThrow().hasLocalVariableTable());
      assertEquals(List.of(), method.code().orElseThrow().locals());
    }
    var fixture = new Fixture();
    byte[] empty = fixture.emptyTable();
    var code =
        RetainedLocalMetadata.read(empty, "LCase;").methods().getFirst().code().orElseThrow();
    assertTrue(code.hasLocalVariableTable());
    assertEquals(List.of(), code.locals());
  }

  @Test
  void skipsEveryJava17ConstantTagAndAccountsForWideConstants() throws IOException {
    var fixture = new Fixture();
    var metadata = RetainedLocalMetadata.read(fixture.bytes, "LCase;");
    var code = metadata.methods().getFirst().code().orElseThrow();
    assertEquals(
        List.of(new RetainedLocalMetadata.LocalVariable(0, 4, 1, "b7", "Ljava/lang/Object;")),
        code.locals());
    assertEquals(metadata, RetainedLocalMetadata.read(fixture.bytes, "LCase;"));
  }

  @Test
  void distinguishesMethodIdentityPairsWithEqualConcatenations() throws IOException {
    byte[] bytes = nativeMethods("m", "()Lfoo()Lbar;", "m()Lfoo", "()Lbar;");
    var metadata = RetainedLocalMetadata.read(bytes, "LCase;");
    assertEquals(2, metadata.methods().size());
    assertTrue(method(metadata, "m", "()Lfoo()Lbar;").code().isEmpty());
    assertTrue(method(metadata, "m()Lfoo", "()Lbar;").code().isEmpty());
  }

  @Test
  void rejectsAnActualDuplicateMethodIdentityPair() throws IOException {
    byte[] bytes = nativeMethods("m", "()Lfoo()Lbar;", "m", "()Lfoo()Lbar;");
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(bytes, "LCase;"));
  }

  @Test
  void combinesSeparateTablesAndSkipsUnknownAttributes() throws IOException {
    var fixture = new Fixture();
    byte[] table = fixture.tableBytes();
    int relativeName = fixture.positions.get("local-name") - fixture.positions.get("table");
    table[relativeName] = (byte) (fixture.methodName >>> 8);
    table[relativeName + 1] = (byte) fixture.methodName;
    table[table.length - 1] = 2;
    byte[] combined = fixture.insert("class-attributes", table);
    fixture.put(combined, "code-attribute-count", 2, 2);
    fixture.put(combined, "code-size", 34 + table.length, 4);
    var code =
        RetainedLocalMetadata.read(combined, "LCase;").methods().getFirst().code().orElseThrow();
    assertEquals(2, code.locals().size());
    assertLocal(code, "execute", "Ljava/lang/Object;", 2);

    byte[] unknown = fixture.changed("table", fixture.methodName, 2);
    code = RetainedLocalMetadata.read(unknown, "LCase;").methods().getFirst().code().orElseThrow();
    assertFalse(code.hasLocalVariableTable());
    assertEquals(List.of(), code.locals());
  }

  @Test
  void rejectsDuplicateMethodsCodeAndLocalEntries() throws IOException {
    var fixture = new Fixture();
    byte[] table = fixture.tableBytes();
    byte[] locals = fixture.insert("class-attributes", table);
    fixture.put(locals, "code-attribute-count", 2, 2);
    fixture.put(locals, "code-size", 34 + table.length, 4);
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(locals, "LCase;"));

    byte[] codeAttribute =
        Arrays.copyOfRange(
            fixture.bytes,
            fixture.positions.get("code"),
            fixture.positions.get("class-attributes"));
    byte[] codes = fixture.insert("class-attributes", codeAttribute);
    fixture.put(codes, "method-attribute-count", 2, 2);
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(codes, "LCase;"));

    byte[] method =
        Arrays.copyOfRange(
            fixture.bytes,
            fixture.positions.get("method-flags"),
            fixture.positions.get("class-attributes"));
    byte[] methods = fixture.insert("class-attributes", method);
    fixture.put(methods, "method-count", 2, 2);
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(methods, "LCase;"));
  }

  @Test
  void allowsAZeroLengthScopeAndChecksWidthsInsteadOfDescriptorPrefixes() throws IOException {
    var fixture = new Fixture();
    byte[] zero = fixture.changed("scope-length", 0, 2);
    var code = RetainedLocalMetadata.read(zero, "LCase;").methods().getFirst().code().orElseThrow();
    assertEquals(0, code.locals().getFirst().length());

    byte[] wide = fixture.changed("local-descriptor", fixture.longType, 2);
    code = RetainedLocalMetadata.read(wide, "LCase;").methods().getFirst().code().orElseThrow();
    assertLocal(code, "b7", "J", 1);
    byte[] array = fixture.changed("local-descriptor", fixture.arrayType, 2);
    fixture.put(array, "slot", 2, 2);
    code = RetainedLocalMetadata.read(array, "LCase;").methods().getFirst().code().orElseThrow();
    assertLocal(code, "b7", "[J", 2);
  }

  @Test
  void boundsLocalEntriesAcrossMultipleTables() throws IOException {
    var fixture = new Fixture();
    var output = new ByteArrayOutputStream();
    try (var data = new DataOutputStream(output)) {
      data.writeShort(fixture.tableName);
      data.writeInt(2 + 10 * 65_535);
      data.writeShort(65_535);
      for (int slot = 0; slot < 65_535; slot++) {
        data.writeShort(0);
        data.writeShort(4);
        data.writeShort(fixture.localName);
        data.writeShort(fixture.localType);
        data.writeShort(slot);
      }
      data.writeShort(fixture.tableName);
      data.writeInt(22);
      data.writeShort(2);
      data.write(new byte[20]);
    }
    byte[] tables = output.toByteArray();
    byte[] expanded = fixture.replaceTable(tables);
    fixture.put(expanded, "code-attribute-count", 2, 2);
    fixture.put(expanded, "code-size", 16 + tables.length, 4);
    fixture.put(expanded, "max-locals", 65_535, 2);
    var failure =
        assertThrows(IOException.class, () -> RetainedLocalMetadata.read(expanded, "LCase;"));
    assertEquals("Local-variable entry limit exceeded", failure.getMessage());
  }

  @Test
  void checksEveryTruncationAndRejectsTrailingBytesAndOwnerMismatch() throws IOException {
    var fixture = new Fixture();
    for (int length = 0; length < fixture.bytes.length; length++) {
      byte[] prefix = Arrays.copyOf(fixture.bytes, length);
      assertThrows(
          IOException.class,
          () -> RetainedLocalMetadata.read(prefix, "LCase;"),
          "length " + length);
    }
    assertThrows(
        IOException.class,
        () ->
            RetainedLocalMetadata.read(
                Arrays.copyOf(fixture.bytes, fixture.bytes.length + 1), "LCase;"));
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(fixture.bytes, "LOther;"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "magic",
        "minor",
        "major",
        "tag",
        "owner",
        "method-name",
        "method-descriptor",
        "code-size",
        "code-length",
        "table-size",
        "table-count",
        "scope-start",
        "scope-length",
        "slot",
        "wide-slot",
        "local-name",
        "local-descriptor",
        "abstract-code",
        "unsigned-length",
        "exception-scope"
      })
  void rejectsMalformedFramingMetadataAndSlotBounds(String damage) throws IOException {
    var fixture = new Fixture();
    byte[] bytes =
        switch (damage) {
          case "magic" -> fixture.changed("magic", 0, 4);
          case "minor" -> fixture.changed("minor", 1, 2);
          case "major" -> fixture.changed("major", 65, 2);
          case "tag" -> fixture.changed("first-tag", 2, 1);
          case "owner" -> fixture.changed("owner", fixture.methodName, 2);
          case "method-name" -> fixture.changed("method-name", 0, 2);
          case "method-descriptor" -> fixture.changed("method-descriptor", fixture.localType, 2);
          case "code-size" -> fixture.changed("code-size", 1, 4);
          case "code-length" -> fixture.changed("code-length", 65_536, 4);
          case "table-size" -> fixture.changed("table-size", 13, 4);
          case "table-count" -> fixture.changed("table-count", 2, 2);
          case "scope-start" -> fixture.changed("scope-start", 4, 2);
          case "scope-length" -> fixture.changed("scope-length", 5, 2);
          case "slot" -> fixture.changed("slot", 3, 2);
          case "wide-slot" -> {
            byte[] wide = fixture.changed("local-descriptor", fixture.longType, 2);
            fixture.put(wide, "slot", 2, 2);
            yield wide;
          }
          case "local-name" -> fixture.changed("local-name", 0, 2);
          case "local-descriptor" -> fixture.changed("local-descriptor", fixture.methodType, 2);
          case "abstract-code" -> fixture.changed("method-flags", 0x0408, 2);
          case "unsigned-length" -> fixture.changed("code-size", -1, 4);
          case "exception-scope" -> fixture.changed("handler-count", 65_535, 2);
          default -> throw new AssertionError(damage);
        };
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(bytes, "LCase;"));
  }

  @Test
  void rejectsMalformedModifiedUtf8AndIncompleteWideConstant() throws IOException {
    var fixture = new Fixture();
    byte[] malformed = fixture.bytes.clone();
    malformed[fixture.positions.get("first-utf8")] = (byte) 0xc0;
    malformed[fixture.positions.get("first-utf8") + 1] = 0;
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(malformed, "LCase;"));
    byte[] noncanonical = fixture.bytes.clone();
    noncanonical[fixture.positions.get("first-utf8")] = 0;
    String decodedOwner = "L" + (char) 0 + "ase;";
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(noncanonical, decodedOwner));
    byte[] overlong = fixture.bytes.clone();
    overlong[fixture.positions.get("first-utf8")] = (byte) 0xc1;
    overlong[fixture.positions.get("first-utf8") + 1] = (byte) 0x81;
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(overlong, "LAse;"));
    var output = new ByteArrayOutputStream();
    try (var data = new DataOutputStream(output)) {
      data.writeInt(0xcafebabe);
      data.writeShort(0);
      data.writeShort(61);
      data.writeShort(2);
      data.writeByte(5);
      data.writeLong(0);
    }
    assertThrows(
        IOException.class, () -> RetainedLocalMetadata.read(output.toByteArray(), "LCase;"));
  }

  @Test
  void boundsByteArraysAndPathReadsBeforeParsing() throws IOException {
    byte[] oversized = new byte[RetainedLocalMetadata.MAX_BYTES + 1];
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(oversized, "LCase;"));
    Path file = directory.resolve("large.class");
    Files.write(file, oversized);
    assertThrows(IOException.class, () -> RetainedLocalMetadata.read(file, "LCase;"));
  }

  private Path compile(String debugOption) throws IOException {
    Path source = directory.resolve("Case.java");
    Files.writeString(
        source,
        """
        public abstract class Case {
          static final long LARGE = 1234567890123L;
          static final double FRACTION = 1.25;
          abstract void absent();
          static Object execute(Object argument) {
            Object b7 = argument;
            long wide = LARGE;
            double fraction = FRACTION;
            Object[] refs = { b7 };
            Runnable action = () -> System.out.print("");
            action.run();
            return wide + fraction > 0 ? refs[0] : b7;
          }
          static int execute(int value) { return value + 1; }
          static String text(String value) { return "value=" + value; }
          static int reuse(Object value) {
            int count;
            { Object first = value; count = first.hashCode(); }
            { Object second = value; count += second.hashCode(); }
            return count;
          }
        }
        """);
    var diagnostics = new ByteArrayOutputStream();
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                diagnostics,
                "--release",
                "17",
                debugOption,
                "-Xlint:all",
                "-Werror",
                "-d",
                directory.toString(),
                source.toString()),
        diagnostics.toString());
    return directory.resolve("Case.class");
  }

  private static byte[] nativeMethods(
      String firstName, String firstDescriptor, String secondName, String secondDescriptor)
      throws IOException {
    var output = new ByteArrayOutputStream();
    try (var data = new DataOutputStream(output)) {
      data.writeInt(0xcafebabe);
      data.writeShort(0);
      data.writeShort(61);
      data.writeShort(9);
      data.writeByte(1);
      data.writeUTF("Case");
      data.writeByte(7);
      data.writeShort(1);
      data.writeByte(1);
      data.writeUTF("java/lang/Object");
      data.writeByte(7);
      data.writeShort(3);
      for (String value : List.of(firstName, firstDescriptor, secondName, secondDescriptor)) {
        data.writeByte(1);
        data.writeUTF(value);
      }
      data.writeShort(0x0021);
      data.writeShort(2);
      data.writeShort(4);
      data.writeShort(0); // interfaces
      data.writeShort(0); // fields
      data.writeShort(2); // methods
      for (int name : new int[] {5, 7}) {
        data.writeShort(0x0109); // public static native
        data.writeShort(name);
        data.writeShort(name + 1);
        data.writeShort(0); // method attributes
      }
      data.writeShort(0); // class attributes
    }
    return output.toByteArray();
  }

  private static RetainedLocalMetadata.MethodInfo method(
      RetainedLocalMetadata.ClassInfo metadata, String name, String descriptor) {
    return metadata.methods().stream()
        .filter(method -> method.name().equals(name) && method.descriptor().equals(descriptor))
        .findFirst()
        .orElseThrow();
  }

  private static RetainedLocalMetadata.LocalVariable local(
      RetainedLocalMetadata.CodeInfo code, String name) {
    return code.locals().stream()
        .filter(local -> local.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static void assertLocal(
      RetainedLocalMetadata.CodeInfo code, String name, String descriptor, int slot) {
    var local = local(code, name);
    assertEquals(descriptor, local.descriptor());
    assertEquals(slot, local.slot());
  }

  /**
   * Structural fixtures exercise skipped constants without claiming that they are JVM-verifiable.
   */
  private static final class Fixture {
    final Map<String, Integer> positions = new HashMap<>();
    final List<byte[]> pool = new ArrayList<>();
    final int owner,
        parent,
        methodName,
        methodType,
        codeName,
        tableName,
        localName,
        localType,
        longType,
        arrayType;
    final byte[] bytes;
    ByteArrayOutputStream output;
    DataOutputStream data;

    Fixture() throws IOException {
      int ownerName = utf8("Case");
      owner = constant(7, new byte[] {(byte) (ownerName >>> 8), (byte) ownerName});
      int parentName = utf8("java/lang/Object");
      parent = constant(7, new byte[] {(byte) (parentName >>> 8), (byte) parentName});
      for (int tag : new int[] {3, 4, 5, 6, 8, 9, 10, 11, 12, 15, 16, 17, 18, 19, 20}) {
        int length =
            switch (tag) {
              case 5, 6 -> 8;
              case 8, 16, 19, 20 -> 2;
              case 15 -> 3;
              default -> 4;
            };
        constant(tag, new byte[length]);
      }
      utf8("nul" + (char) 0 + "\uD83D\uDE00");
      methodName = utf8("execute");
      methodType = utf8("()V");
      codeName = utf8("Code");
      tableName = utf8("LocalVariableTable");
      localName = utf8("b7");
      localType = utf8("Ljava/lang/Object;");
      longType = utf8("J");
      arrayType = utf8("[J");
      bytes = build(false);
    }

    byte[] emptyTable() throws IOException {
      return build(true);
    }

    private byte[] build(boolean empty) throws IOException {
      output = new ByteArrayOutputStream();
      data = new DataOutputStream(output);
      mark("magic");
      data.writeInt(0xcafebabe);
      mark("minor");
      data.writeShort(0);
      mark("major");
      data.writeShort(61);
      data.writeShort(pool.size() + 1);
      mark("first-tag");
      positions.put("first-utf8", output.size() + 3);
      for (byte[] entry : pool) if (entry != null) data.write(entry);
      data.writeShort(0x0021);
      mark("owner");
      data.writeShort(owner);
      data.writeShort(parent);
      data.writeShort(0); // interfaces
      data.writeShort(0); // fields
      mark("method-count");
      data.writeShort(1);
      mark("method-flags");
      data.writeShort(0x0008);
      mark("method-name");
      data.writeShort(methodName);
      mark("method-descriptor");
      data.writeShort(methodType);
      mark("method-attribute-count");
      data.writeShort(1);
      mark("code");
      data.writeShort(codeName);
      mark("code-size");
      data.writeInt(empty ? 24 : 34);
      data.writeShort(0); // max_stack
      mark("max-locals");
      data.writeShort(3);
      mark("code-length");
      data.writeInt(4);
      data.write(new byte[] {0, 0, 0, (byte) 177});
      mark("handler-count");
      data.writeShort(0);
      mark("code-attribute-count");
      data.writeShort(1);
      mark("table");
      data.writeShort(tableName);
      mark("table-size");
      data.writeInt(empty ? 2 : 12);
      mark("table-count");
      data.writeShort(empty ? 0 : 1);
      if (!empty) {
        mark("scope-start");
        data.writeShort(0);
        mark("scope-length");
        data.writeShort(4);
        mark("local-name");
        data.writeShort(localName);
        mark("local-descriptor");
        data.writeShort(localType);
        mark("slot");
        data.writeShort(1);
      }
      mark("class-attributes");
      data.writeShort(0);
      return output.toByteArray();
    }

    private int utf8(String value) throws IOException {
      var payload = new ByteArrayOutputStream();
      try (var entry = new DataOutputStream(payload)) {
        entry.writeUTF(value);
      }
      return constant(1, payload.toByteArray());
    }

    private int constant(int tag, byte[] payload) {
      int index = pool.size() + 1;
      byte[] entry = new byte[payload.length + 1];
      entry[0] = (byte) tag;
      System.arraycopy(payload, 0, entry, 1, payload.length);
      pool.add(entry);
      if (tag == 5 || tag == 6) pool.add(null);
      return index;
    }

    private void mark(String name) {
      positions.put(name, output.size());
    }

    byte[] tableBytes() {
      return Arrays.copyOfRange(bytes, positions.get("table"), positions.get("class-attributes"));
    }

    byte[] insert(String name, byte[] content) {
      int offset = positions.get(name);
      byte[] result = new byte[bytes.length + content.length];
      System.arraycopy(bytes, 0, result, 0, offset);
      System.arraycopy(content, 0, result, offset, content.length);
      System.arraycopy(bytes, offset, result, offset + content.length, bytes.length - offset);
      return result;
    }

    byte[] replaceTable(byte[] tables) {
      int start = positions.get("table"), end = positions.get("class-attributes");
      byte[] result = new byte[bytes.length - (end - start) + tables.length];
      System.arraycopy(bytes, 0, result, 0, start);
      System.arraycopy(tables, 0, result, start, tables.length);
      System.arraycopy(bytes, end, result, start + tables.length, bytes.length - end);
      return result;
    }

    byte[] changed(String name, int value, int width) {
      byte[] changed = bytes.clone();
      put(changed, name, value, width);
      return changed;
    }

    void put(byte[] target, String name, int value, int width) {
      int offset = positions.get(name);
      for (int index = 0; index < width; index++)
        target[offset + index] = (byte) (value >>> (8 * (width - index - 1)));
    }
  }
}
