package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Actual startup image/API/monitor-setup facts; this fixture does not certify a captured graph. */
public final class QueueAutomaticFacts {
  private QueueAutomaticFacts() {}

  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    NativeObserver.configure(
        List.of(
            new NativeObserver.Registration(
                Class.forName("Case$W"), HeapSnapshot.ClassKind.WEAK_REFERENCE),
            new NativeObserver.Registration(
                Class.forName("Case$AutomaticRoots"), HeapSnapshot.ClassKind.ROOTS)));
    NativeObserver.configureQueues(
        Class.forName("Case$QueueRoots"), Class.forName("Case$Queue"), true);
    var facts = VmFacts.read();
    facts.putAll(QueueQualification.exercise(true));
    Path output = Path.of(args[1]);
    Files.createDirectories(output.getParent());
    try (var stream = Files.newOutputStream(output)) {
      facts.store(stream, "Actual selected automatic queue startup facts");
    }
  }
}
