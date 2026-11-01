package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.ArrayCopyProgram;
import io.connglli.gorrect.engine.gir.CloneProgram;
import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.HistoryProgram;
import io.connglli.gorrect.engine.gir.IdentityHashProgram;
import io.connglli.gorrect.engine.gir.Instruction;
import io.connglli.gorrect.engine.gir.MemoryMode;
import io.connglli.gorrect.engine.gir.MonitorProgram;
import io.connglli.gorrect.engine.gir.ReferenceProgram;
import io.connglli.gorrect.engine.model.Value;
import io.connglli.gorrect.protocol.ExecutionTrace;
import java.util.ArrayList;
import java.util.List;

/** Exact interpreter for sequential and conflict-free parallel GIR. Each call owns its state. */
public final class Oracle {
  private Oracle() {}

  /** Runs {@link #interpret(GirProgram, OracleLimits)} with {@link OracleLimits#DEFAULT}. */
  public static OracleResult interpret(GirProgram program) {
    return interpret(program, OracleLimits.DEFAULT);
  }

  /**
   * Derives checkpoint obligations and the final graph. The outer scope's bindings are released
   * before deriving the final graph.
   *
   * @throws GirException if static contracts or an executed precondition fail
   * @throws OracleLimitException if a checking budget is exhausted
   */
  public static OracleResult interpret(GirProgram program, OracleLimits limits) {
    return interpret(program, limits, true);
  }

  /** Package-local differential baseline; no runtime, profile or CLI switch is exposed. */
  static OracleResult interpret(GirProgram program, OracleLimits limits, boolean retirement) {
    GirValidator.validate(program);
    var unsupported = CloneProgram.unsupported(program);
    if (!unsupported.isEmpty())
      throw new UnsupportedHistoryException(String.join("; ", unsupported));
    var monitors = MonitorProgram.unsupported(program);
    if (!monitors.isEmpty()) throw new UnsupportedHistoryException(String.join("; ", monitors));
    var copies = ArrayCopyProgram.unsupported(program);
    if (!copies.isEmpty()) throw new UnsupportedHistoryException(String.join("; ", copies));
    var hashSupport = IdentityHashProgram.unsupported(program);
    if (!hashSupport.isEmpty())
      throw new UnsupportedHistoryException(String.join("; ", hashSupport));
    if (HistoryProgram.usesConcurrentHistory(program) || ReferenceProgram.usesReferences(program))
      throw new GirException("Conditional references require a recorded history");
    var machine = new GraphMachine(program, limits, retirement && retirementEligible(program));
    execute(program.body(), machine);
    machine.leaveScope();
    return new OracleResult(
        machine.snapshot(),
        machine.checkpoints(),
        machine.steps(),
        machine.events(),
        machine.identityHashes());
  }

  /**
   * Positive whole-source admission: future mechanisms remain unoptimized until their possible
   * reference recovery paths have been reviewed. Ineligibility changes no support decision.
   */
  static boolean retirementEligible(GirProgram program) {
    return program.queues().isEmpty()
        && program.shapes().stream()
            .allMatch(
                shape ->
                    switch (shape.kind()) {
                      case OBJECT, REFERENCE_ARRAY, DATA_ARRAY -> true;
                      default -> false;
                    })
        && program.objects().stream()
            .flatMap(object -> object.references().stream())
            .allMatch(value -> value.kind() == Value.ReferenceKind.STRONG)
        && program.roots().stream()
            .allMatch(root -> root.value().kind() == Value.ReferenceKind.STRONG)
        && program.instructions().allMatch(Oracle::retirementEligible);
  }

  private static boolean retirementEligible(Instruction instruction) {
    return switch (instruction) {
      case Instruction.Allocate ignored -> true;
      case Instruction.Clone ignored -> true;
      case Instruction.CopyArray ignored -> true;
      case Instruction.IdentityHash ignored -> true;
      case Instruction.SameObject ignored -> true;
      case Instruction.Release ignored -> true;
      case Instruction.Checkpoint ignored -> true;
      case Instruction.Collect ignored -> true;
      case Instruction.Scope ignored -> true;
      case Instruction.Repeat ignored -> true;
      case Instruction.IfPresent ignored -> true;
      case Instruction.IfNonzero ignored -> true;
      case Instruction.ReadRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteRoot op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.ReadReference op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteReference op ->
          op.mode() == MemoryMode.PLAIN && op.kind() == Value.ReferenceKind.STRONG;
      case Instruction.ReadData op -> op.mode() == MemoryMode.PLAIN;
      case Instruction.WriteData op -> op.mode() == MemoryMode.PLAIN;
      default -> false;
    };
  }

  /** Executes validated instructions; failed child blocks leave the caller scope current. */
  static void execute(List<Instruction> instructions, GraphMachine machine) {
    for (var instruction : instructions) {
      switch (instruction) {
        case Instruction.IfPresent present -> {
          boolean value = machine.present(present.name());
          machine.marker(present.id(), ExecutionTrace.Kind.BRANCH, value ? 1 : 0);
          if (value) scoped(present.body(), machine);
        }
        case Instruction.IfNonzero branch -> {
          boolean value = machine.nonzero(branch.condition());
          machine.marker(branch.id(), ExecutionTrace.Kind.BRANCH, value ? 1 : 0);
          scoped(value ? branch.thenBody() : branch.elseBody(), machine);
        }
        case Instruction.Acquire ignored ->
            throw new GirException("Acquisition requires a recorded history");
        case Instruction.Parallel parallel -> parallel(parallel, machine);
        case Instruction.Operation operation -> machine.apply(operation);
        case Instruction.Monitor monitor -> {
          machine.enterMonitor(monitor);
          scoped(monitor.body(), machine);
          machine.marker(monitor.id(), ExecutionTrace.Kind.OPERATION, 1);
        }
        case Instruction.Scope scope -> {
          machine.step(scope.id(), false);
          scoped(scope.body(), machine);
        }
        case Instruction.Repeat repeat -> {
          machine.step(repeat.id(), false);
          for (long iteration = 0; iteration < repeat.count(); iteration++) {
            // Empty bodies must still consume budget to bound loop execution.
            machine.step(repeat.id(), true);
            scoped(repeat.body(), machine);
          }
        }
      }
    }
  }

  private static void parallel(Instruction.Parallel parallel, GraphMachine machine) {
    machine.step(parallel.id(), false);
    long round = machine.visit(parallel.id());
    var caller = machine.context();
    var contexts =
        parallel.workers().stream()
            .map(worker -> machine.workerContext(worker.id(), parallel.id(), round))
            .toList();
    var phases = parallel.workers().stream().map(worker -> phases(worker.body())).toList();
    try {
      for (int phase = 0; phase < phases.get(0).size(); phase++) {
        machine.beginPhase();
        for (int index = 0; index < parallel.workers().size(); index++) {
          machine.activate(contexts.get(index), phase);
          int marker = phase == 0 ? parallel.id() : phases.get(index).get(phase - 1).getLast().id();
          machine.marker(
              marker,
              phase == 0 ? ExecutionTrace.Kind.WORKER_START : ExecutionTrace.Kind.PHASE_START,
              0);
          execute(phases.get(index).get(phase), machine);
          if (phase == phases.get(index).size() - 1) {
            machine.leaveScope();
            machine.marker(parallel.id(), ExecutionTrace.Kind.WORKER_END, 0);
          }
        }
        machine.checkPhase();
      }
    } finally {
      machine.activate(caller, caller.phase());
    }
    for (var worker : parallel.workers())
      machine.marker(parallel.id(), ExecutionTrace.Kind.JOIN, worker.id());
  }

  private static List<List<Instruction>> phases(List<Instruction> body) {
    var result = new ArrayList<List<Instruction>>();
    var current = new ArrayList<Instruction>();
    for (var instruction : body) {
      current.add(instruction);
      if (instruction instanceof Instruction.Barrier) {
        result.add(List.copyOf(current));
        current.clear();
      }
    }
    result.add(List.copyOf(current));
    return result;
  }

  private static void scoped(List<Instruction> body, GraphMachine machine) {
    machine.enterScope();
    try {
      execute(body, machine);
    } finally {
      machine.leaveScope();
    }
  }
}
