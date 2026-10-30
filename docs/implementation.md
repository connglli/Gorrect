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

The protocol is a shared library and file format. `RunEngine` coordinates preparation; `RunCompiler`, `TargetRunner`, and `RunVerifier` own compilation, target process/control, and offline verification. Artifact decoding and provenance belong to `Artifacts`.

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

The frontend parses, formats, and statically validates [GIR](./gir.md). Its immutable syntax describes declarations, bindings, and structured control flow. Shared structural traversal visits every instruction once in declaration order; execution and history search retain their own control flow. A case-local host model stores logical objects, roots, and handles; heap writes replace immutable records so retained checkpoints remain unchanged. Execution checks dynamic preconditions such as null owners and target shapes.

### Oracle and verifier

The oracle interprets GIR independently of generated layouts and runtime bookkeeping. The exact oracle checks conflict-free phases. Contended profiles explore bounded schedules and use vector clocks to prove race freedom before launch; offline verification repeats that proof before matching evidence. Search forks preserve private scopes, identities, and budgets. Equivalent quiescent states merge after joins; live worker states remain distinct.

Reference histories track cleared, pending, and delivered wrapper sets. Legal GC transitions expand the frontier; validated choices and snapshots filter it. Concurrent states include every worker's and suspended caller's handles. Detached wrappers may survive without creating strong referent roots. Search forks share cumulative work limits; graph traversal and retained search states count toward those limits.

### Generation and emission

The seeded generator constructs valid GIR. The emitter generates node classes, reusable worker methods, and layout metadata without a second simulated heap. Named roots and payload fields use plain storage and direct plain accesses. Volatile and atomic instructions call VarHandles on that storage; only used variables receive handles. The engine compiles against the target runtime and retains the exact classes.

The planned reducer will simplify GIR and execution settings while preserving the failure and any required stress conditions.

## Target runtime

The runtime coordinates workers, barriers, checkpoints, recording, and VM controls. Generated methods perform ordinary allocations and mutations in Java; native calls occur at observation and control points. Java locals represent handles, with reachability fences preserving GIR lifetimes.

Each worker has private primitive buffers for scalar and sparse identity observations, keyed by worker-local trace positions. Syntax bounds reserve capacity for either conditional outcome without predicting payloads. Only actual appends are serialized, and exhaustion stops execution. Strong reads record presence separately from identity. Recorders retain no workload objects.

Start, barrier, phase-start, end, and join markers establish worker control flow without a global sequence counter or a shared recorder lock. Per-worker order is meaningful; cross-worker file order is not.

## Native observer

JNI exposes capture requests; JVMTI heap callbacks fill bounded native buffers using VM field metadata and emitted layout roles. They receive no expected graph, perform no JNI calls, allocation, or locking, and retain no persistent strong references to workload objects. C++ exceptions are contained at JNI boundaries.

The engine projects raw records under the [observation contract](./observation.md), preserving raw evidence for diagnosis. That contract defines supported layouts, incomplete captures, GC/compilation counter coverage, and heap-walk effects.

## JVM integration

An integration has a host driver and target-side control bridge. A bounded helper queries HotSpot options/beans or OpenJ9's reported GC policy/beans; the workload handshake rechecks selection before execution. Resolved modes are frozen for replay. VM-specific controls and evidence interpretation stay behind this boundary; another integration must provide equivalent evidence or report unmet requirements.

The VM bridge separates optional JIT settings from GC request policy. HotSpot uses WhiteBox controls and JFR frame witnesses. OpenJ9 uses launch-time compilation policy, code-load callbacks, and `com.ibm.GetStackTraceExtended` witnesses. Raw stacks retain declaring class, method, descriptor, and interpreted/compiled/inlined kind in bounded `StackEvidence` version 1 records, with VM/run/thread/context identities. Both integrations match actual worker frames independently of installation reports. Pause-counter summaries retain one request per phase; missing evidence cannot satisfy [stress requirements](./gir.md#stress-requirements).

HotSpot cycle-completion coverage decodes every JFR collection-request span and VM GC event. It verifies VM/run identity and the exact executed request sequence, then requires a matching complete whole-heap/major cycle within each request. This proof is independent of JVMTI pause counts and graph contents. OpenJ9 reports this coverage unsupported. Cycle completion supplies no reclamation assertion.

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

The manifest records input/resolved profiles, seeds, compiler/target identities, command, environment, and provenance hashes. `collector.properties` retains preflight facts; `target.properties` records actual options and VM identity. Generated source, classes, layouts, and support libraries preserve the executable case. Replay reuses those bytes and settings; offline verification needs no target installation.

`traces/` holds the control transcript, scalar trace, worker evidence, `references.ghistory` for reference cases, and enabled VM witnesses (`workers.jfr` or OpenJ9 `workers.gstacks`). `snapshots/` preserves native captures. Records carry run identities and completion evidence. `execution.json` records process outcome; `result.json` adds the verdict and unmet requirements defined in [usage](./usage.md#artifacts-and-results).

## Checks

Gradle `check` includes strict Javadoc and Java tests for the frontend, model, oracle, emission, and codecs. JUnit tags select unit, observer, HotSpot, and configured OpenJ9 suites. Target suites run fresh because external JVM builds can change independently of Gradle inputs. A shared golden fixture checks Java/C++ wire compatibility. JDK 17/21 integration exercises generated execution, capture, offline verification, and replay. Set `GORRECT_OPENJ9_JAVA` to include `:engine:openj9Integration`. Independent exhaustive models check histories; damaged evidence, actual/interpreted frames, and injected failures test proof boundaries and diagnostics.
