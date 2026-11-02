package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.observe.RetainedLocalMetadata;
import io.connglli.gorrect.protocol.BindingEvidence;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.FrameEvidence;
import io.connglli.gorrect.protocol.HeapSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedMetadataTest {
  @TempDir Path directory;

  @Test
  void certifiesExactLoadedBytesAndRequiresIndependentFinalCompletion() throws IOException {
    var code = certificate();
    assertEquals(code.methods(), RetainedMetadata.startup(directory, 17).methods());
    assertThrows(IOException.class, () -> RetainedMetadata.completion(directory, 17));
    terminal(code, code.epoch(), CodeEvidence.Status.VALID);
    RetainedMetadata.completion(directory, 17);
    terminal(code, code.epoch() + 1, CodeEvidence.Status.VALID);
    assertThrows(IOException.class, () -> RetainedMetadata.completion(directory, 17));
    terminal(code, code.epoch(), CodeEvidence.Status.INVALIDATED);
    assertThrows(IOException.class, () -> RetainedMetadata.completion(directory, 17));
    // A later failure cannot erase the immutable earlier loaded-code proof.
    RetainedMetadata.startup(directory, 17);
  }

  @Test
  void rejectsDifferentBytesDebugMetadataAndIncompleteMethodCorrespondence() throws IOException {
    var code = certificate();
    byte[] modified = code.code();
    modified[modified.length - 1] ^= 1;
    Files.write(directory.resolve("generated/classes/Case.class"), modified);
    assertThrows(IOException.class, () -> RetainedMetadata.startup(directory, 17));
    Files.write(directory.resolve("generated/classes/Case.class"), code.code());
    var methods = code.methods().subList(1, code.methods().size());
    new CodeEvidence(
            17,
            CodeEvidence.Stage.STARTUP,
            CodeEvidence.Status.VALID,
            0,
            2,
            1,
            1,
            15,
            code.code(),
            methods)
        .write(directory.resolve("traces/code.gcode"));
    assertThrows(IOException.class, () -> RetainedMetadata.startup(directory, 17));
  }

  @Test
  void rejectsDebugOnlyMutationAndMissingLoaderOrPreparationProof() throws IOException {
    var code = certificate();
    byte[] changed = code.code();
    byte[] name = "session".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    boolean renamed = false;
    for (int index = 0; index <= changed.length - name.length; index++) {
      if (java.util.Arrays.equals(
          java.util.Arrays.copyOfRange(changed, index, index + name.length), name)) {
        changed[index + name.length - 1] = 'N';
        renamed = true;
        break;
      }
    }
    assertEquals(true, renamed);
    RetainedLocalMetadata.read(changed, "LCase;");
    Files.write(directory.resolve("generated/classes/Case.class"), changed);
    assertThrows(IOException.class, () -> RetainedMetadata.startup(directory, 17));
    Files.write(directory.resolve("generated/classes/Case.class"), code.code());
    for (int flags : List.of(14, 11, 27)) {
      new CodeEvidence(
              17,
              CodeEvidence.Stage.STARTUP,
              CodeEvidence.Status.VALID,
              0,
              2,
              1,
              1,
              flags,
              code.code(),
              code.methods())
          .write(directory.resolve("traces/code.gcode"));
      assertThrows(IOException.class, () -> RetainedMetadata.startup(directory, 17));
    }
  }

  @Test
  void certifiesExecuteAndMandatoryTerminalMainAsDistinctActivations() throws IOException {
    var code = certificate();
    var bindings = new BindingEvidence(17, 3, 7, 0, 1, List.of());
    frames(code, "execute", FrameEvidence.Role.EXECUTE, 0, 0, 2, 2);
    var capture = RetainedMetadata.capture(directory, snapshot(), List.of(bindings), false);
    assertEquals(FrameEvidence.Role.EXECUTE, capture.participants().get(0).role());
    assertThrows(
        IOException.class,
        () -> RetainedMetadata.capture(directory, snapshot(), List.of(bindings), true));
    frames(code, "main", FrameEvidence.Role.MAIN, 0, 0, 2, 2);
    assertEquals(
        FrameEvidence.Role.MAIN,
        RetainedMetadata.capture(directory, snapshot(), List.of(bindings), true)
            .participants()
            .get(0)
            .role());
    long main =
        code.methods().stream()
            .filter(method -> method.name().equals("main"))
            .findFirst()
            .orElseThrow()
            .token();
    long execute =
        code.methods().stream()
            .filter(method -> method.name().equals("execute"))
            .findFirst()
            .orElseThrow()
            .token();
    var active =
        List.of(new FrameEvidence.Frame(main, 0, 0), new FrameEvidence.Frame(execute, 1, 0));
    new FrameEvidence(
            17,
            3,
            CodeEvidence.Status.VALID,
            0,
            2,
            2,
            List.of(new FrameEvidence.Participant(0, 7, FrameEvidence.Role.MAIN, active, active)))
        .write(directory.resolve("snapshots/3.gframe"));
    assertThrows(
        IOException.class,
        () -> RetainedMetadata.capture(directory, snapshot(), List.of(bindings), true));
    var spurious =
        new BindingEvidence(17, 3, 7, 0, 1, List.of(new BindingEvidence.Binding(1, false)));
    assertThrows(
        IOException.class,
        () -> RetainedMetadata.capture(directory, snapshot(), List.of(spurious), true));
  }

  @Test
  void rejectsChangedEpochMissingBciWrongDepthAndUnstableFrame() throws IOException {
    var code = certificate();
    var bindings = List.of(new BindingEvidence(17, 3, 7, 0, 1, List.of()));
    frames(code, "execute", FrameEvidence.Role.EXECUTE, 0, 0, 2, 3);
    assertThrows(
        IOException.class, () -> RetainedMetadata.capture(directory, snapshot(), bindings, false));
    frames(code, "execute", FrameEvidence.Role.EXECUTE, -1, -1, 2, 2);
    assertThrows(
        IOException.class, () -> RetainedMetadata.capture(directory, snapshot(), bindings, false));
    frames(code, "execute", FrameEvidence.Role.EXECUTE, 0, 1, 2, 2);
    assertThrows(
        IOException.class, () -> RetainedMetadata.capture(directory, snapshot(), bindings, false));
    Files.delete(directory.resolve("snapshots/3.gframe"));
    assertThrows(
        IOException.class, () -> RetainedMetadata.capture(directory, snapshot(), bindings, false));
  }

  @Test
  void rawMethodMetadataCannotContradictOrDuplicateTheCertifiedIdentity() throws IOException {
    var code = certificate();
    frames(code, "execute", FrameEvidence.Role.EXECUTE, 0, 0, 2, 2);
    var method =
        code.methods().stream()
            .filter(candidate -> candidate.name().equals("execute"))
            .findFirst()
            .orElseThrow();
    var valid =
        new HeapSnapshot.MethodInfo(method.token(), "LCase;", method.name(), method.descriptor());
    for (var methods :
        List.of(
            List.of(valid, valid),
            List.of(
                new HeapSnapshot.MethodInfo(
                    method.token(), "LOther;", method.name(), method.descriptor())),
            List.of(new HeapSnapshot.MethodInfo(0, "LOther;", "method", "()V")))) {
      var raw = snapshot();
      var changed =
          new HeapSnapshot(
              raw.run(),
              raw.checkpoint(),
              raw.status(),
              raw.jvmtiError(),
              raw.capabilities(),
              0,
              0,
              0,
              0,
              raw.classes(),
              raw.arrays(),
              methods,
              raw.records());
      assertThrows(
          IOException.class,
          () ->
              RetainedMetadata.capture(
                  directory,
                  changed,
                  List.of(new BindingEvidence(17, 3, 7, 0, 1, List.of())),
                  false));
    }
  }

  @Test
  void rejectsCertifiedHelperFramesInEitherBracketAtOrdinaryAndTerminalCaptures()
      throws IOException {
    var code = certificate();
    long helper =
        code.methods().stream()
            .filter(method -> method.name().equals("helper1"))
            .findFirst()
            .orElseThrow()
            .token();
    var binding = List.of(new BindingEvidence(17, 3, 7, 0, 1, List.of()));
    for (boolean terminal : List.of(false, true)) {
      String roleName = terminal ? "main" : "execute";
      var role = terminal ? FrameEvidence.Role.MAIN : FrameEvidence.Role.EXECUTE;
      long token =
          code.methods().stream()
              .filter(method -> method.name().equals(roleName))
              .findFirst()
              .orElseThrow()
              .token();
      var ordinary = List.of(new FrameEvidence.Frame(token, 0, 0));
      var active =
          List.of(new FrameEvidence.Frame(token, 0, 0), new FrameEvidence.Frame(helper, 1, 0));
      for (boolean before : List.of(false, true)) {
        new FrameEvidence(
                17,
                3,
                CodeEvidence.Status.VALID,
                0,
                2,
                2,
                List.of(
                    new FrameEvidence.Participant(
                        0, 7, role, before ? active : ordinary, before ? ordinary : active)))
            .write(directory.resolve("snapshots/3.gframe"));
        assertThrows(
            IOException.class,
            () -> RetainedMetadata.capture(directory, snapshot(), binding, terminal));
      }
    }
  }

  private CodeEvidence certificate() throws IOException {
    Files.createDirectories(directory.resolve("generated/classes"));
    Files.createDirectories(directory.resolve("traces"));
    Files.createDirectories(directory.resolve("snapshots"));
    Path stubs = directory.resolve("io/connglli/gorrect/runtime");
    Files.createDirectories(stubs);
    Path session = stubs.resolve("TargetSession.java"),
        context = stubs.resolve("WorkerContext.java");
    Files.writeString(
        session, "package io.connglli.gorrect.runtime; public class TargetSession {}");
    Files.writeString(
        context, "package io.connglli.gorrect.runtime; public class WorkerContext {}");
    Path source = directory.resolve("Case.java");
    Files.writeString(
        source,
        """
        import io.connglli.gorrect.runtime.TargetSession;
        import io.connglli.gorrect.runtime.WorkerContext;
        public class Case {
          public static void main(String[] args) { System.out.println(args.length); }
          private static void execute(TargetSession session) { System.out.println(session); }
          private static void helper1(TargetSession session, int callSite) { System.out.println(callSite); }
          private static void worker1(TargetSession session, WorkerContext worker) { System.out.println(worker); }
        }
        """);
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
                "-d",
                directory.resolve("generated/classes").toString(),
                session.toString(),
                context.toString(),
                source.toString()));
    byte[] bytes = Files.readAllBytes(directory.resolve("generated/classes/Case.class"));
    var metadata = RetainedLocalMetadata.read(bytes, "LCase;");
    var methods = new ArrayList<CodeEvidence.Method>();
    for (var method : metadata.methods())
      methods.add(new CodeEvidence.Method(methods.size() + 1L, method.name(), method.descriptor()));
    var code =
        new CodeEvidence(
            17,
            CodeEvidence.Stage.STARTUP,
            CodeEvidence.Status.VALID,
            0,
            2,
            1,
            1,
            15,
            bytes,
            methods);
    code.write(directory.resolve("traces/code.gcode"));
    return code;
  }

  private void terminal(CodeEvidence code, long epoch, CodeEvidence.Status status)
      throws IOException {
    new CodeEvidence(
            17,
            CodeEvidence.Stage.FINAL,
            status,
            0,
            epoch,
            code.classToken(),
            code.loaderToken(),
            code.flags(),
            new byte[0],
            List.of())
        .write(directory.resolve("traces/code-final.gcode"));
  }

  private void frames(
      CodeEvidence code,
      String name,
      FrameEvidence.Role role,
      long before,
      long after,
      long beforeEpoch,
      long afterEpoch)
      throws IOException {
    long token =
        code.methods().stream()
            .filter(method -> method.name().equals(name))
            .findFirst()
            .orElseThrow()
            .token();
    new FrameEvidence(
            17,
            3,
            CodeEvidence.Status.VALID,
            0,
            beforeEpoch,
            afterEpoch,
            List.of(
                new FrameEvidence.Participant(
                    0,
                    7,
                    role,
                    List.of(new FrameEvidence.Frame(token, 0, before)),
                    List.of(new FrameEvidence.Frame(token, 0, after)))))
        .write(directory.resolve("snapshots/3.gframe"));
  }

  private static HeapSnapshot snapshot() {
    return new HeapSnapshot(
        17,
        3,
        HeapSnapshot.Status.COMPLETE,
        0,
        HeapSnapshot.TAGGING,
        0,
        0,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
