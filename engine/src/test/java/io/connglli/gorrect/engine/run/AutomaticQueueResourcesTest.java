package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.observe.ObservationLayout;
import io.connglli.gorrect.engine.observe.SnapshotProjector;
import io.connglli.gorrect.protocol.QueuePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AutomaticQueueResourcesTest {
  @Test
  void rejectedGlobalContextNeverReadsQueueOrConsistencyEvidence() {
    var layout =
        new ObservationLayout(
            List.of(),
            List.of(),
            new ObservationLayout.Roots("LCase$Roots;", Map.of()),
            List.of(),
            List.of(),
            ObservationLayout.REQUIRED,
            "jvmti",
            new QueuePlan(List.of(new QueuePlan.Resource(1, "q", "W")), true));
    var rejected = new SnapshotProjector.Projection(Optional.empty(), List.of("bad global cut"));
    assertSame(
        rejected,
        QueueResources.project(Path.of("unavailable"), null, layout, "hotspot", rejected));
  }

  @Test
  void sourceSelectionRequiresTheAutomaticPlanFlag() throws IOException {
    var program =
        GirParser.parse(
            "gir 1; shape W weak; queue q W; root permanent = null; program { allocate-ref target W"
                + " null; allocate-ref r W target q; write-root permanent r; }");
    var layout = io.connglli.gorrect.engine.emit.JavaEmitter.emit(program).layout();
    QueueResources.source(program, layout);
    var wrong =
        new ObservationLayout(
            layout.objects(),
            layout.arrays(),
            layout.roots(),
            layout.references(),
            layout.bindings(),
            layout.bindingSelection(),
            layout.bindingMetadata(),
            new QueuePlan(layout.queuePlan().resources()));
    assertThrows(IOException.class, () -> QueueResources.source(program, wrong));
  }
}
