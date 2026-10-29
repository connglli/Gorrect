package io.connglli.gorrect.engine.observe;

import io.connglli.gorrect.engine.model.Graph;
import io.connglli.gorrect.engine.model.HeapObject;
import io.connglli.gorrect.engine.model.ObjectId;
import io.connglli.gorrect.engine.model.Shape;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.engine.oracle.ExactVerifier;
import io.connglli.gorrect.protocol.HeapSnapshot;
import io.connglli.gorrect.protocol.SnapshotCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Checks saved captures on the engine JVM, independently of the target fixture's implementation.
 */
public final class ObserverArtifactCheck {
  static final String OWNER = "Lio/connglli/gorrect/runtime/observe/ObserverFixture";
  static final Shape NODE = new Shape("N", Shape.Kind.OBJECT, 2, 1);
  static final Shape LINKS = new Shape("Links", Shape.Kind.REFERENCE_ARRAY, 3, 0);
  static final Shape PAYLOAD = new Shape("Payload", Shape.Kind.DATA_ARRAY, 0, 3);
  static final Shape SMALL = new Shape("Small", Shape.Kind.DATA_ARRAY, 0, 2);
  static final ObservationLayout LAYOUT =
      new ObservationLayout(
          List.of(
              new ObservationLayout.ObjectType(
                  OWNER + "$Node;",
                  NODE,
                  List.of("f0", "f1"),
                  List.of("d0"),
                  "idWorker",
                  "idSite",
                  "idOccurrence")),
          List.of(LINKS, PAYLOAD, SMALL),
          new ObservationLayout.Roots(
              OWNER + "$Roots;",
              Map.of("main", "main", "refs", "refs", "data", "data", "bridge", "bridge")));

  private ObserverArtifactCheck() {}

  public static void main(String[] args) throws IOException {
    for (String directory : args) verify(Path.of(directory));
  }

  public static void verify(Path directory) throws IOException {
    var initial = read(directory, "initial", 1);
    var mutated = read(directory, "mutated", 2);
    verifyGraph(initial, expected(false));
    verifyGraph(mutated, expected(true));
    var limited = read(directory, "limited", 3);
    require(limited.status() == HeapSnapshot.Status.RECORD_LIMIT, "Buffer exhaustion not reported");
    require(
        SnapshotProjector.project(limited, LAYOUT).verify(expected(false)).verdict()
            == ExactVerifier.Verdict.INCONCLUSIVE,
        "Partial capture passed verification");
    var local = read(directory, "local", 4);
    var projection = SnapshotProjector.project(local, LAYOUT);
    require(
        projection.graph().isPresent(),
        "Local capture cannot be projected: " + projection.problems());
    var localGraph = projection.graph().orElseThrow();
    require(localGraph.objects().containsKey(ObjectId.initial(9)), "Local object not observed");
    // A VM can retain the local object without reporting its compiled-frame origin.
    // Record that capability gap; only checks requiring the unmapped binding are inconclusive.
    boolean localRoot =
        local.records().stream()
            .filter(record -> record.kind() == HeapSnapshot.Kind.ROOT)
            .anyMatch(record -> pointsTo(local, record.a(), 9));
    var neededHandle = new Graph(localGraph.objects(), localGraph.roots(), Map.of(1, ref(9)));
    require(
        projection.verify(neededHandle).verdict() == ExactVerifier.Verdict.INCONCLUSIVE,
        "Missing binding coverage treated as complete");
    var released = read(directory, "after-release", 6);
    var releasedProjection = SnapshotProjector.project(released, LAYOUT);
    require(releasedProjection.graph().isPresent(), "Released graph projection failed");
    require(
        !releasedProjection.graph().orElseThrow().objects().containsKey(ObjectId.initial(99)),
        "Released node retained");
    require(
        !releasedProjection.graph().orElseThrow().objects().containsKey(ObjectId.initial(100)),
        "Tagged array retained");
    for (var snapshot : List.of(initial, mutated, local, released)) {
      require(
          snapshot.records().stream()
              .noneMatch(record -> record.kind() == HeapSnapshot.Kind.ROOT && record.b() == 21),
          "Unexpected persistent JNI global root");
    }
    var evidence = new Properties();
    try (var input = Files.newInputStream(directory.resolve("evidence.properties"))) {
      evidence.load(input);
    }
    require(
        "true".equals(evidence.getProperty("retention.node-and-array-cleared")),
        "Missing retention evidence");
    System.out.println(
        "Verified observer artifacts: "
            + directory.getFileName()
            + "; local root origin="
            + (localRoot ? "available" : "unavailable")
            + "; root kinds="
            + local.records().stream()
                .filter(record -> record.kind() == HeapSnapshot.Kind.ROOT)
                .map(record -> Long.toString(record.b()))
                .distinct()
                .toList());
  }

  static HeapSnapshot read(Path directory, String name, long checkpoint) throws IOException {
    return SnapshotCodec.read(
        Files.readAllBytes(directory.resolve(name + ".gsnap")), 42, checkpoint);
  }

  static Graph expected(boolean changed) {
    return new Graph(
        Map.of(
            ObjectId.initial(1),
                new HeapObject(NODE, List.of(ref(2), ref(3)), List.of(changed ? 50_000L : 7L)),
            ObjectId.initial(2), new HeapObject(NODE, List.of(ref(1), ref(4)), List.of(9L)),
            ObjectId.initial(3),
                new HeapObject(LINKS, List.of(ref(changed ? 1 : 2), ref(2), ref(1)), List.of()),
            ObjectId.initial(4),
                new HeapObject(
                    PAYLOAD,
                    List.of(),
                    List.of(Long.MIN_VALUE, changed ? -7L : 42L, Long.MAX_VALUE))),
        Map.of("main", ref(1), "refs", ref(3), "data", ref(4), "bridge", Value.Reference.NULL),
        Map.of());
  }

  private static void verifyGraph(HeapSnapshot snapshot, Graph expected) {
    var projection = SnapshotProjector.project(snapshot, LAYOUT);
    var result = projection.verify(expected);
    require(
        result.verdict() == ExactVerifier.Verdict.SATISFIED,
        "Graph mismatch: " + result.differences());
  }

  private static boolean pointsTo(HeapSnapshot snapshot, long tag, long id) {
    var type =
        snapshot.classes().stream()
            .filter(info -> info.signature().equals(OWNER + "$Node;"))
            .findFirst()
            .orElseThrow();
    int index =
        type.fields().stream()
            .filter(field -> field.name().equals("idOccurrence"))
            .findFirst()
            .orElseThrow()
            .index();
    return snapshot.records().stream()
        .anyMatch(
            record ->
                record.kind() == HeapSnapshot.Kind.PRIMITIVE_FIELD
                    && record.a() == tag
                    && record.b() == index
                    && record.d() == id);
  }

  private static Value.Reference ref(long id) {
    return Value.Reference.strong(ObjectId.initial(id));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
