# Implementation plan

This proposal divides the [implementation architecture](../../../docs/implementation.md) into eleven stages. The [design](../../../docs/design.md) owns the methodology; this note owns the proposed work order and completion checks. Each stage can contain several focused commits. A stage is complete when its checks pass, including the relevant failure cases.

## Development JVMs

Use Java 21 from PATH for the engine and its matching compiler for portable generated programs with `--release 17`. Resolve the engine independently of target selection and record compiler and target identities separately.

The initial development targets are HotSpot JDK 21 fastdebug and OpenJ9 JDK 21. Supply executable paths through local profiles. Committed notes, examples, and fixtures must use portable paths or explicit placeholders; local profiles and generated run artifacts remain untracked.

JDK 17 execution remains a separate compatibility check. Compilation with `--release 17` alone does not establish target compatibility.

## Command-line configuration

Follow the [profile and CLI ownership contract](../../../docs/implementation.md#jvm-integration). The stages below introduce each command with its underlying engine functionality.

The following is proposed CLI syntax, not an implemented command:

```bash
gorrect fuzz --profile hotspot-g1.json \
  --workload concurrent-jit --workers 4 --jobs 2 \
  --duration 8h --seed 42 --out runs/
```

## Delivery milestones

* Stage 4 delivers `probe`, `run`, `verify`, and `replay` for sequential strong-reference cases.
* Stage 5 delivers a minimal `fuzz` command for concurrent and JIT stress.
* Stages 8 and 9 extend the same interface across HotSpot collectors and OpenJ9.
* Stages 10 and 11 deliver parallel campaigns and automated failure reduction.

## 1. Establish the build and compatibility checks

Status: complete. The pinned Gradle wrapper builds the four units and invokes CMake. Java compilation uses the engine toolchain, with target modules restricted to Java 17; native code uses C++17. Compiler warnings fail the build. JNI/JVMTI loading checks discover JDK 17 independently of the engine.

Verified a clean source build, native loading on JDK 17 and 21 and the selected HotSpot fastdebug and OpenJ9 targets, Java API and bytecode boundaries, and rejection of a C++ compiler warning. Formatting and documentation checks pass. The GitHub Actions build workflow is configured; remote CI has not run.

## 2. Implement the minimal GIR and exact oracle

Status: complete. [GIR 1](../../../docs/gir.md) has immutable graph declarations and instructions, optional labels, parsing, canonical formatting, validation, and a bounded deterministic interpreter. It supports strong fields and arrays, long-valued payloads, typed handles, root lifetimes, scoped release, loops, and checkpoints. Exact verification checks required identities, shapes, slot counts, kinds, targets, and data while allowing retained garbage. Incomplete observations are inconclusive.

Verified round trips, forward references, aliases, cycles, self-references, retargeting, local lifetimes, repeated allocation identities, and rejection without state or budget changes. Independent expected snapshots, corrupted observations, exhaustive two-object graphs, and the executable documentation example exercise the oracle. The clean full build and JDK 17/21 native checks pass. Test dependencies are pinned and locked; unsupported syntax and checking limits remain explicit errors.

## 3. Prove observation and root handling

Status: complete for the prototype's named-root profile. The [observation contract](../../../docs/observation.md) defines raw capture, Java/C++ encoding, field metadata, logical projection, and unsupported coverage. Native callbacks use bounded preallocated storage and weak tags. Ordinary identities are read from object fields at observation time; published arrays use an explicit diagnostic binding that can force materialization.

Verified JDK 17/21 integration, cycles, aliases, fields, arrays, payloads, retained raw origins, explicit buffer exhaustion, and rejection of corrupted or missing evidence. The selected HotSpot and OpenJ9 targets pass named-root graph verification in interpreted and compilation-focused modes. Weak references to captured nodes and tagged arrays clear after release. Allocation probes compare no-agent, tagging, and post-walk execution; the temporary-object probe remains eliminated on HotSpot and has comparable allocation with and without the agent on OpenJ9.

Portability limits remain explicit: the selected OpenJ9 compiled-local probe preserves the object across GC and capture but omits its root-origin callback. HotSpot reports the origin, while its heap walk may materialize eliminated objects. General GIR local-binding projection is not implemented; obligations requiring it return inconclusive. Stage 4 must establish binding evidence or report the affected cases unsupported, and must not infer liveness failures from missing physical roots.

## 4. Deliver profiles and the sequential CLI

Goal: execute and investigate saved GIR cases through the CLI.

Implement versioned JSON profile loading and validation, then expose `probe`, `run`, `verify`, and basic `replay`. Connect direct Java emission, layout descriptions, compilation, the runtime endpoint, control protocol, runner, native capture, and exact verification. Start with one HotSpot collector. Retain profile contents, resolved launch settings, artifact identities, source, classes, observations, logs, and results. Keep local profiles and generated artifacts excluded from Git.

Complete when hand-written GIR cases run on JDK 17 and a newer supported JDK. Profile validation must reject malformed data, unknown fields, conflicting settings, and unsupported requirements; the CLI must expose no profile-field overrides. `probe` reports actual capabilities. `verify` works offline without the original profile file or target installation. `replay` reuses saved classes and profile settings, supports repeated runs, and records changes to target or support artifacts.

Corrupted graphs and traces must fail the appropriate checks. Compilation failure, startup failure, crashes, deadlines, and incomplete artifacts must retain diagnostics and distinct results. Define readable summaries, machine-readable results, and exit statuses that preserve execution outcome, semantic verdict, and unmet requirements.

## 5. Generate concurrent and JIT stress workloads

Goal: deliver `fuzz` for concurrent and JIT stress with an exact oracle.

Add workers, start and join, barriers, safe publication, and disjoint or commuting mutations. Emit reusable methods and hot loops directly. Add separate warm-up objects or an explicit modeled prefix, per-worker scalar recording, local-root lifetime cases, and target-method compilation evidence through a HotSpot control bridge. Implement a small seeded generator and a bounded single-job `fuzz` loop with workload, seed, worker count, duration, and output options.

Complete when a CLI campaign generates overlapping mutators, allocation pressure, and compiled workload methods with exact checks after synchronization. Saved findings must replay through stage 4's execution path. Check local lifetimes across allocations and calls, and detect trace loss or missing synchronization evidence. Report semantic verdict and stress requirements separately; warm-up counts or code installation alone cannot prove compiled execution. Inspect bytecode and focused recordings to confirm direct graph operations and recording without per-mutation serialization.

## 6. Implement conditional reference histories

Goal: check programs whose behavior depends on soft or weak reference acquisition.

Specify the supported clearing transition model before implementing the history checker. Add wrapper incarnation identities, single-read conditional acquisition, scoped strong results, clearing observations, and queue events where supported. Keep clearing, enqueueing, and physical reclamation distinct. Start with a small exhaustive reference model, then use bounded search or an external SMT solver where necessary. Add profiles for the evidence required by reclamation-sensitive checks.

Complete when the conditional-access example in the design works end to end. Compare the production checker with exhaustive enumeration of small histories. Exercise connected referents, multiple wrappers, wrapper replacement, protected referents, surviving strong fields, and impossible acquisition sequences. Invalid histories must fail; legal policy variation must pass. Missing guarantees, unsupported semantics, and exhausted checking budgets must produce an inconclusive check.

## 7. Support contended concurrent histories

Goal: extend concurrency beyond exact post-synchronization graphs.

Define supported volatile, atomic, and ownership-transfer operation contracts. Add corresponding GIR operations, Java emission, trace records, and bounded history checking. Validate returned values and publication along with final graph contents. Combine these operations with supported reference acquisition only after their individual models have independent checks.

Complete when small exhaustive concurrent models agree with the production checker, including cases with several legal outcomes and deliberately impossible reads or updates. Validate synchronization and per-worker order without treating logging timestamps as a global execution order. Keep arbitrary data races outside these profiles until their Java memory-model obligations are specified.

## 8. Expand HotSpot collector and compiler coverage

Goal: run supported workloads across the intended HotSpot profiles through the common CLI.

Extend `probe`, collector profiles, collection and compilation controls, and evidence interpretation. Cover Serial, Parallel, G1, ZGC, and Shenandoah where the selected build provides them. Resolve modes per JDK build, use matching WhiteBox support where needed, and distinguish stop-the-world pause notifications from concurrent-phase evidence.

Complete when common cases pass for every claimed profile using the same commands. Verify actual collector selection and report unavailable controls without substitution. Include JDK 17 and supported newer builds, reclamation-sensitive checkpoints, and JIT transition tests whose evidence the integration can establish.

## 9. Complete the OpenJ9 integration

Goal: reuse the profile format, CLI, GIR, and oracle on another JVM.

Build on stage 3's probe. Implement the OpenJ9 driver, runtime bridge, collector policies, compilation evidence, and necessary observer adaptations behind the shared commands. Keep collector and compiler details within the integration.

Complete when common strong-reference, concurrent, JIT, and supported conditional-reference cases run on declared OpenJ9 profiles. `probe` must report requirements the selected build cannot establish. Any discovered assumption in the shared semantic contracts needs a separately reviewed correction.

## 10. Expand stress generation and campaign execution

Goal: extend `fuzz` to diverse workloads and parallel campaigns.

Expand generation with varied shapes, arrays, cycles, shared targets, allocation bursts, lifetime patterns, reference combinations, and supported synchronization. Use target measurements to guide physical workloads without changing semantic checks. Add parallel target jobs, campaign resource budgets, interruption handling, and summaries using stage 4's result categories. Worker and job limits remain independent.

Complete when seeds reproduce GIR inputs and retained artifacts explain each result. Concurrent jobs must isolate state, output, and deadlines; interruption must retain completed and partial runs. Crashes, timeouts, inconclusive checks, and unmet stress requirements remain visible. Rejected generation candidates must leave model state and bookkeeping consistent with the generation contract.

## 11. Reduce failures and validate the complete workflow

Goal: expose `reduce` and produce compact, credible reproducers.

Reduce GIR, graph shapes and contents, loop counts, workers, synchronization constraints, and optional profile settings. Use the shared validation, execution, and verification path for each candidate. Preserve the failure category and required stress evidence, use repeated trials for timing-sensitive findings, and retain the original case. Export reduced GIR, profile, generated Java, and replay instructions as a local reproducer bundle.

Complete when injected graph, trace, and execution defects reduce without losing their defining condition. A compiled concurrent failure must not become an unrelated interpreted or sequential failure. Exercise the CLI workflow from campaign finding through offline verification, repeated replay, and reduction, including interrupted runs, damaged artifacts, checker limits, and target resource exhaustion.

## Work boundaries

Each stage includes meaningful tests, artifact handling, and negative checks. CLI commands call shared engine operations; their implementation must not duplicate configuration, execution, or verification logic. Keep prototypes small and establish their contracts before expanding generation. Accepted contract changes belong in the owning design or implementation document.

The early generator uses a small supported subset. Phantom processing, finalization, resurrection, and deliberately racy programs require separate semantic profiles and follow-up plans.
