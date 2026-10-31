package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.emit.JavaEmitter;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.runtime.TargetSession;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamedQueueEmissionTest {
  @TempDir Path directory;

  @Test
  void actualConstructorsAndApiCallsCompileForJava17() throws Exception {
    var program =
        GirParser.parse(
            """
            gir 1; shape W weak; shape S soft; queue ready W; queue another S;
            program { allocate-ref r W null ready; allocate-ref noqueue W r;
              enqueue-ref inserted r; poll-queue held ready;
              enqueue-ref failed noqueue; checkpoint; }
            """);
    var emission = JavaEmitter.emit(program);
    assertEquals(2, emission.layout().queuePlan().resources().size());
    assertTrue(emission.source().contains(".enqueue()"));
    assertTrue(emission.source().contains(".poll()"));
    assertTrue(emission.source().contains("super(target, queue)"));
    Path source = directory.resolve("Case.java");
    Files.writeString(source, emission.source());
    String runtime =
        Path.of(TargetSession.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    String protocol =
        Path.of(ExecutionTrace.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    var compiler = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean result =
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
                  manager.getJavaFileObjects(source))
              .call();
      assertTrue(result, diagnostics.getDiagnostics().toString());
    }
  }
}
