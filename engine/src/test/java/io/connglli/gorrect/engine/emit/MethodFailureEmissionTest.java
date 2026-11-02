package io.connglli.gorrect.engine.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Injects recorder failures into the actual generated Java, independently of the graph model. */
class MethodFailureEmissionTest {
  @TempDir Path directory;

  @Test
  void generatedFinallyRestoresDepthOnEntryBodyAndExitRecorderFailures() throws Exception {
    compile();
    for (int limit : List.of(2, 3, 5, 6, 7)) {
      try (var loader =
          new URLClassLoader(
              new java.net.URL[] {directory.toUri().toURL()},
              ClassLoader.getPlatformClassLoader())) {
        var sessionType = loader.loadClass("io.connglli.gorrect.runtime.TargetSession");
        var session = sessionType.getConstructor(int.class).newInstance(limit);
        var workload = loader.loadClass("Case");
        var execute = workload.getDeclaredMethod("execute", sessionType);
        execute.setAccessible(true);
        if (limit < 7)
          assertThrows(InvocationTargetException.class, () -> execute.invoke(null, session));
        else execute.invoke(null, session);
        assertEquals(0, sessionType.getField("depth").getInt(session));
        assertEquals(limit, sessionType.getField("count").getInt(session));
        var node = loader.loadClass("Case$Roots").getField("r0").get(null);
        assertNotNull(node);
        assertEquals(limit >= 5 ? 7L : 0L, node.getClass().getField("d0").getLong(node));
        if (limit >= 5) assertNotNull(node.getClass().getField("f0").get(node));
        else assertNull(node.getClass().getField("f0").get(node));
        assertEquals(limit == 7, sessionType.getField("exited").getBoolean(session));
      }
    }
  }

  private void compile() throws IOException {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; root saved=null;
            method f(ref N node) { allocate local N; write-data node 0 7; write-ref node 0 local; }
            program { allocate node N; write-root saved node; call f(node); }
            """);
    Path source = directory.resolve("Case.java");
    Files.writeString(
        source,
        JavaEmitter.emit(program, ObservationProfile.CONTROLLED_ROOTS, "retained-class").source());
    Path stub = directory.resolve("io/connglli/gorrect/runtime/TargetSession.java");
    Files.createDirectories(stub.getParent());
    Files.writeString(
        stub,
        """
        package io.connglli.gorrect.runtime;
        public final class TargetSession implements AutoCloseable {
          private final int capacity;
          public int count, depth;
          public boolean exited;
          public TargetSession(int capacity) { this.capacity = capacity; }
          public static TargetSession open(String[] args, Class<?> owner) { return new TargetSession(100); }
          public void record(int operation, int kind, long value) {
            if (count == capacity) throw new IllegalStateException("injected recorder exhaustion");
            count++;
            if (kind == %d) exited = true;
          }
          public void callEnter(int operation) { record(operation, %d, 0); depth++; }
          public void callLeave() { depth--; }
          public void checkpoint(int operation) {}
          public void binding(int declaration, boolean present) {}
          public void complete() {}
          public void fail(Throwable failure) {}
          @Override public void close() {}
        }
        """
            .formatted(
                ExecutionTrace.Kind.CALL_EXIT.ordinal(), ExecutionTrace.Kind.CALL_ENTER.ordinal()));
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "17",
                "-g",
                "-Xlint:all",
                "-Werror",
                "-cp",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                stub.toString(),
                source.toString()));
  }
}
