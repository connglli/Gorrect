# Running Gorrect

The CLI supports sequential and conflict-free concurrent [GIR](./gir.md) on HotSpot Serial GC, JDK 17 and newer. It retains generated Java, executable classes, and observations for verification and replay. Other collector integrations and contended histories remain implementation work.

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
engine/build/install/gorrect/bin/gorrect fuzz --profile profiles/local/hotspot-serial.json --workload concurrent --workers 4 --duration 1m --seed 42
```

`probe` runs a built-in graph check and reports the actual VM, collectors, and observer capability bits. Profiles requesting concurrent or JIT coverage also exercise those conditions. A capability flag alone does not prove that compilation or a collection occurred.

`run` derives the oracle, emits direct Java operations, compiles them, and launches a separate target process. A Unix domain socket carries control messages; its temporary directory keeps long run paths out of the OS socket-path limit and is removed on completion or failure. The engine checks target identity and requirements before releasing execution. At each checkpoint, the target writes a snapshot and waits for acknowledgement. Completion requires the scalar trace, every expected checkpoint, and the final observation after the outer GIR scope ends.

`verify` re-derives expectations from saved GIR and checks the retained executable identities, trace, and observations. It needs neither the original profile file nor the target installation. `replay` copies the saved classes and bundled support artifacts without recompiling, restores the recorded profile settings and launch environment, and records changed installation artifacts. Each repetition gets a fresh run token and directory.

Output directories must not exist. Without `--out`, execution commands create a fresh directory under `runs/`. Profile fields have no CLI overrides.

`fuzz` generates seeded cases and runs one target JVM at a time. `--workers` selects threads within a case; `--workload` selects `concurrent` or `concurrent-jit`. The latter requires profile JIT controls and compiled-frame evidence. The campaign freezes profile settings and records each case's seed, generator version, GIR, and ordinary run bundle under `cases/`. Replay and verification use those bundles directly. `campaign.json` retains the outcomes and unmet requirements.

`--duration` bounds the campaign's active compilation and target execution; input preparation and artifact retention finish outside those waits. A partial final case has outcome `CAMPAIGN_LIMIT`, distinct from a target timeout. The campaign stops on a violation, crash, execution failure, or unsupported case. It returns an inconclusive status if no case completed; completed cases must satisfy their requirements for a successful campaign status.

## Profiles

Profiles are versioned JSON objects. Unknown fields, duplicate keys, invalid types, conflicting options, and invalid limits are rejected.

| Field | Meaning |
| --- | --- |
| `version` | Profile format version, currently 1. |
| `vm`, `collector` | Requested integration and collector. The implementation checks the collector reported by the running VM. |
| `java` | Target executable path. |
| `observation` | `named-roots-diagnostic`, explicitly permitting the [observer effects](./observation.md#coverage-and-observer-effects), including array identity binding. |
| `support` | Paths named `runtime`, `protocol`, and `observer`, plus optional `whitebox`. Copies enter the run bundle. |
| `vmArgs` | Optional VM argument array. Collector selection, agents, classpath, entry point, and crash destination belong to the driver. Use one array element per argument. |
| `environment` | Optional environment additions. Ambient Java option and classpath variables are removed; profiles cannot restore them. The resolved launch environment is retained. |
| `requirements` | Optional capabilities: `tagging`, `named-roots`, `gc-events`, `compilation-events`, and the [GIR stress requirements](./gir.md#stress-requirements). `local-bindings` and `gc-completion` remain unsupported. |
| `limits` | Optional positive bounds: `startupSeconds`, `runSeconds`, `compileSeconds`, `records`, and `traceEvents`. Startup fits within the run deadline; compilation has a separate deadline. |
| `jit` | Optional HotSpot controls: `level`, `warmupRounds`, and `deadlineMillis`. Requires `support.whitebox`; the driver supplies bootstrap and WhiteBox flags. |

Defaults and bounds are defined by [TargetProfile](../engine/src/main/java/io/connglli/gorrect/engine/config/TargetProfile.java). Recognized VM and collector names do not imply implemented integration. Unsupported selections or requirements produce an explicit unsupported result.

Checkpoints with live GIR reference bindings are unsupported because their captured roots cannot yet be mapped to binding IDs. Put checkpoints after those bindings end. Scalar bindings can remain live. Arrays use diagnostic native identity bindings; ordinary identities come from immutable fields read during capture. These limits follow the [observation contract](./observation.md).

For JIT stress, copy [hotspot-serial-jit.example.json](../profiles/hotspot-serial-jit.example.json) to `profiles/local/` and supply an API-compatible WhiteBox client for the selected target. `warmupRounds` counts modeled visits to each parallel block; the runtime requests installation before later visits. It executes no additional graph mutations for warm-up. The generator emits enough visits for this prefix within its checking budget. Compilation level is an installation request; actual compiled frames require separate evidence.

For the JDK 21 test-library layout, set `JDK_TEST_LIB` to the target source tree's `test/lib` directory and build a client with the engine JDK:

```bash
mkdir -p build/whitebox-classes
javac --release 17 -d build/whitebox-classes "$JDK_TEST_LIB/jdk/test/whitebox/WhiteBox.java" "$JDK_TEST_LIB/jdk/test/whitebox/parser/DiagnosticCommand.java"
jar --create --file build/whitebox.jar -C build/whitebox-classes .
```

Set `support.whitebox` to this JAR, relative to the local profile or as an absolute path. The client is external to Gorrect; its checksum and the actual target identity enter each bundle.

## Artifacts and results

The [run layout](./implementation.md#artifacts-and-results) includes the resolved profile, original profile data, compiler and installation identities, launch command and environment, generated source and layout, exact classes, copied support libraries, raw snapshots, and logs. `traces/` contains the control transcript, scalar trace, worker evidence, and JFR witnesses when enabled. `execution.json` records execution; `result.json` records verification. Failed runs keep partial artifacts.

Control frames and traces carry a format version and run identity. Checkpoint messages also identify the operation and visit. [ControlMessage](../protocol/src/main/java/io/connglli/gorrect/protocol/ControlMessage.java), [ExecutionTrace](../protocol/src/main/java/io/connglli/gorrect/protocol/ExecutionTrace.java), and [ExecutionEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/ExecutionEvidence.java) define the encodings. Workers append to separate primitive buffers within the total `traceEvents` bound; serialization follows joins. Expected events come from the oracle, and cross-worker file order does not imply an execution order.

Results keep execution outcome, semantic verdict, and unmet requirements separate. A complete conflicting graph or scalar trace is a violation. Incomplete capture coverage is inconclusive. Structural damage and altered executable inputs are extraction errors. Compilation failure, startup failure, workload exceptions, crashes, protocol errors, and deadlines retain separate outcomes. A confirmed violation remains visible when another checkpoint is inconclusive.

| Exit status | Meaning |
| --- | --- |
| 0 | Completed, checked invariants satisfied, requirements met. |
| 1 | Semantic violation. |
| 2 | Invalid command, profile, input path, or workload. |
| 3 | Unsupported integration, coverage, or capability requirement. |
| 4 | Compilation, startup, runtime, crash, protocol, or deadline failure. |
| 5 | Extraction error, checking limit, inconclusive verification, or no completed campaign case. |
| 130 | Engine interrupted while waiting on a process or control message. |
