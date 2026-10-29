package io.connglli.gorrect.engine.oracle;

import io.connglli.gorrect.engine.gir.GirException;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.gir.GirValidator;
import io.connglli.gorrect.engine.gir.Instruction;
import java.util.List;

/** Deterministic interpreter for GIR 1. Each invocation owns its execution state. */
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
    GirValidator.validate(program);
    var machine = new GraphMachine(program, limits);
    execute(program.body(), machine);
    machine.leaveScope();
    return new OracleResult(
        machine.snapshot(), machine.checkpoints(), machine.steps(), machine.events());
  }

  private static void execute(List<Instruction> instructions, GraphMachine machine) {
    for (var instruction : instructions) {
      switch (instruction) {
        case Instruction.Operation operation -> machine.apply(operation);
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

  private static void scoped(List<Instruction> body, GraphMachine machine) {
    machine.enterScope();
    try {
      execute(body, machine);
    } finally {
      machine.leaveScope();
    }
  }
}
