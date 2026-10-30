package io.connglli.gorrect.engine.oracle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.connglli.gorrect.engine.model.ObjectId;
import org.junit.jupiter.api.Test;

class PhaseRaceTest {
  private static GraphMachine.Memory access(int index, boolean write, boolean atomic) {
    return new GraphMachine.Memory(
        new GraphMachine.Slot(ObjectId.initial(1), null, false, index), write, atomic);
  }

  @Test
  void laterAtomicAccessCannotHideAnOrdinaryRace() {
    var race = new PhaseRace(new HistoryBudget(HistoryLimits.DEFAULT));
    race.access(1, access(0, true, false));
    race.access(1, access(0, true, true));
    assertThrows(UnsupportedHistoryException.class, () -> race.access(2, access(0, true, true)));
  }

  @Test
  void volatileReadAcquiresAllEarlierWritesToThatVariable() {
    var race = new PhaseRace(new HistoryBudget(HistoryLimits.DEFAULT));
    race.access(1, access(0, true, false));
    race.access(1, access(1, true, true));
    race.access(2, access(1, true, true));
    race.access(3, access(1, false, true));
    assertDoesNotThrow(() -> race.access(3, access(0, false, false)));
  }
}
