package io.connglli.gorrect.engine.run;

import static io.connglli.gorrect.engine.run.RunTestSupport.assertPass;
import static io.connglli.gorrect.engine.run.RunTestSupport.input;
import static io.connglli.gorrect.engine.run.RunTestSupport.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks actual storage and bytecode because graph outcomes cannot detect stronger ordering. */
@Tag("hotspot")
class RootAccessTest {
  @TempDir Path directory;

  private static final String HEADER =
      "gir 1; shape N object 0 0; object 1 N [] []; root main = 1; root shared = null; ";

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void preservesPlainRootAccesses(int feature) throws IOException {
    Path run =
        execute(feature, "read-root n N main; write-root shared n; read-root copy N shared;");
    String code = executeBytecode(run);
    assertDirectAccess(code, "getstatic", "r1");
    assertDirectAccess(code, "putstatic", "r1");
    assertFalse(code.contains("java/lang/invoke/VarHandle"), code);
  }

  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void preservesMixedPlainVolatileAndAtomicAccessesToOneRoot(int feature) throws IOException {
    Path run =
        execute(
            feature,
            """
            read-root n N main;
            write-root shared n;
            read-root copy N shared;
            write-volatile-root shared null;
            read-volatile-root absent N shared;
            compare-set-root won shared null copy;
            exchange-root prior N shared null;
            write-root shared prior;
            read-volatile-root saved N shared;
            """);
    String code = executeBytecode(run);
    assertDirectAccess(code, "getstatic", "r1");
    assertDirectAccess(code, "putstatic", "r1");
    for (String method : new String[] {"getVolatile", "setVolatile", "compareAndSet", "getAndSet"})
      assertTrue(code.contains("java/lang/invoke/VarHandle." + method + ":"), code);

    assertPass(RunEngine.verify(run));
    assertPass(RunEngine.replay(run, directory.resolve("replay")));
  }

  private Path execute(int feature, String body) throws IOException {
    Path run = directory.resolve("run");
    assertPass(
        RunEngine.run(
            input(directory, HEADER + "program { scope { " + body + " } checkpoint; }"),
            profile(directory, feature),
            run));

    var snapshot = SnapshotCodec.read(Files.readAllBytes(run.resolve("snapshots/1.gsnap")));
    var roots =
        snapshot.classes().stream()
            .filter(type -> type.kind() == HeapSnapshot.ClassKind.ROOTS)
            .findFirst()
            .orElseThrow();
    assertEquals(2, roots.fields().size());
    for (var field : roots.fields()) {
      assertTrue(Modifier.isStatic(field.modifiers()));
      assertFalse(Modifier.isVolatile(field.modifiers()));
      assertFalse(Modifier.isFinal(field.modifiers()));
    }
    return run;
  }

  private static String executeBytecode(Path run) {
    var output = new StringWriter();
    var writer = new PrintWriter(output);
    var javap = ToolProvider.findFirst("javap").orElseThrow();
    assertEquals(
        0,
        javap.run(
            writer,
            writer,
            "-c",
            "-p",
            "-classpath",
            run.resolve("generated/classes").toString(),
            "Case"),
        output::toString);

    // Exclude initialization stores: they cannot prove that GIR writes use the requested mode.
    String code = output.toString();
    int start = code.indexOf("private static void execute(");
    assertTrue(start >= 0, code);
    int end = code.indexOf("\n  private ", start + 1);
    return code.substring(start, end < 0 ? code.length() : end);
  }

  private static void assertDirectAccess(String code, String opcode, String field) {
    assertTrue(
        code.lines()
            .anyMatch(
                line ->
                    line.contains(": " + opcode + " ")
                        && line.endsWith("// Field Case$Roots." + field + ":Ljava/lang/Object;")),
        code);
  }
}
