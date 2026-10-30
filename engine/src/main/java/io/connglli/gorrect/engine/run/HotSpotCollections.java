package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.protocol.CollectionEvidence;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import jdk.jfr.consumer.RecordingFile;

/** VM GC events, not pause counts or target labels, supply completed-cycle intervals. */
final class HotSpotCollections {
  private HotSpotCollections() {}

  static CollectionEvidence read(Path file, long run, long pid, long started) throws IOException {
    if (Files.size(file) > 64L * 1024 * 1024)
      throw new IOException("GC recording exceeds byte limit");
    var requests = new ArrayList<CollectionEvidence.Request>();
    var cycles = new ArrayList<CollectionEvidence.Cycle>();
    var ids = new HashSet<String>();
    boolean identity = false;
    try (var recording = new RecordingFile(file)) {
      int count = 0;
      while (recording.hasMoreEvents()) {
        if (++count > ExecutionTrace.MAX_EVENTS)
          throw new IOException("GC recording exceeds event limit");
        var event = recording.readEvent();
        switch (event.getEventType().getName()) {
          case "jdk.JVMInformation" -> {
            if (event.getLong("pid") != pid || event.getLong("jvmStartTime") != started)
              throw new IOException("GC recording belongs to another VM");
            identity = true;
          }
          case CollectionEvidence.REQUEST_EVENT -> {
            if (event.getInt("version") != CollectionEvidence.VERSION
                || event.getLong("run") != run
                || event.getThread() == null)
              throw new IOException("GC request version/run/thread mismatch");
            var request =
                new CollectionEvidence.Request(
                    event.getInt("worker"),
                    event.getInt("operation"),
                    event.getInt("group"),
                    event.getLong("round"),
                    event.getInt("phase"),
                    event.getLong("collection"),
                    event.getThread().getJavaThreadId(),
                    event.getString("control"),
                    event.getBoolean("returned"),
                    event.getStartTime(),
                    event.getEndTime());
            if (request.worker() < 0
                || request.worker() > ExecutionTrace.MAX_WORKERS
                || request.operation() <= 0
                || request.ordinal() <= 0
                || request.thread() <= 0
                || request.phase() < 0
                || request.group() < 0
                || request.round() < 0
                || request.end().isBefore(request.begin())
                || (request.worker() == 0
                    ? request.group() != 0 || request.round() != 0 || request.phase() != 0
                    : request.group() == 0 || request.round() == 0))
              throw new IOException("Invalid GC request identity/interval");
            requests.add(request);
          }
          case "jdk.GarbageCollection" -> {
            var cycle =
                new CollectionEvidence.Cycle(
                    event.getLong("gcId"),
                    event.getString("name"),
                    event.getString("cause"),
                    event.getStartTime(),
                    event.getEndTime());
            if (cycle.id() < 0
                || !cycle.end().isAfter(cycle.begin())
                || !ids.add(cycle.id() + ":" + cycle.name()))
              throw new IOException("Invalid/duplicate VM GC event");
            cycles.add(cycle);
          }
          default -> {
            /* Worker frame events have their own decoder and coverage checks. */
          }
        }
      }
    } catch (RuntimeException malformed) {
      throw new IOException("Malformed GC recording", malformed);
    }
    if (!identity) throw new IOException("GC recording lacks VM identity");
    return new CollectionEvidence(requests, cycles);
  }
}
