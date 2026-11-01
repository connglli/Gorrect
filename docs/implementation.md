# Implementation architecture

## Architecture

The Java 21 engine generates and compiles a workload for a separate JDK 17+ target. Its oracle derives expectations independently; the target returns observations and execution evidence.

```text
Engine JVM (Java 21)                    Target JVM (JDK 17+)

GIR -> Java emitter -> javac --classes-> Generated methods/workers
 |                                             |
 v                                             v
Oracle -> Verifier <------- artifacts -- Runtime + VM control bridge
             ^                                 |
             +------------------------- JNI/JVMTI observer (C++17)

Driver -> Runner <------ Unix socket --> Runtime control endpoint
```

## Languages and build units

| Unit | Language | Responsibility |
| --- | --- | --- |
| `engine` | Java 21 | GIR, model/oracle, generation, emission, drivers, execution and CLI |
| `runtime` | Java 17 | Worker coordination, recording, checkpoints and VM controls |
| `protocol` | Java 17 | Shared records and codecs |
| `native` | C++17 | JNI entry points and JVMTI observation |

Gradle builds Java and invokes CMake. Runtime, protocol and generated programs use `--release 17`; the engine supplies the compiler independently of the target. Engine/runtime depend on protocol, and runtime has no oracle dependency. The native library must match the target OS/architecture; external VM clients must match its APIs.

## Engine

The frontend parses and validates [GIR](./gir.md). The model uses immutable heap records and explicit binding lifetimes so later writes cannot alter retained expectations. Dynamic checks reject invalid owners, shapes and accesses before their effects.

The exact oracle handles deterministic phases. History checking explores bounded schedules and reference-processing choices, proves race freedom before launch, and repeats that proof offline. Only equivalent complete states may be merged; all live worker/caller contexts and semantic resources remain part of the state.

The emitter generates real node classes, arrays and reusable worker methods. Plain operations use plain storage/accesses; volatile and atomic operations use field or array-element VarHandles. It retains compiled classes and layout metadata, without maintaining a second Java heap model.

[RunEngine](../engine/src/main/java/io/connglli/gorrect/engine/run/RunEngine.java) coordinates compilation, execution and verification through separate components. The seeded generator produces valid cases; reduction remains planned work.

## Target runtime

Generated Java performs graph mutations. Helpers coordinate workers, VM controls and checkpoints, recording scalar/identity facts in bounded per-worker primitive buffers. Recording does not retain workload objects or serialize ordinary mutations. Capacity plans cover either branch without predicting observed values.

A two-phase rendezvous publishes worker arrivals, then keeps the caller and every worker paused through capture and acknowledgement. Failure cancels waiters and joins workers before propagation. Per-worker order matters; cross-worker file order supplies no synchronization.

## Native observer

JNI drives JVMTI walks under the [capture contract](./observation.md#capture-contract), coordinating queue locks and containing C++ exceptions. Metadata queries and encoding run outside callbacks.

The engine projects records using layouts that identify observation roles without expected graph values. Local metadata comes from VM tables or certified retained class bytes.

## JVM integration

Each integration has a host driver and target control bridge. A bounded probe resolves the actual collector/mode, and the workload handshake rechecks it before graph construction. Replay freezes the resolved selection. New JVM support belongs here, with equivalent evidence or explicit unsupported results.

HotSpot uses WhiteBox controls and JFR; OpenJ9 uses launch policy, code-load callbacks and extended-stack witnesses. Installation events and pause counters do not prove compiled invocation or cycle completion. The verifier matches actual frames and collection events to validated execution contexts under the [stress](./gir.md#stress-requirements) and [profile](./usage.md#profiles) contracts.

## Runner and protocol

The runner enforces compiler/startup/run deadlines, captures output and terminates surviving processes. Versioned control frames travel over a temporary Unix domain socket; bulk evidence travels as files. Socket cleanup and partial artifact retention apply on failure too.

At each checkpoint the target writes evidence and waits for acknowledgement. The runner verifies run, sequence and executed site/visit identities against the program and trace. Plans reserve storage but supply no observed values. Completion requires a final capture after outer-scope release. The [protocol sources](../protocol/src/main/java/io/connglli/gorrect/protocol/) define framing and bounds.

## Artifacts and results

Each run has its own directory:

```text
runs/<run-id>/
    case.gir             manifest.json
    generated/           support/
    traces/              snapshots/
    logs/                target.properties
    execution.json       result.json
```

The manifest retains input/resolved profiles, seeds, compiler/target identities, command, environment and provenance hashes. Generated classes and bundled support preserve the executable. Traces/snapshots retain raw observations and enabled VM witnesses. Execution outcome, semantic verdict and unmet requirements are separate [result dimensions](./usage.md#artifacts-and-results).

## Checks

`./gradlew check` covers strict compilation/Javadoc, frontend/model/oracle/emission tests, Java/C++ wire compatibility and JDK 17/21 execution/capture/replay. Target suites run fresh because external JVM builds can change. Set `GORRECT_OPENJ9_JAVA` to enable the OpenJ9 suite. Independent small models, damaged evidence and injected failures check validation boundaries.
