package io.connglli.gorrect.engine.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.observe.ObservationProfile;
import io.connglli.gorrect.engine.observe.RetainedLocalMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodEmissionTest {
  @TempDir Path directory;

  @Test
  void warningFreeRelease17ClassContainsActualDirectStaticCallsAndTypedParameters()
      throws IOException, InterruptedException {
    var program =
        GirParser.parse(
            """
            gir 1; shape N object 1 1; shape A refs 1; shape D data 1; root saved=null;
            method leaf(ref N node, ref A references, ref D data, long value) {
              allocate local N; write-data local 0 value; write-ref node 0 local;
              scope { allocate nested N; release nested; }
              write-ref references 0 local; write-data data 0 value; release node;
            }
            method outer(ref N node, ref A references, ref D data) { call leaf(node, references, data, 7); }
            program { allocate caller N; allocate array A; allocate data D; call outer(caller, array, data);
              write-root saved caller; }
            """);
    var emission = JavaEmitter.emit(program, ObservationProfile.CONTROLLED_ROOTS, "retained-class");
    Path source = directory.resolve("Case.java");
    Files.writeString(source, emission.source());
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
                source.toString()));
    var metadata = RetainedLocalMetadata.read(directory.resolve("Case.class"), "LCase;");
    assertTrue(
        metadata.methods().stream()
            .anyMatch(
                method ->
                    method.name().equals("helper1")
                        && method
                            .descriptor()
                            .equals(
                                "(Lio/connglli/gorrect/runtime/TargetSession;ILCase$S0;[Ljava/lang/Object;[JJ)V")));
    var command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin/javap").toString(),
            "-p",
            "-c",
            directory.resolve("Case.class").toString());
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String disassembly =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), disassembly);
    assertTrue(
        disassembly
            .lines()
            .anyMatch(line -> line.contains("invokestatic") && line.contains("helper1:")),
        disassembly);
    assertTrue(
        disassembly
            .lines()
            .anyMatch(line -> line.contains("invokestatic") && line.contains("helper2:")),
        disassembly);
    assertTrue(disassembly.contains("Exception table:"));
  }
}
