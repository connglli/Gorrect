package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import io.connglli.gorrect.protocol.StackEvidence;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenJ9DriverTest {
  @TempDir Path directory;

  @Test
  void policyDisambiguatesCollectorsWithIdenticalBeans() {
    var facts = new Properties();
    facts.setProperty("collector.policy", "-Xgcpolicy:optavgpause");
    facts.setProperty("collectors", "global");
    assertTrue(OpenJ9Driver.selected("optavgpause", facts));
    assertFalse(OpenJ9Driver.selected("optthruput", facts));
    assertFalse(OpenJ9Driver.selected("metronome", facts));
    facts.remove("collector.policy");
    assertFalse(OpenJ9Driver.selected("optavgpause", facts));
    facts.setProperty("collector.policy", "-Xgcpolicy:gencon");
    assertFalse(OpenJ9Driver.selected("gencon", facts));
    facts.setProperty("collectors", "scavenge,global");
    assertTrue(OpenJ9Driver.selected("gencon", facts));
  }

  @Test
  void rawFramesRequireExactMethodsAndVmProvenance() throws IOException {
    Path path = directory.resolve("frames");
    var target = new Properties();
    target.setProperty("process.pid", "42");
    target.setProperty("vm.start", "1000");
    String descriptor =
        "(Lio/connglli/gorrect/runtime/TargetSession;Lio/connglli/gorrect/runtime/WorkerContext;)V";
    for (var frame :
        List.of(
            new StackEvidence.Frame("LCase;", "worker1", descriptor, 0),
            new StackEvidence.Frame("LOther;", "worker1", descriptor, 1),
            new StackEvidence.Frame("LCase;", "worker1", "(J)V", 1),
            new StackEvidence.Frame("LCase;", "worker1", descriptor, 1),
            new StackEvidence.Frame("LCase;", "worker1", descriptor, 2))) {
      var witness =
          new StackEvidence.Witness(1, 2, 1, 0, ExecutionEvidence.ENTER, 0, 7, List.of(frame));
      new StackEvidence(1, 42, 1000, List.of(witness)).write(path);
      assertEquals(
          frame.owner().equals("LCase;")
              && frame.descriptor().equals(descriptor)
              && frame.kind() > 0,
          OpenJ9Driver.compiledFrames(path, 1, target).iterator().next().compiled());
      assertThrows(IOException.class, () -> OpenJ9Driver.compiledFrames(path, 2, target));
      var wrong = new Properties();
      wrong.putAll(target);
      wrong.setProperty("process.pid", "43");
      assertThrows(IOException.class, () -> OpenJ9Driver.compiledFrames(path, 1, wrong));
      new StackEvidence(1, 42, 1000, List.of(witness, witness)).write(path);
      assertThrows(IOException.class, () -> OpenJ9Driver.compiledFrames(path, 1, target));
    }
  }

  @Test
  void rejectsDamagedRawEvidenceAndInvalidFrameKinds() throws IOException {
    Path path = directory.resolve("frames");
    var witness =
        new StackEvidence.Witness(
            1, 2, 1, 0, 0, 0, 7, List.of(new StackEvidence.Frame("LCase;", "worker1", "()V", 3)));
    new StackEvidence(1, 42, 1000, List.of(witness)).write(path);
    assertThrows(IOException.class, () -> StackEvidence.read(path, 1, 42, 1000));
    new StackEvidence(1, 42, 1000, List.of()).write(path);
    var bytes = Files.readAllBytes(path);
    Files.write(path, Arrays.copyOf(bytes, bytes.length - 1));
    assertThrows(IOException.class, () -> StackEvidence.read(path, 1, 42, 1000));
    var badCount = bytes.clone();
    ByteBuffer.wrap(badCount).putInt(32, Integer.MAX_VALUE);
    Files.write(path, badCount);
    assertThrows(IOException.class, () -> StackEvidence.read(path, 1, 42, 1000));
  }
}
