package io.connglli.gorrect.engine.model;

/**
 * A reference object's incarnation, independent of its referent. Initial wrappers use site zero,
 * their owner's initial ID as occurrence, and the slot index. Stores use their instruction site,
 * its next occurrence, and slot -1. Replacing a wrapper never revives the previous incarnation.
 */
public record WrapperId(int worker, int site, long occurrence, int slot)
    implements Comparable<WrapperId> {
  public WrapperId {
    if (worker < 0
        || site < 0
        || occurrence <= 0
        || (site == 0 ? worker != 0 || slot < 0 : slot != -1))
      throw new IllegalArgumentException("Invalid wrapper identity");
  }

  @Override
  public int compareTo(WrapperId other) {
    int order = Integer.compare(worker, other.worker);
    if (order == 0) order = Integer.compare(site, other.site);
    if (order == 0) order = Long.compare(occurrence, other.occurrence);
    return order == 0 ? Integer.compare(slot, other.slot) : order;
  }
}
