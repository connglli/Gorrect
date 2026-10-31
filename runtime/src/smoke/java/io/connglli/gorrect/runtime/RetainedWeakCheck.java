package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A rejected custom Case definition must not be retained by native method/class metadata. */
public final class RetainedWeakCheck {
  private RetainedWeakCheck() {}

  private static final class Loader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass("Case", bytes, 0, bytes.length);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected library and output");
    System.load(args[0]);
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    var references = new AtomicReference<List<WeakReference<?>>>();
    var problem = new AtomicReference<Throwable>();
    var owner =
        new Thread(
            () -> {
              try (var input = RetainedWeakCheck.class.getResourceAsStream("/Case.class")) {
                if (input == null) throw new AssertionError("Missing Case bytes");
                var loader = new Loader();
                Class<?> type = loader.define(input.readAllBytes());
                type.getDeclaredMethods();
                byte[] bytes = NativeObserver.bindCode(17, type);
                Files.write(output.resolve("code.gcode"), bytes);
                var code = CodeEvidence.read(bytes, 17, CodeEvidence.Stage.STARTUP);
                if (code.status() == CodeEvidence.Status.VALID
                    || (code.flags() & CodeEvidence.SYSTEM_LOADER) != 0)
                  throw new AssertionError("Custom Case loader was admitted");
                references.set(List.of(new WeakReference<>(loader), new WeakReference<>(type)));
              } catch (Throwable failure) {
                problem.set(failure);
              }
            });
    owner.start();
    owner.join();
    if (problem.get() != null)
      throw new AssertionError("Custom loader setup failed", problem.get());
    boolean cleared = false;
    for (int attempt = 0; attempt < 30; attempt++) {
      System.gc();
      if (references.get().stream().allMatch(reference -> reference.refersTo(null))) {
        cleared = true;
        break;
      }
      Thread.sleep(10);
    }
    if (!cleared) throw new AssertionError("Native certificate retained a custom class or loader");
    byte[] bytes = NativeObserver.codeStatus(17, true);
    Files.write(output.resolve("code-final.gcode"), bytes);
    if (CodeEvidence.read(bytes, 17, CodeEvidence.Stage.FINAL).status()
        == CodeEvidence.Status.VALID)
      throw new AssertionError("Cleared rejected metadata acquired valid completion");
  }
}
