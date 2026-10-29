# Running Gorrect

The sequential CLI supports HotSpot Serial GC on JDK 17 and newer. It executes [GIR 1](./gir.md), retaining generated Java, executable classes, and observations for verification and replay. Other collector integrations and concurrent workloads remain implementation work.

## Build and configure

Build the distribution with JDK 21, CMake, and a C++17 compiler:

```bash
./gradlew :engine:installDist
mkdir -p profiles/local
cp profiles/hotspot-serial.example.json profiles/local/hotspot-serial.json
```

Edit `java` in the copied profile to select the target executable. Paths resolve relative to the profile file; an absolute path is also accepted. The example's support paths assume that copy location and a Linux build. Select the corresponding native library on another operating system. Local profiles and run directories are excluded from Git.

The engine and compiler use the engine's JDK independently of the target. Generated programs compile with `--release 17`. The target need not provide a compiler.

## Commands

`gorrect --help` explains the workflow; `gorrect <command> --help` describes that command's inputs, options, and examples without starting a target JVM. From the repository root, use:

```bash
engine/build/install/gorrect/bin/gorrect probe --profile profiles/local/hotspot-serial.json
engine/build/install/gorrect/bin/gorrect run examples/cycle.gir --profile profiles/local/hotspot-serial.json --out runs/cycle
engine/build/install/gorrect/bin/gorrect verify runs/cycle
engine/build/install/gorrect/bin/gorrect replay runs/cycle --repeat 3 --out runs/replays
```

`probe` runs a small graph check and reports the actual VM, collectors, and observer capability bits. It retains the same diagnostics as `run`. A capability reports available evidence, not proof that compilation or a collection occurred.

`run` derives the oracle, emits direct Java operations, compiles them, and launches a separate target process. A Unix domain socket carries control messages; its temporary directory keeps long run paths out of the OS socket-path limit and is removed on completion or failure. The engine checks target identity and requirements before releasing execution. At each checkpoint, the target writes a snapshot and waits for acknowledgement. Completion requires the scalar trace, every expected checkpoint, and the final observation after the outer GIR scope ends.

`verify` re-derives expectations from saved GIR and checks the retained executable identities, trace, and observations. It needs neither the original profile file nor the target installation. `replay` copies the saved classes and bundled support artifacts without recompiling, restores the recorded profile settings and launch environment, and records changed installation artifacts. Each repetition gets a fresh run token and directory.

Output directories must not exist. Without `--out`, execution commands create a fresh directory under `runs/`. Profile fields have no CLI overrides.

## Profiles

Profiles are versioned JSON objects. Unknown fields, duplicate keys, invalid types, conflicting options, and invalid limits are rejected.

| Field | Meaning |
| --- | --- |
| `version` | Profile format version, currently 1. |
| `vm`, `collector` | Requested integration and collector. The implementation checks the collector reported by the running VM. |
| `java` | Target executable path. |
| `observation` | `named-roots-diagnostic`, explicitly permitting the [observer effects](./observation.md#coverage-and-observer-effects), including array identity binding. |
| `support` | Paths named `runtime`, `protocol`, and `observer`. Copies enter the run bundle. |
| `vmArgs` | Optional VM argument array. Collector selection, agents, classpath, entry point, and crash destination belong to the driver. Use one array element per argument. |
| `environment` | Optional environment additions. Ambient Java option and classpath variables are removed; profiles cannot restore them. The resolved launch environment is retained. |
| `requirements` | Optional capability names: `tagging`, `named-roots`, `gc-events`, or `compilation-events`. `local-bindings`, `compiled-execution`, and `gc-completion` are recognized but unsupported. |
| `limits` | Optional positive bounds: `startupSeconds`, `runSeconds`, `compileSeconds`, `records`, and `traceEvents`. Startup fits within the run deadline; compilation has a separate deadline. |

Defaults and bounds are defined by [TargetProfile](../engine/src/main/java/io/connglli/gorrect/engine/config/TargetProfile.java). Recognized VM and collector names do not imply implemented integration. Unsupported selections or requirements produce an explicit unsupported result.

Checkpoints with live GIR reference bindings are unsupported because their captured roots cannot yet be mapped to binding IDs. Put checkpoints after those bindings end. Scalar bindings can remain live. Arrays use diagnostic native identity bindings; ordinary identities come from immutable fields read during capture. These limits follow the [observation contract](./observation.md).

## Artifacts and results

The [run layout](./implementation.md#artifacts-and-results) includes the resolved profile, original profile data, compiler and installation identities, launch command and environment, generated source and layout, exact classes, copied support libraries, raw snapshots, and logs. `traces/` contains the control transcript and scalar execution trace. `execution.json` records the execution outcome; `result.json` records verification. Failed runs keep their partial artifacts.

Control frames and traces carry a format version and run identity. Checkpoint messages also identify the operation and visit. [ControlMessage](../protocol/src/main/java/io/connglli/gorrect/protocol/ControlMessage.java) and [ExecutionTrace](../protocol/src/main/java/io/connglli/gorrect/protocol/ExecutionTrace.java) define their encodings. The target recorder stores operation IDs and scalar reads in bounded primitive arrays, then serializes after execution. Expected events come from the oracle.

Results keep execution outcome, semantic verdict, and unmet requirements separate. A complete conflicting graph or scalar trace is a violation. Incomplete capture coverage is inconclusive. Structural damage and altered executable inputs are extraction errors. Compilation failure, startup failure, workload exceptions, crashes, protocol errors, and deadlines retain separate outcomes. A confirmed violation remains visible when another checkpoint is inconclusive.

| Exit status | Meaning |
| --- | --- |
| 0 | Completed, checked invariants satisfied, requirements met. |
| 1 | Semantic violation. |
| 2 | Invalid command, profile, input path, or workload. |
| 3 | Unsupported integration, coverage, or capability requirement. |
| 4 | Compilation, startup, runtime, crash, protocol, or deadline failure. |
| 5 | Extraction error, checking limit, or inconclusive verification. |
| 130 | Engine interrupted while waiting on a process or control message. |
