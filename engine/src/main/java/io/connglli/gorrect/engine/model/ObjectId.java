package io.connglli.gorrect.engine.model;

/**
 * Case-local identity independent of addresses and allocation interleaving. Site zero identifies an
 * initial object; other sites identify allocation instructions.
 */
public record ObjectId(int worker, int site, long occurrence) implements Comparable<ObjectId> {
  public ObjectId {
    if (worker < 0 || site < 0 || occurrence <= 0 || (site == 0 && worker != 0)) {
      throw new IllegalArgumentException("Invalid object identity");
    }
  }

  /** Creates an identity from a positive initial-object ID. */
  public static ObjectId initial(long id) {
    return new ObjectId(0, 0, id);
  }

  @Override
  public int compareTo(ObjectId other) {
    int order = Integer.compare(worker, other.worker);
    if (order == 0) order = Integer.compare(site, other.site);
    return order == 0 ? Long.compare(occurrence, other.occurrence) : order;
  }

  @Override
  public String toString() {
    return worker + ":" + site + ":" + occurrence;
  }
}
