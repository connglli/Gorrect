package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.model.Graph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Compares deterministic strong-reference obligations and produces bounded diagnostics. */
public final class ExactVerifier {
  private static final int MAX_DIFFERENCES = 100;

  public enum Verdict {
    SATISFIED,
    VIOLATED,
    INCONCLUSIVE
  }

  /** Immutable verdict and comparison diagnostics. */
  public record Result(Verdict verdict, List<String> differences) {
    public Result {
      differences = List.copyOf(differences);
    }
  }

  private ExactVerifier() {}

  /**
   * Compares required roots and slots, allowing retained garbage. Incomplete observations produce
   * an inconclusive result before comparison.
   *
   * @param complete whether all records required by the checkpoint are available
   * @throws IllegalArgumentException if the expected model's required part is invalid
   */
  public static Result verify(Graph expected, Graph observed, boolean complete) {
    if (!complete) return new Result(Verdict.INCONCLUSIVE, List.of("Incomplete observation"));
    var required = expected.required();
    return compare(required, observed);
  }

  /** Compares all still-accessible referents chosen by a legal reference-history state. */
  public static Result verifyAccessible(Graph expected, Graph observed, boolean complete) {
    if (!complete) return new Result(Verdict.INCONCLUSIVE, List.of("Incomplete observation"));
    return compare(expected.accessible(), observed);
  }

  private static Result compare(Graph required, Graph observed) {
    var differences = new ArrayList<String>();
    bindings("root", required.roots(), observed.roots(), differences);
    bindings("handle", required.handles(), observed.handles(), differences);
    // Exact roots and outgoing slots expose invented reachability while allowing retained garbage.
    for (var id : new TreeSet<>(required.objects().keySet())) {
      if (differences.size() >= MAX_DIFFERENCES) break;
      var wanted = required.objects().get(id);
      var actual = observed.objects().get(id);
      String path = "object " + id;
      if (actual == null) {
        add(differences, path + " is missing");
        continue;
      }
      if (!wanted.shape().equals(actual.shape())) add(differences, path + " has wrong shape");
      slots(path + " reference", wanted.references(), actual.references(), differences);
      slots(path + " data", wanted.data(), actual.data(), differences);
    }
    return new Result(differences.isEmpty() ? Verdict.SATISFIED : Verdict.VIOLATED, differences);
  }

  private static <K extends Comparable<K>, V> void bindings(
      String kind, Map<K, V> expected, Map<K, V> observed, List<String> differences) {
    var keys = new TreeSet<>(expected.keySet());
    keys.addAll(observed.keySet());
    for (var key : keys) {
      if (differences.size() >= MAX_DIFFERENCES) break;
      if (!Objects.equals(expected.get(key), observed.get(key))) {
        add(
            differences,
            kind
                + " "
                + key
                + ": expected "
                + expected.get(key)
                + ", observed "
                + observed.get(key));
      }
    }
  }

  private static void slots(
      String path, List<?> expected, List<?> observed, List<String> differences) {
    if (expected.size() != observed.size()) add(differences, path + " slot count differs");
    for (int index = 0; index < Math.min(expected.size(), observed.size()); index++) {
      if (differences.size() >= MAX_DIFFERENCES) break;
      if (!expected.get(index).equals(observed.get(index))) {
        add(
            differences,
            path
                + "["
                + index
                + "]: expected "
                + expected.get(index)
                + ", observed "
                + observed.get(index));
      }
    }
  }

  private static void add(List<String> differences, String difference) {
    if (differences.size() < MAX_DIFFERENCES) differences.add(difference);
  }
}
