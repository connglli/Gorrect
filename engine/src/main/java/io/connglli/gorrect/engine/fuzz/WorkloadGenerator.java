package io.connglli.gorrect.engine.fuzz;

import io.connglli.gorrect.engine.gir.GirParser;
import io.connglli.gorrect.engine.gir.GirProgram;
import io.connglli.gorrect.engine.oracle.Oracle;
import java.util.Random;
import java.util.Set;

/** Seed-local generation: rejecting a case cannot change another case's model or random state. */
public final class WorkloadGenerator {
  public static final String VERSION = "disjoint-v1";
  public static final Set<String> WORKLOADS = Set.of("concurrent", "concurrent-jit");

  private WorkloadGenerator() {}

  public static GirProgram generate(long seed, String workload, int workers) {
    return generate(seed, workload, workers, 2);
  }

  public static GirProgram generate(long seed, String workload, int workers, int rounds) {
    validate(workload, workers, rounds);
    var random = new Random(seed);
    int data = 1 + random.nextInt(2);
    int iterations = Math.max(1, (1800 + random.nextInt(200)) / (workers * rounds));
    if ((long) workers * rounds * 200 <= 8000) iterations = Math.max(200, iterations);
    var source =
        new StringBuilder("gir 1;\nrequire concurrent-overlap;\nrequire gc-during-workers;\n");
    if (workload.equals("concurrent-jit")) source.append("require compiled-execution;\n");
    source.append("shape N object 2 ").append(data).append(";\n");
    long anchor = workers + 1L;
    for (int worker = 1; worker <= workers; worker++) {
      source
          .append("object ")
          .append(worker)
          .append(" N [null, ")
          .append(anchor)
          .append("] [")
          .append(random.nextLong());
      if (data == 2) source.append(", ").append(random.nextLong());
      source.append("];\nroot root_").append(worker).append(" = ").append(worker).append(";\n");
    }
    source
        .append("object ")
        .append(anchor)
        .append(" N [")
        .append(anchor)
        .append(", null] [")
        .append(random.nextLong());
    if (data == 2) source.append(", ").append(random.nextLong());
    source
        .append("];\nroot shared = ")
        .append(anchor)
        .append(";\nprogram {\n repeat ")
        .append(rounds)
        .append(" {\n  parallel {\n");
    for (int worker = 1; worker <= workers; worker++) {
      source
          .append("worker w_")
          .append(worker)
          .append(" {\n")
          .append(" read-root owner N root_")
          .append(worker)
          .append(";\n")
          .append(" write-root root_")
          .append(worker)
          .append(" null;\n")
          .append(" collect;\n read-ref shared N owner 1;\n read-data sentinel shared 0;\n")
          .append(" repeat ")
          .append(iterations)
          .append(" {\n")
          .append(
              "  allocate fresh N;\n  write-ref fresh 0 owner;\n  write-data fresh 0 sentinel;\n")
          .append(
              "  write-ref owner 0 fresh;\n"
                  + "  read-data echoed fresh 0;\n"
                  + "  write-data owner 0 echoed;\n"
                  + " }\n")
          .append(" barrier;\n write-root root_")
          .append(worker)
          .append(" owner;\n }");
    }
    source.append("\n  }\n }\n final: checkpoint;\n}\n");
    var program = GirParser.parse(source.toString());
    // Accept only complete cases whose concrete accesses and model budgets have been checked.
    Oracle.interpret(program);
    return program;
  }

  static void validate(String workload, int workers, int rounds) {
    if (!WORKLOADS.contains(workload) || workers < 2 || workers > 16)
      throw new IllegalArgumentException(
          "Expected concurrent or concurrent-jit workload and 2..16 workers");
    if (rounds < 1 || (long) workers * rounds > 4000)
      throw new IllegalArgumentException("Warm-up rounds exceed the generator's model budget");
  }
}
