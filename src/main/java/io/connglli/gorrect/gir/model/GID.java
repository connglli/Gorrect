package io.connglli.gorrect.gir.model;

import lombok.NonNull;
import io.connglli.gorrect.utility.GAssert;

/**
 * Represents a globally unique identifier (GID) used in GIR.
 * This class is immutable and comparable.
 */
public class GID implements Comparable<GID> {
  @NonNull
  private final String id;

  public GID(@NonNull String id) {
    GAssert.set(!id.isEmpty(), "GID cannot be empty");
    this.id = id;
  }

  public GID(@NonNull GID other) {
    this.id = other.id;
  }

  public String get() {
    return id;
  }

  @Override
  public int compareTo(@NonNull GID other) {
    return id.compareTo(other.id);
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof GID) && id.equals(((GID) obj).id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return id;
  }
}
