package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Shared run-bundle setup; semantic expectations remain in each test. */
final class RunTestSupport {
  private RunTestSupport() {}

  static final String CASE =
      """
      gir 1;
      shape N object 2 1;
      shape R refs 3;
      shape D data 2;
      object 1 N [2, 3] [7];
      object 2 R [1, 1, null] [];
      object 3 D [] [-9223372036854775808, 9223372036854775807];
      root main = 1;
      root saved = null;
      program {
        initial: checkpoint;
        repeat 3 {
          read-root n N main;
          read-ref links R n 0;
          read-ref payload D n 1;
          read-data value n 0;
          allocate child N;
          write-data child 0 value;
          write-ref child 0 n;
          write-ref child 1 child;
          write-root saved child;
          write-ref links 2 child;
          write-data payload 1 value;
          write-data n 0 42;
        }
        mutated: checkpoint;
        write-root main null;
        write-root saved null;
        cleared: checkpoint;
      }
      """;

  static String quote(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  static void assertSocketRemoved(Path output) throws IOException {
    var manifest = JsonFiles.read(output.resolve("manifest.json"));
    Path address = Path.of(manifest.get("controlSocket").getAsString());
    assertFalse(Files.exists(address));
    assertFalse(Files.exists(address.getParent()));
  }

  static Path profile(Path directory, int feature) throws IOException {
    var object =
        TestProfiles.create("hotspot", System.getProperty("gorrect.java" + feature), "serial");
    object.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-Xmx128m", "-Xbatch")));
    var limits = new JsonObject();
    limits.addProperty("records", 10000);
    object.add("limits", limits);
    Path profile = directory.resolve("profile.json");
    JsonFiles.write(profile, object);
    return profile;
  }

  static Path input(Path directory, String text) throws IOException {
    Path path = directory.resolve("case.gir");
    Files.writeString(path, text);
    return path;
  }

  static Path copy(Path directory, Path source, String name) throws IOException {
    Path target = directory.resolve(name);
    Artifacts.copyTree(source, target);
    return target;
  }

  static void assertPass(RunReport result) {
    assertEquals(RunReport.Outcome.COMPLETED, result.execution(), result.toString());
    assertEquals(ExactVerifier.Verdict.SATISFIED, result.verdict(), result.toString());
    assertEquals(0, result.exitStatus());
  }

  static void rewriteSnapshot(Path directory, boolean remove) throws IOException {
    Path file = directory.resolve("snapshots/1.gsnap");
    var snapshot = SnapshotCodec.read(Files.readAllBytes(file));
    int index =
        IntStream.range(0, snapshot.records().size())
            .filter(
                i -> {
                  var record = snapshot.records().get(i);
                  return record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD && record.d() == 7;
                })
            .findFirst()
            .orElseThrow();
    var records = new ArrayList<>(snapshot.records());
    var record = records.get(index);
    if (remove) records.remove(index);
    else
      records.set(
          index,
          new HeapSnapshot.Record(
              record.kind(),
              record.a(),
              record.b(),
              record.c(),
              8,
              record.e(),
              record.f(),
              record.g()));
    Files.write(
        file,
        SnapshotCodec.write(
            new HeapSnapshot(
                snapshot.run(),
                snapshot.checkpoint(),
                snapshot.status(),
                snapshot.jvmtiError(),
                snapshot.capabilities(),
                snapshot.gcStartsBefore(),
                snapshot.gcFinishesBefore(),
                snapshot.gcStartsAfter(),
                snapshot.gcFinishesAfter(),
                snapshot.classes(),
                snapshot.arrays(),
                snapshot.methods(),
                records)));
  }
}
