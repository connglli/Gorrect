# Implementation architecture

This document defines the components, language baselines, execution boundaries, and data flow for Gorrect. [design.md](./design.md) defines the methodology; [gir.md](./gir.md) specifies the implemented language.

## Architecture

The engine runs in its own JVM and launches a separate target JVM for each test run. The engine derives expectations from GIR; the target executes generated Java and supplies observations.

```text
+----------------------------------------+                  +----------------------------------------------+
| engine JVM (Java 21)                   |                  | target JVM (JDK 17+)                         |
|                                        |                  |                                              |
| GIR -> Java emitter -> javac           |--- classes ----> | Generated Java methods and workers           |
|  |                                     |                  |                  |                           |
|  v                                     |                  |                  v                           |
| Oracle                                 |                  | Runtime helpers (Java 17)                    |
|  |                                     |                  |        |                    |                |
|  v                                     |                  |        v                    v                |
| Verifier <--- run artifacts            |<---- files ----- | VM control bridge     Native observer        |
|                                        |                  |                       (C++17, JNI/JVMTI)     |
| JVM driver -> Runner                   |<-- protocol ---> | Runtime control endpoint                     |
+----------------------------------------+                  +----------------------------------------------+
```

The runner launches the target with the compiled case and manages its lifetime. The runtime and observer produce trace, snapshot, and evidence files that the verifier reads. The protocol is a shared library and data contract, not another process.

## Languages and build units

| Unit | Language | Responsibility |
| --- | --- | --- |
| `engine` | Java 21 | GIR tools, oracle, generation, Java emission, JVM drivers, run management, reduction, and command line |
| `runtime` | Java 17 | Target-side workers, recording, checkpoints, and VM control bridges |
| `protocol` | Java 17 | Shared commands, records, schemas, and Java codecs |
| `native` | C++17 | JNI entry points and JVMTI observation inside the target JVM |

Gradle builds the Java units and invokes CMake for the native library. The wrapper pins Gradle; compiler language levels and dependency versions are explicit in the build. The runtime, protocol, and ordinary generated programs compile with `--release 17` to exclude newer Java APIs. Java and C++ compiler warnings fail the build.

The engine and runtime depend on the protocol. The runtime has no dependency on the engine, GIR interpreter, or oracle. The native library implements the observation record format defined by the protocol.

The engine uses a stable JDK independently of the selected target. Target-specific support libraries must match the target profile's compatibility requirements. Native libraries are built for the target operating system and architecture.

## Engine

The engine groups its subsystems into Java packages. Each package has one responsibility; these subsystems share a process rather than communicating as separate services.

### GIR frontend and model

The GIR frontend parses, formats, and validates the [versioned language](./gir.md). Its immutable syntax tree contains graph declarations, typed bindings, structured control flow, and stable operation identities.

Validation checks object shapes, slot accesses, value types, scope, and synchronization structure before execution. The generator evaluates a candidate's preconditions and state change before accepting it. Rejecting a candidate leaves the graph and generation bookkeeping unchanged.

The host model stores [logical identities](./gir.md#instructions), slots, values, and root lifetimes. Its immutable snapshots describe the target graph. No engine object is used as a target workload object, and semantic state belongs to one case or verification run.

### Oracle and verifier

The oracle interprets validated GIR and derives the permitted behavior. The verifier compares that behavior with trace events and snapshots. Their expected state is independent of generated Java layouts and target runtime bookkeeping.

Exact obligations use graph algorithms. Supported nondeterministic histories use a bounded history checker. Where constraint solving is required, Java code emits SMT-LIB queries, a standard solver input format, to an external solver process. The solver has a separate deadline, and an undecided query produces an inconclusive check.

Trace events are matched to the original GIR operations and control flow. A missing event, invalid branch, incomplete snapshot, or unsupported semantic profile cannot be treated as a successful verification.

### Generator, emitter, and reducer

The generator produces initial graphs and GIR programs. The Java emitter consumes the same validated GIR as the oracle and emits source plus a layout description. The emitter does not maintain a second simulated heap.

Generated node classes are shared by objects with the same shape. Field names follow slot positions, independently of their targets. Ordinary accesses become direct Java field or array operations; volatile and atomic accesses use the corresponding declarations or VarHandles.

The engine invokes `javac` with the target runtime on the classpath and retains the source, compiler settings, and exact class files. Generated methods and loops perform workload operations directly, allowing them to be compiled by the target JIT.

The reducer operates on GIR and execution settings. Each candidate must remain valid and reproduce the relevant outcome, including any required concurrency or compilation condition.

## Target runtime

The runtime supplies worker coordination, synchronization, phase transitions, scalar trace buffers, checkpoint requests, and access to VM controls. It calls generated worker methods rather than interpreting GIR in their hot loops.

The execution phases include graph construction, warm-up, concurrent stress, worker quiescence, and observation. A checkpoint identifies the participating workers and any collection or reference-processing requirements. Worker quiescence and collector completion are tracked separately.

Temporary handles can be emitted as Java locals in compiled frames or as explicitly published roots. Their required lifetimes follow GIR scopes. The recorder stores primitive identities and values so that telemetry does not retain workload objects.

Each worker records events with its identity, operation identity, and local sequence information. Synchronization events establish ordering between workers. Recording avoids a shared lock or global sequence counter on every mutation. Buffer exhaustion is reported explicitly.

Native calls and control traffic occur at the observation and coordination points that need them. Ordinary allocations and reference writes remain Java operations. Logical object identities are assigned in Java and associated with native snapshot identities during observation where possible.

## Native observer

The C++ library uses JNI to expose observation requests and JVMTI to inspect the target under the [observation contract](./observation.md). It receives layout descriptions, not the expected graph. Class metadata identifies fields and array elements without guessing object headers or field offsets.

Heap callbacks capture object identities, references, relevant roots, sizes, and requested data into bounded native storage. The engine decodes the capture into a logical graph and retains both forms. Unknown fields, schema mismatches, and incomplete captures are explicit results rather than silently discarded records.

Heap callbacks obey JVMTI's callback restrictions and do not call Java. The observer avoids persistent strong JNI references to workload objects. Native failures are reported through the runtime or protocol; C++ exceptions must not escape JNI or JVMTI entry points.

The observer records compilation and collection events where available. Their coverage and heap-walk effects are specified in the [observation contract](./observation.md#coverage-and-observer-effects); JVM integrations supply additional evidence required by the case.

## JVM integration

A JVM integration has a host driver and a target-side control bridge.

The host driver reads a target profile specifying the executable, collector, VM arguments and environment, support artifacts, required capabilities, and per-run limits. It probes the JVM and prepares the launch configuration, including the runtime bridge, native observer, and logging settings.

The target-side bridge implements the supported collection requests, compilation requests, queries, and phase controls. HotSpot can use a matching WhiteBox client. OpenJ9 uses its own controls and logs. Compiler names, optimization levels, flags, and testing APIs stay inside the integration.

Commands select the profile with `--profile`; profile fields have no CLI equivalents or overrides. CLI options control generation and campaigns. The integration verifies the actual collector and capabilities against case and profile requirements, reporting unsupported requirements without substitution or weaker checks.

The implemented commands and profile format are specified in [usage.md](./usage.md).

The common observer is reused where it satisfies the target's observation contract. A JVM that needs another observation implementation must still produce the same protocol records. Adding a JVM extends its integration without changing GIR or the oracle's validation rules.

## Runner and protocol

The runner owns process startup, phase and run deadlines, control communication, output capture, termination, and artifact retention. A campaign can run several target JVM jobs; each job has its own generated worker threads. These two forms of parallelism have separate limits.

The engine and runtime exchange versioned control messages over a Unix domain socket, separate from JVM stdout and stderr. Messages identify the run, capabilities, requested phase, checkpoint, completion, or failure. Bulk trace and snapshot data are files rather than per-operation control messages.

The protocol unit owns message and record definitions and their Java codecs. Records carry sufficient identity and completion information to detect missing data or a file from another run. Readers reject malformed data and incompatible versions.

Collection and compilation requests are paired with evidence. The runtime reports observed progress, and the engine checks the conditions required by the case. Flags and warm-up counts alone do not establish those conditions.

## Artifacts and results

Each run retains its inputs and observations under a separate run directory:

```text
runs/<run-id>/
    case.gir
    manifest.json
    generated/
    support/
    traces/
    snapshots/
    logs/
    target.properties
    execution.json
    result.json
```

GIR is the authoritative program representation. The manifest records the target profile, seeds, target and compiler identities, launch settings, capability requirements, and artifact provenance. Generated source, class files, and layout descriptions are retained so that replay can reuse the exact executable case.

Results separate execution outcome, semantic verdict, and stress coverage. A completed run can satisfy its checked invariants while missing a required compilation condition. A crash or timeout retains its logs and partial artifacts even when verification cannot finish.

Verification of saved artifacts uses the engine's oracle without the original target process or generator state. Timing-sensitive reproduction can repeat a case with its recorded synchronization and scheduling constraints.

## Checks

Java tests cover GIR parsing and formatting, validation, graph operations, oracle decisions, emission, and protocol handling. Native tests cover isolated decoding and record production where useful. Shared fixtures check compatibility between the Java and C++ protocol implementations.

Integration tests compile and execute generated Java on target JVMs, then verify its observations. They exercise multiple mutators, hot generated methods, local roots, conditional reference acquisition, supported atomic contention, and target capability reporting.

The checker is also exercised with deliberately corrupted graphs, traces, and missing evidence. Process tests cover compilation failure, startup failure, runtime crashes, deadlines, incomplete artifacts, and replay. Compatibility checks include the JDK 17 target boundary and the declared JVM profiles.
