package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.connglli.gorrect.protocol.NativeRootEvidence;
import io.connglli.gorrect.protocol.NativeRootPlan;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeRootTrackerTest {
  private static NativeRootEvidence.Slot held(int slot, long generation, long handle, long tag) {
    return new NativeRootEvidence.Slot(slot, generation, handle, 2, tag);
  }

  private static NativeRootEvidence.Slot empty(int slot, long generation) {
    return new NativeRootEvidence.Slot(slot, generation, 0, 0, 0);
  }

  private static NativeRootEvidence capture(
      long checkpoint, boolean cleanup, NativeRootEvidence.Slot... slots) {
    return evidence(checkpoint, cleanup ? 0 : 3, cleanup, slots);
  }

  private static NativeRootEvidence evidence(
      long checkpoint, int site, boolean cleanup, NativeRootEvidence.Slot... slots) {
    var rows = List.of(slots);
    return new NativeRootEvidence(
        1, checkpoint, site, 1, cleanup, NativeRootPlan.MAX_GENERATIONS, 0, 0, 0, 0, rows, rows);
  }

  @Test
  void emptyDeclarationsDoNotClaimAnExercisedAcquisition() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, empty(0, 0), empty(1, 0)));
    assertFalse(tracker.exercised());
    tracker.accept(capture(2, true, empty(0, 0), empty(1, 0)));
    assertFalse(tracker.exercised());
  }

  @Test
  void deletionAndFreshAcquisitionCanReuseTheSameHandleBits() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, held(0, 1, 123, 7)));
    tracker.accept(capture(2, false, empty(0, 1)));
    tracker.accept(capture(3, false, held(0, 2, 123, 8)));
    tracker.accept(capture(4, true, empty(0, 2)));
    assertTrue(tracker.exercised());
  }

  @Test
  void aliasedTargetsAndEqualOpaqueHandlesNeedDistinctAcquisitions() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, held(0, 1, 123, 7), held(1, 2, 123, 7)));
    tracker.accept(capture(2, true, empty(0, 1), empty(1, 2)));
    assertTrue(tracker.exercised());
  }

  @Test
  void rejectedChronologyDoesNotConsumeTheCheckpoint() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, held(0, 1, 123, 7)));
    for (var changed : List.of(held(0, 1, 456, 7), held(0, 1, 123, 8), empty(0, 0)))
      assertThrows(IOException.class, () -> tracker.accept(capture(2, false, changed)));
    assertDoesNotThrow(() -> tracker.accept(capture(2, false, held(0, 1, 123, 7))));
  }

  @Test
  void aDeletedGenerationCannotBeReactivatedWithoutAcquisition() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, empty(0, 1)));
    assertThrows(IOException.class, () -> tracker.accept(capture(2, false, held(0, 1, 123, 7))));
    tracker.accept(capture(2, false, held(0, 2, 123, 7)));
  }

  @Test
  void freshPerSlotGenerationsMustAlsoBeFreshAcrossSlots() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, held(0, 5, 123, 7), empty(1, 0)));
    assertThrows(
        IOException.class,
        () -> tracker.accept(capture(2, false, held(0, 5, 123, 7), held(1, 4, 456, 8))));
    tracker.accept(capture(2, false, held(0, 5, 123, 7), held(1, 6, 456, 8)));
  }

  @Test
  void cleanupCannotAcquireNewGlobalsOrReopenTheRun() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(evidence(1, 0, false, held(0, 1, 123, 7)));
    assertThrows(IOException.class, () -> tracker.accept(capture(2, true, empty(0, 2))));
    tracker.accept(capture(2, true, empty(0, 1)));
    assertThrows(IOException.class, () -> tracker.accept(capture(3, false, empty(0, 1))));
  }

  @Test
  void failedPrefixesMayAcquireBeforeTheirCleanup() throws Exception {
    var tracker = new NativeRoots.Tracker();
    tracker.accept(capture(1, false, held(0, 1, 123, 7)));
    tracker.accept(capture(2, true, empty(0, 2)));
    assertTrue(tracker.exercised());
  }

  @Test
  void aRepeatedOrCrossRunCertificateCannotAdvanceState() throws Exception {
    var tracker = new NativeRoots.Tracker();
    var first = capture(1, false, held(0, 1, 123, 7));
    tracker.accept(first);
    assertThrows(IOException.class, () -> tracker.accept(first));
    var rows = List.of(held(0, 1, 123, 7));
    var crossRun =
        new NativeRootEvidence(
            2, 2, 3, 1, false, NativeRootPlan.MAX_GENERATIONS, 0, 0, 0, 0, rows, rows);
    assertThrows(IOException.class, () -> tracker.accept(crossRun));
    tracker.accept(capture(2, true, empty(0, 1)));
  }
}
