package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.runtime.TargetSession;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorEmissionTest {
  @TempDir Path directory;

  @Test
  void injectedUnexpectedFailureReleasesBothJavaMonitorsAndRethrowsWithoutExitMarkers()
      throws Exception {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 0 1; object 1 N [] [7]; root main=1;
            program { read-root owner N main; monitor owner {
              write-data owner 0 8; monitor owner { write-data owner 0 9; }
            } }
            """);
    String source = JavaEmitter.emit(program).source();
    assertEquals(2, source.lines().filter(line -> line.contains("synchronized (b1)")).count());
    // Inject only the recorder and a failing payload expression; keep javac's monitor lowering.
    source =
        source
            .replace(
                "public final class Case {",
                """
                public final class Case {
                public static final RuntimeException failure = new RuntimeException("injected");
                public static final java.util.List<String> events = new java.util.ArrayList<>();
                private static void recordProbe(int operation, int kind, long value) {
                  if (operation == 2 || operation == 4) {
                    if (value == 0 && !Thread.holdsLock(Roots.r0)) throw new AssertionError("entry");
                  }
                  events.add(operation + ":" + value);
                }
                private static long failProbe() {
                  if (!Thread.holdsLock(Roots.r0)) throw new AssertionError("body");
                  throw failure;
                }
                """)
            .replace("session.record(", "recordProbe(")
            .replace("b1.d0 = 9L;", "b1.d0 = failProbe();");
    Path file = directory.resolve("Case.java");
    Files.writeString(file, source);
    String runtime =
        Path.of(TargetSession.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    String protocol =
        Path.of(ExecutionTrace.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
      assertTrue(
          compiler
              .getTask(
                  null,
                  manager,
                  diagnostics,
                  List.of(
                      "--release",
                      "17",
                      "-g",
                      "-Xlint:all",
                      "-Werror",
                      "-classpath",
                      runtime + File.pathSeparator + protocol,
                      "-d",
                      directory.toString()),
                  null,
                  manager.getJavaFileObjects(file))
              .call(),
          diagnostics.getDiagnostics().toString());
    }
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, TargetSession.class.getClassLoader())) {
      var target = loader.loadClass("Case");
      var initialize = target.getDeclaredMethod("initialize");
      initialize.setAccessible(true);
      initialize.invoke(null);
      var execute = target.getDeclaredMethod("execute", TargetSession.class);
      execute.setAccessible(true);
      var thrown =
          assertThrows(
              InvocationTargetException.class, () -> execute.invoke(null, new Object[] {null}));
      assertSame(target.getField("failure").get(null), thrown.getCause());
      assertEquals("[1:0, 2:0, 3:0, 4:0]", target.getField("events").get(null).toString());
      Object owner = loader.loadClass("Case$Roots").getField("r0").get(null);
      assertEquals(8L, owner.getClass().getField("d0").getLong(owner));
      assertFalse(Thread.holdsLock(owner));
      var acquired = new AtomicBoolean();
      var contender =
          new Thread(
              () -> {
                synchronized (owner) {
                  acquired.set(true);
                }
              });
      contender.setDaemon(true);
      contender.start();
      contender.join(1000);
      assertTrue(acquired.get(), "An independent thread must acquire after abrupt release");
    }
  }
}
