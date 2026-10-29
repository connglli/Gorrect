# Running Gorrect

The CLI supports exact and bounded concurrent [GIR](./gir.md) on HotSpot Serial GC, JDK 17+, including volatile/atomic operations and soft/weak acquisition. Other collectors and OpenJ9 remain planned work.

## Build and configure

Build the distribution with JDK 21, CMake, and a C++17 compiler:

```bash
./gradlew :engine:installDist
mkdir -p profiles/local
cp profiles/hotspot-serial.example.json profiles/local/hotspot-serial.json
```

Set `java` in the copied profile to the target executable. Paths are absolute or relative to the profile. The example assumes that copy location and a Linux build; adjust the native library for another OS. Local profiles and runs are ignored by Git. The engine uses its own JDK and compiler; the target needs no compiler.

## Commands

`gorrect --help` explains the workflow; `gorrect <command> --help` describes that command's inputs, options, and examples without starting a target JVM. From the repository root, use:

```bash
engine/build/install/gorrect/bin/gorrect probe --profile profiles/local/hotspot-serial.json
engine/build/install/gorrect/bin/gorrect run examples/cycle.gir --profile profiles/local/hotspot-serial.json --out runs/cycle
engine/build/install/gorrect/bin/gorrect verify runs/cycle
engine/build/install/gorrect/bin/gorrect replay runs/cycle --repeat 3 --out runs/replays
engine/build/install/gorrect/bin/gorrect fuzz --profile profiles/local/hotspot-serial.json --workload concurrent --workers 4 --duration 1m --seed 42
```

| Command | Purpose |
| --- | --- |
| `probe` | Run a built-in check and report the actual VM, collectors, and capabilities. Reference requirements select a reference case; concurrent/JIT requirements select a worker case. |
| `run` | Generate Java from a GIR case, compile it, execute it on the target, and verify the observations. |
| `verify` | Check saved GIR, executable identities, and evidence without the original profile file or target installation. |
| `replay` | Reuse saved classes and bundled support without recompiling. Restore recorded settings/environment, report changed installation artifacts, and create a fresh run per repetition. |
| `fuzz` | Generate seeded cases and execute one target JVM at a time. |

Output directories must not exist. Without `--out`, execution commands create a fresh directory under `runs/`. Profile fields have no CLI overrides.

For `fuzz`, `--workers` selects threads per case; `--workload` selects `concurrent` or `concurrent-jit`. The latter requires profile JIT controls and compiled-frame evidence. A campaign freezes profile settings, saves seeds, generator versions, and run bundles under `cases/`, and records outcomes in `campaign.json`.

`--duration` bounds active compilation and target execution; preparation and artifact retention can finish later. A partial final case has outcome `CAMPAIGN_LIMIT`. Campaigns stop on violations, execution failures, or unsupported cases. Success requires at least one completed case; every completed case must satisfy the oracle and its requirements.

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
| `requirements` | Optional capabilities: `tagging`, `named-roots`, `gc-events`, `compilation-events`, `contended-histories`, `reference-histories`, `reference-queues`, and the [GIR stress requirements](./gir.md#stress-requirements). `local-bindings`, `gc-completion`, and `reference-reclamation` remain unsupported. |
| `limits` | Optional positive bounds: `startupSeconds`, `runSeconds`, `compileSeconds`, `records`, `traceEvents`, `historyStates`, and `historyTransitions`. History limits bound live search/memo states and cumulative work across forks. Startup fits within the run deadline; compilation has a separate deadline. |
| `jit` | Optional HotSpot controls: `level`, `warmupRounds`, and `deadlineMillis`. Requires `support.whitebox`; the driver supplies bootstrap and WhiteBox flags. |

Defaults and bounds are defined by [TargetProfile](../engine/src/main/java/io/connglli/gorrect/engine/config/TargetProfile.java). Recognized but unimplemented selections or requirements return unsupported.

Place checkpoints after reference bindings end; live-binding capture is unsupported. Scalars can remain live. Contended histories require ordinary-object shapes and a complete [race-freedom audit](./gir.md#contended-histories). Failed preflight proofs retain the case with an unsupported, invalid-workload, or checking-limit outcome. The [observation contract](./observation.md) defines capture effects.

For JIT stress, copy [hotspot-serial-jit.example.json](../profiles/hotspot-serial-jit.example.json) to `profiles/local/` and supply an API-compatible WhiteBox client. `warmupRounds` counts modeled visits to each parallel block before installation requests; warm-up adds no unmodeled mutations. Compilation level requests installation; actual execution needs the [compiled-frame evidence](./gir.md#stress-requirements).

For the JDK 21 test-library layout, set `JDK_TEST_LIB` to the target source tree's `test/lib` directory and build a client with the engine JDK:

```bash
mkdir -p build/whitebox-classes
javac --release 17 -d build/whitebox-classes "$JDK_TEST_LIB/jdk/test/whitebox/WhiteBox.java" "$JDK_TEST_LIB/jdk/test/whitebox/parser/DiagnosticCommand.java"
jar --create --file build/whitebox.jar -C build/whitebox-classes .
```

Set `support.whitebox` to the JAR path. Each bundle records its checksum and the target identity.

## Artifacts and results

Each [run bundle](./implementation.md#artifacts-and-results) retains inputs, executables, observations, and diagnostics, including partial evidence after failure. Results separate execution outcome, semantic verdict, and unmet requirements.

A conflicting graph, scalar read, or impossible reference choice is a violation. Incomplete coverage or exhausted search is inconclusive; malformed evidence or altered executables is an extraction error. A rejected conditional precondition is an invalid workload when its recorded prefix establishes a legal path; otherwise the runtime failure remains. A confirmed violation stays visible alongside inconclusive checkpoints.

| Exit status | Meaning |
| --- | --- |
| 0 | Completed, checked invariants satisfied, requirements met. |
| 1 | Semantic violation. |
| 2 | Invalid command, profile, input path, or workload. |
| 3 | Unsupported integration, coverage, or capability requirement. |
| 4 | Compilation, startup, runtime, crash, protocol, or deadline failure. |
| 5 | Extraction error, checking limit, inconclusive verification, or no completed campaign case. |
| 130 | Engine interrupted while waiting on a process or control message. |
