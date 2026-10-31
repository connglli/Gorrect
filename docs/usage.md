# Running Gorrect

Gorrect runs [GIR](./gir.md) on JDK 17+ targets. HotSpot supports Serial, Parallel, G1, ZGC and Shenandoah; OpenJ9 supports gencon, balanced, optavgpause, optthruput and metronome where the selected build provides them.

## Build and configure

Build with JDK 21, CMake and a C++17 compiler:

```bash
./gradlew :engine:installDist
mkdir -p profiles/local
cp profiles/hotspot-serial.example.json profiles/local/hotspot-serial.json
```

Set `java` in the copied profile to the target executable. Paths are absolute or relative to the profile. The example assumes that copy location and a Linux native build; adjust the library for another OS. Local profiles/runs are ignored. The engine supplies its own compiler; the target needs none.

Choose an [example](../examples/) with a compatible [capture profile](./observation.md#coverage-and-observer-effects).

## Commands

From the repository root:

```bash
engine/build/install/gorrect/bin/gorrect probe \
  --profile profiles/local/hotspot-serial.json
engine/build/install/gorrect/bin/gorrect run examples/cycle.gir \
  --profile profiles/local/hotspot-serial.json --out runs/cycle
engine/build/install/gorrect/bin/gorrect verify runs/cycle
engine/build/install/gorrect/bin/gorrect replay runs/cycle \
  --repeat 3 --out runs/replays
```

| Command | Purpose |
| --- | --- |
| `probe` | Check the actual target/collector with a case exercising the requested capabilities. |
| `run` | Generate, compile, execute and verify a GIR case. |
| `verify` | Recheck a saved bundle offline without the original profile/installation. |
| `replay` | Rerun saved classes/support without recompiling, using recorded settings/environment and fresh evidence. |
| `fuzz` | Generate seeded cases and run one target JVM at a time. |

`gorrect <command> --help` gives inputs/options/examples without starting a JVM. Execution output directories must be new; the default is under `runs/`. Settings belong to the profile and have no CLI overrides. Replay reports changed installation artifacts.

A campaign can be started with:

```bash
engine/build/install/gorrect/bin/gorrect fuzz \
  --profile profiles/local/hotspot-serial.json \
  --workload concurrent --workers 4 --duration 1m --seed 42
```

`--workers` is threads per case. Workloads are `concurrent` and `concurrent-jit`; the latter needs configured JIT controls and compiled-frame evidence. Campaigns freeze the profile, retain cases/seeds and summarize outcomes in `campaign.json`. Duration bounds active compilation/execution; final retention may finish later. A partial final case is `CAMPAIGN_LIMIT`. Campaigns stop on violations, execution failures or unsupported cases; success needs at least one completed case and satisfaction of every completed case's graph and requirements.

## Profiles

Version 1 JSON profiles reject unknown/duplicate fields, invalid types/limits and conflicting options. [Example profiles](../profiles/) provide portable starting points; [TargetProfile](../engine/src/main/java/io/connglli/gorrect/engine/config/TargetProfile.java) owns defaults and bounds.

| Field | Meaning |
| --- | --- |
| `version` | `1`. |
| `vm`, `collector`, `java` | Integration, collector and target executable. Selection is checked on the running VM. |
| `collectorMode` | Optional `default`, resolved/frozen on the target. ZGC: `generational` / `non-generational`; Shenandoah: `satb`, `iu`, `passive`; others: `default`. |
| `observation` | One of the [observation profiles](./observation.md#coverage-and-observer-effects). |
| `bindingMetadata` | Optional `jvmti` or `retained-class`; see [metadata qualification](./observation.md#binding-metadata). |
| `support` | `runtime`, `protocol`, `observer` paths, and optional `whitebox`; copies enter the bundle. |
| `vmArgs` | One VM argument per array element; the driver owns agents, collector flags, classpath, main and crash destination. |
| `environment` | Additions to a cleaned environment. Java-option/classpath injection cannot be restored; retained mode also blocks native injection. |
| `requirements` | Requested capabilities/coverage, including [GIR stress requirements](./gir.md#stress-requirements). |
| `limits` | Positive `compileSeconds`, `startupSeconds`, `runSeconds`, `records`, `traceEvents`, `historyStates`, `historyTransitions`. History bounds cover states/cumulative work; startup fits within the run deadline. |
| `jit` | Optional `level`, `warmupRounds`, `deadlineMillis`. HotSpot levels 1–4 need WhiteBox; OpenJ9 level 1 denotes installed code, not an optimization tier. |
| `gc` | Optional `control`: `system`, or HotSpot `whitebox-full` / `whitebox-young` with a matching WhiteBox client. Default: full for HotSpot JIT profiles, system otherwise. |

Requirements include `tagging`, `named-roots`, `local-bindings`, `semantic-reads`, `alias-checks`, `reference-histories`, `reference-queues`, `contended-histories`, `gc-events`, `compilation-events`, `gc-completion` and `young-gc`. Read/binding/alias capabilities must be exercised, not merely available. `reference-reclamation` is unsupported.

HotSpot collection certification requires at least one executed `collect` and a complete VM event with matching collector-specific kind/cause wholly inside every request. `gc-completion` checks whole-heap/major cycles. `young-gc` needs `whitebox-young`: Serial, Parallel, G1, or explicit generational ZGC on JDK 21+. The young policy applies to all requests and cannot combine with `gc-completion`. Startup exercise, return alone or pause counters cannot supply a certificate. No request forces clearing/reclamation or proves promotion; OpenJ9 collection certification is unsupported. Use `collect; checkpoint;` to observe after a request.

HotSpot JIT profiles need a matching WhiteBox client; OpenJ9 can use `"jit": {}` and disables shared-cache/AOT reuse. Warm-up counts modeled visits and adds no unmodeled mutations. For the JDK 21 test-library layout, set `JDK_TEST_LIB` to the target source's `test/lib` and build with the engine JDK:

```bash
mkdir -p build/whitebox-classes
javac --release 17 -d build/whitebox-classes \
  "$JDK_TEST_LIB/jdk/test/whitebox/WhiteBox.java" \
  "$JDK_TEST_LIB/jdk/test/whitebox/parser/DiagnosticCommand.java"
jar --create --file build/whitebox.jar -C build/whitebox-classes .
```

Set `support.whitebox` to that JAR. The bundle records its hash/target identity.

## Artifacts and results

[Run bundles](./implementation.md#artifacts-and-results) retain inputs, executable identities, raw evidence and diagnostics, including partial failures. Results separate execution outcome, graph verdict and unmet requirements. A confirmed graph/scalar/reference violation survives independent extraction gaps; incomplete proof is inconclusive. Altered executables or malformed control/evidence are extraction errors offline, while live control violations are protocol failures.

`observation-capabilities.json` is a derived availability/exercise/verification report, never verification input. Unusable target facts produce an empty capability list, with extraction diagnostics and unverified requirements retained in the result. A runtime conditional-precondition failure is invalid workload only if its legal prefix and every preceding observation are fully established.

| Exit | Meaning |
| --- | --- |
| 0 | Completed, satisfied and all requirements met. |
| 1 | Confirmed semantic violation; takes precedence over other outcomes. |
| 2 | Invalid command, profile, path or workload. |
| 3 | Unsupported capability/coverage or unmet requirement. |
| 4 | Compilation, startup, runtime, crash, protocol or deadline failure. |
| 5 | Extraction/checking-limit/inconclusive result or no completed campaign case. |
| 130 | Engine interrupted while waiting on process/control. |

[RunReport](../engine/src/main/java/io/connglli/gorrect/engine/run/RunReport.java) defines exact status precedence.
