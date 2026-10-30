package io.connglli.gorrect.engine.observe;

/** Capture coverage and lifetime policy, selected only through the target profile. */
public enum ObservationProfile {
  /** Fenced GIR lifetimes with named static roots; stack bindings are not projected. */
  NAMED_ROOTS("named-roots-diagnostic", false, true),
  /** Fenced lifetimes with explicit presence and validated physical roots for live bindings. */
  CONTROLLED_ROOTS("roots-diagnostic", true, true),
  /** Future semantic uses determine local obligations; no lifetime fences or array binding. */
  NATURAL_ROOTS("roots-natural", true, false);

  private final String name;
  private final boolean bindings, controlled;

  ObservationProfile(String name, boolean bindings, boolean controlled) {
    this.name = name;
    this.bindings = bindings;
    this.controlled = controlled;
  }

  public String profileName() {
    return name;
  }

  public boolean bindings() {
    return bindings;
  }

  public boolean controlled() {
    return controlled;
  }

  public static ObservationProfile parse(String name) {
    for (var profile : values()) if (profile.name.equals(name)) return profile;
    throw new IllegalArgumentException("Unknown observation profile " + name);
  }
}
