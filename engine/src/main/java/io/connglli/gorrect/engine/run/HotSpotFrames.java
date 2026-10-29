package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.protocol.ExecutionEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import jdk.jfr.consumer.RecordingFile;

/** Decodes VM-reported frames; event fields alone cannot establish compiled execution. */
final class HotSpotFrames {
  private static final String DESCRIPTOR =
      "(Lio/connglli/gorrect/runtime/TargetSession;Lio/connglli/gorrect/runtime/WorkerContext;)V";

  private record Identity(
      int worker, int group, long round, int phase, int point, long collection) {}

  private HotSpotFrames() {}

  static Set<ExecutionEvidence.Frame> read(Path path, long run) throws IOException {
    if (Files.size(path) > 64L * 1024 * 1024)
      throw new IOException("JFR evidence exceeds byte limit");
    var frames = new HashSet<ExecutionEvidence.Frame>();
    var identities = new HashSet<Identity>();
    try (var recording = new RecordingFile(path)) {
      int count = 0;
      while (recording.hasMoreEvents()) {
        var event = recording.readEvent();
        if (!event.getEventType().getName().equals("gorrect.WorkerExecution")) continue;
        if (++count > 1_000_000) throw new IOException("JFR witness count exceeds limit");
        if (event.getInt("version") != ExecutionEvidence.FRAME_VERSION
            || event.getLong("run") != run)
          throw new IOException("JFR witness version or run mismatch");
        int worker = event.getInt("worker"),
            group = event.getInt("group"),
            phase = event.getInt("phase"),
            point = event.getInt("point");
        long round = event.getLong("round"), collection = event.getLong("collection");
        if (worker < 1
            || worker > 64
            || group <= 0
            || round <= 0
            || phase < 0
            || point < ExecutionEvidence.ENTER
            || point > ExecutionEvidence.AFTER_COLLECTION
            || collection < 0
            || ((point < ExecutionEvidence.BEFORE_COLLECTION) != (collection == 0))
            || event.getThread() == null) throw new IOException("Invalid JFR witness identity");
        if (!identities.add(new Identity(worker, group, round, phase, point, collection)))
          throw new IOException("Duplicate JFR witness identity");
        boolean compiled = false;
        if (event.getStackTrace() != null) {
          for (var frame : event.getStackTrace().getFrames()) {
            var method = frame.getMethod();
            if (method.getType().getName().equals("Case")
                && method.getName().equals(ExecutionEvidence.workerMethod(worker))
                && method.getDescriptor().equals(DESCRIPTOR)) {
              compiled =
                  "JIT compiled".equals(frame.getType()) || "Inlined".equals(frame.getType());
              break;
            }
          }
        }
        frames.add(
            new ExecutionEvidence.Frame(
                worker,
                group,
                round,
                phase,
                point,
                collection,
                event.getThread().getJavaThreadId(),
                compiled));
      }
    } catch (RuntimeException failure) {
      throw new IOException("Malformed JFR witness", failure);
    }
    return Set.copyOf(frames);
  }
}
