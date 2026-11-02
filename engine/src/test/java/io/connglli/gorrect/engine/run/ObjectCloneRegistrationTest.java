package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises native class admission independently of generated source and oracle projection. */
class ObjectCloneRegistrationTest {
  @TempDir Path directory;

  @Tag("hotspot")
  @ParameterizedTest
  @ValueSource(ints = {17, 21})
  void exactBootstrapMarkerIsTheOnlyInterfaceAdmittedForOrdinaryClasses(int feature)
      throws Exception {
    check(System.getProperty("gorrect.java" + feature));
  }

  @Tag("openj9")
  @Test
  void selectedOpenJ9KeepsTheSameRegistrationBoundary() throws Exception {
    check(System.getProperty("gorrect.openj9.java"));
  }

  private void check(String executable) throws Exception {
    Path support = Path.of(System.getProperty("gorrect.support"));
    String classpath =
        support.resolve("runtime.jar") + File.pathSeparator + support.resolve("protocol.jar");
    Path source = directory.resolve("RegistrationProbe.java");
    Files.writeString(
        source,
        """
        import io.connglli.gorrect.runtime.observe.NativeObserver;
        import io.connglli.gorrect.protocol.HeapSnapshot;
        import java.util.List;
        public final class RegistrationProbe {
          interface Cloneable {}
          interface SubMarker extends java.lang.Cloneable {}
          static final class Fake implements Cloneable {}
          static final class Sub implements SubMarker {}
          static final class Other implements java.io.Serializable {
            private static final long serialVersionUID = 1L;
          }
          static final class Both implements java.lang.Cloneable, Cloneable {}
          static class Parent {}
          static final class Child extends Parent implements java.lang.Cloneable {}
          static final class Wrapper extends java.lang.ref.WeakReference<Object>
              implements java.lang.Cloneable { Wrapper() { super(null); } }
          static final class Exact implements java.lang.Cloneable {}
          public static void main(String[] args) {
            System.load(args[0]);
            Class<?> type = switch (args[1]) {
              case "fake" -> Fake.class; case "sub" -> Sub.class;
              case "other" -> Other.class; case "both" -> Both.class;
              case "child" -> Child.class; case "wrapper" -> Wrapper.class;
              default -> Exact.class;
            };
            var kind = args[1].equals("root") ? HeapSnapshot.ClassKind.ROOTS
                : args[1].equals("wrapper") ? HeapSnapshot.ClassKind.WEAK_REFERENCE
                : HeapSnapshot.ClassKind.OBJECT;
            boolean rejected = false;
            try { NativeObserver.configure(List.of(new NativeObserver.Registration(type, kind))); }
            catch (IllegalStateException expected) { rejected = true; }
            if (rejected == args[1].equals("exact")) throw new AssertionError(args[1]);
          }
        }
        """);
    var diagnostics = new StringWriter();
    assertEquals(
        0,
        ToolProvider.findFirst("javac")
            .orElseThrow()
            .run(
                new PrintWriter(diagnostics),
                new PrintWriter(diagnostics),
                "--release",
                "17",
                "-Xlint:all",
                "-Werror",
                "-cp",
                classpath,
                "-d",
                directory.toString(),
                source.toString()),
        diagnostics.toString());
    Path library = support.resolve(System.mapLibraryName("gorrect_observer"));
    for (String kind :
        List.of("fake", "sub", "other", "both", "child", "wrapper", "root", "exact")) {
      Path log = directory.resolve(kind + ".log");
      var process =
          new ProcessBuilder(
                  executable,
                  "-agentpath:" + library,
                  "-cp",
                  directory + File.pathSeparator + classpath,
                  "RegistrationProbe",
                  library.toString(),
                  kind)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), kind);
        assertEquals(0, process.exitValue(), Files.readString(log));
      } finally {
        if (process.isAlive()) process.destroyForcibly().waitFor();
      }
    }
  }
}
