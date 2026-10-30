package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.BindingEvidence;
import java.util.ArrayList;
import java.util.List;

/** Private fixed primitive storage; only its owner appends before publishing a paused cut. */
final class BindingRecorder {
  private final int[] declarations;
  private final boolean[] presence;
  private int count;

  BindingRecorder(int capacity) {
    declarations = new int[capacity];
    presence = new boolean[capacity];
  }

  void record(int declaration, boolean present) {
    if (declaration <= 0 || count == declarations.length)
      throw new IllegalStateException("Binding evidence capacity exhausted or invalid declaration");
    declarations[count] = declaration;
    presence[count++] = present;
  }

  List<BindingEvidence.Binding> snapshot() {
    var bindings = new ArrayList<BindingEvidence.Binding>(count);
    for (int index = 0; index < count; index++)
      bindings.add(new BindingEvidence.Binding(declarations[index], presence[index]));
    return bindings;
  }

  void clear() {
    count = 0;
  }
}
