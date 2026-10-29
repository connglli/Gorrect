# Implementation architecture

## Architecture

The Java 21 engine derives expectations from GIR and launches a separate JDK 17+ target. Generated Java executes the workload; target helpers return observations for verification.

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

The protocol is a shared library and file format. The runner manages the target process and its control connection.

## Languages and build units

| Unit | Language | Responsibility |
| --- | --- | --- |
| `engine` | Java 21 | GIR, oracle, generation, Java emission, JVM drivers, runner, and CLI |
| `runtime` | Java 17 | Workers, recording, checkpoints, and VM controls |
| `protocol` | Java 17 | Commands, records, and Java codecs |
| `native` | C++17 | JNI entry points and JVMTI observation |

Gradle builds Java and invokes CMake for native code. The wrapper and dependencies are pinned; compiler warnings fail the build. Runtime, protocol, and generated programs use `--release 17`. The engine supplies the compiler independently of the target installation.

Engine and runtime depend on protocol; runtime has no engine or oracle dependency. The native library implements the protocol's snapshot encoding and must match the target OS and architecture. External VM support libraries must match the target's APIs.

## Engine

### GIR frontend and model

The frontend parses, formats, and statically validates [GIR](./gir.md). Its immutable syntax describes declarations, bindings, and structured control flow. A case-local host model stores logical objects, roots, and handles; heap writes replace immutable records so retained checkpoints remain unchanged. Execution checks dynamic preconditions such as null owners and target shapes.

### Oracle and verifier

The oracle interprets GIR independently of generated layouts and runtime bookkeeping. The exact oracle records concrete slot accesses while interpreting each parallel phase and rejects cross-worker conflicts before target execution. The verifier compares its obligations with recorded reads and snapshots.

Reference histories use a bounded frontier of cleared, pending, and delivered wrapper sets. Legal GC transitions expand it; validated choices and snapshots filter it. Ordinary payload state is shared across surviving possibilities. Detached wrappers may be retained without creating strong referent roots. Search exhaustion is inconclusive. Contended histories remain planned work.

### Generation and emission

The seeded generator constructs valid GIR. The emitter translates the same syntax into a node class per shape, direct field/array accesses, reusable worker methods, and layout metadata. It maintains no second simulated heap. The engine compiles the source against the target runtime and retains the exact classes.

The planned reducer will simplify GIR and execution settings while preserving the failure and any required stress conditions.

## Target runtime

The runtime coordinates workers, barriers, checkpoints, recording, and VM controls. Generated methods perform ordinary allocations and mutations in Java; native calls occur at observation and control points. Java locals represent handles, with reachability fences preserving GIR lifetimes.

Each worker appends operation, visit, phase, and scalar values to a private primitive buffer. Storage is bounded by the profile's trace limit; planned counts reserve capacity without predicting payloads. Only actual appends are serialized, and exhaustion stops execution. Reference cases add sparse identity observations keyed to main-trace positions, with syntax bounds covering either acquisition outcome. Recorders retain no workload objects.

Start, barrier, phase-start, end, and join markers establish worker control flow without a global sequence counter or a shared recorder lock. Per-worker order is meaningful; cross-worker file order is not.

## Native observer

JNI exposes capture requests; JVMTI heap callbacks fill bounded native buffers using VM field metadata and emitted layout roles. They receive no expected graph, perform no JNI calls, allocation, or locking, and retain no persistent strong references to workload objects. C++ exceptions are contained at JNI boundaries.

The engine projects raw records under the [observation contract](./observation.md), preserving raw evidence for diagnosis. That contract defines supported layouts, incomplete captures, GC/compilation counter coverage, and heap-walk effects.

## JVM integration

An integration has a host driver and target-side control bridge. The driver resolves the [profile](./usage.md#profiles), launches the target, and confirms the actual VM, collector, and capabilities before releasing execution. The bridge implements collection and compilation requests. VM-specific APIs and frame interpretation stay behind this boundary; another integration must supply equivalent evidence or report an unmet requirement.

HotSpot uses an optional external WhiteBox client for full collection and method installation. JFR stack witnesses establish compiled execution independently of installation queries. Frame matching checks generated class, method, descriptor, thread, worker, visit, phase, and collection-request identity. Counter summaries retain one completed collection request per phase; missing frames or recording loss cannot satisfy the [stress requirements](./gir.md#stress-requirements).

Commands select `--profile`; its fields have no CLI overrides. An integration can replace the observer while preserving GIR semantics and the common protocol.

## Runner and protocol

The runner enforces startup, compilation, and run deadlines, captures output, terminates processes, and retains partial artifacts. The engine and target exchange versioned control frames over a Unix domain socket. Its short temporary path is independent of the run directory and is removed on success or failure. Bulk traces and snapshots are files.

Frames identify the run and checkpoint operation/visit. The target writes a snapshot and waits for acknowledgement at each checkpoint; completion requires every expected checkpoint and the final observation after outer-scope release. Version, identity, bounds, and completion checks detect malformed, missing, or mixed-run evidence. The [protocol module](../protocol/src/main/java/io/connglli/gorrect/protocol/) owns the codecs.

## Artifacts and results

Each run uses a separate directory:

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

The manifest records input/resolved profiles, seeds, compiler/target identities, command, environment, and provenance hashes. Generated source, classes, layouts, and copied support libraries preserve the executable case. Replay uses those bytes and settings; offline verification derives obligations from saved GIR without a target installation.

`traces/` holds the control transcript, scalar trace, worker evidence, `references.ghistory` for reference cases, and JFR witnesses when enabled. `snapshots/` preserves native captures. Records carry run identities and completion evidence. `execution.json` records process outcome; `result.json` adds the verdict and unmet requirements defined in [usage](./usage.md#artifacts-and-results).

## Checks

Java tests cover the frontend, model, oracle, emission, and codecs. A shared golden fixture checks Java/C++ wire compatibility. JDK 17/21 integration exercises generated execution, native capture, offline verification, and replay. Independent exhaustive small-graph models check the reference frontier. Corrupted evidence and injected process failures test classification and retained diagnostics.
