# Implementation plan

This proposal divides the [implementation architecture](../../../docs/implementation.md) into twelve stages. The [design](../../../docs/design.md) owns the methodology; this note owns the proposed work order and completion checks. Each stage can contain several focused commits. A stage is complete when its checks pass, including the relevant failure cases.

## Development JVMs

Use Java 21 from PATH for the engine and its matching compiler for portable generated programs with `--release 17`. Resolve the engine independently of target selection and record compiler and target identities separately.

The initial development targets are HotSpot JDK 21 fastdebug and OpenJ9 JDK 21. Supply executable paths through local profiles. Committed notes, examples, and fixtures must use portable paths or explicit placeholders; local profiles and generated run artifacts remain untracked.

JDK 17 execution remains a separate compatibility check. Compilation with `--release 17` alone does not establish target compatibility.

## Command-line configuration

Follow the [profile and CLI ownership contract](../../../docs/implementation.md#jvm-integration). The stages below introduce each command with its underlying engine functionality.

This future parallel-campaign form includes the planned `--jobs` option:

```bash
gorrect fuzz --profile hotspot-g1.json \
  --workload concurrent-jit --workers 4 --jobs 2 \
  --duration 8h --seed 42 --out runs/
```

## Delivery milestones

* Stage 4 delivers `probe`, `run`, `verify`, and `replay` for sequential strong-reference cases.
* Stage 5 delivers a minimal `fuzz` command for concurrent and JIT stress.
* Stages 8 and 9 extend the same interface across HotSpot collectors and OpenJ9.
* Stage 10 reviews and refactors project-wide code and documentation quality.
* Stages 11 and 12 deliver parallel campaigns and automated failure reduction.

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

Status: complete for HotSpot Serial GC and named-root observation. The [CLI contract](../../../docs/usage.md) defines versioned profiles, commands, artifacts, and result statuses. `probe`, `run`, `verify`, and repeated `replay` share the execution and verification path; profile fields have no command-line overrides.

Generated Java performs direct graph operations. A bounded scalar recorder supplies operation order and data reads; a Unix domain socket carries versioned startup and checkpoint messages. Run bundles retain resolved profile data, the launch environment, installation and compiler identities, source, layout, exact classes, bundled support libraries, observations, and diagnostics. Offline verification needs no target installation. Replay restores recorded settings and environment, checks executable provenance, and records installation changes.

Verified JDK 17/21 execution, fresh object and array identities, aliases, cycles, loops, repeated checkpoints, scalar reads, offline checking, exact-class replay, and changed launchers. Negative checks cover strict profile parsing, conflicting flags, invalid GIR, checking limits, missing and corrupted evidence, buffer exhaustion, compilation and startup failures, malformed control, crashes, startup/run deadlines, partial control frames, and socket cleanup after success or failure. The packaged CLI also passes the selected HotSpot fastdebug target; the OpenJ9 driver reports unsupported explicitly.

Checkpoints with live GIR reference bindings remain unsupported rather than substituting synthetic roots. The named-root diagnostic profile permits the observer effects described in the [observation contract](../../../docs/observation.md). Concurrent/JIT generation, additional collectors, and the OpenJ9 driver follow in their planned stages.

## 5. Generate concurrent and JIT stress workloads

Status: complete for the current HotSpot Serial integration. [GIR 1](../../../docs/gir.md) supplies named workers, structured start/join and barriers, collection requests, and stress requirements. The oracle checks concrete per-phase accesses, rejects cross-worker conflicts including aliases, and compares independent per-worker traces. Generated methods perform direct graph operations; telemetry contains no workload references.

The [CLI](../../../docs/usage.md) includes bounded, single-job `fuzz` with reproducible seeds, workload and worker selection, duration, and retained cases. Profiles own optional WhiteBox control settings. Modeled visits supply warm-up; installation queries and raw JFR frame witnesses remain separate evidence. Joint stress checks require the relevant overlap, compiled execution, and GC progress in one group visit. Campaign budget exhaustion is distinct from a target timeout.

Verified a clean build, 180 Java tests, native checks, strict Javadoc, and JDK 17/21 compatibility. Selected HotSpot campaigns establish actual joint stress; interpreted launches retain a valid graph verdict while failing compiled coverage. Offline verification, exact-class replay without original support files, provenance, failure handling, bytecode, and missing/corrupted evidence checks pass. The [completed stage note](./completed-2026-10-29-stage5.md) records witness limits and client compatibility details.

## 6. Implement conditional reference histories

Status: complete for sequential ordinary-object reference histories. [GIR](../../../docs/gir.md#reference-histories) defines initial wrappers, fresh incarnations, single-read acquisition scopes, clearing observations, explicit clear, and queue polls. A bounded frontier validates recorded choices before interpreting their consequences. Capture reads inherited referent fields without strengthening; primitive recorders retain no workload objects.

Verified the design's two-acquisition example on JDK 17/21 and the selected HotSpot fastdebug target, with offline checking and exact-class replay. Independent exhaustive small-graph enumeration agrees with the production clearing search. Tests cover connected referents, mixed strengths, multiple wrappers, replacement, protected referents, surviving fields, impossible choices, delayed/duplicate queue delivery, damaged evidence, search limits, and conditional failure classification. The full build, strict Javadoc, hooks, and concurrent JIT regression case pass.

Reference histories with parallel blocks or array shapes, live-binding checkpoints, and reclamation/completion assertions remain unsupported. Missing coverage and exhausted search are inconclusive. [Stage 6 notes](./completed-2026-10-29-stage6.md) record the checks and remaining boundaries.

## 7. Support contended concurrent histories

Status: complete for bounded race-free ordinary-object histories. [GIR](../../../docs/gir.md#contended-histories) adds volatile access, CAS, exchange, and nullable ownership scopes. Direct VarHandles and private primitive recorders preserve concurrent execution. A bounded exhaustive SC audit proves preconditions and race freedom before launch; offline verification repeats it before matching recorded results, synchronization, and snapshots. All live worker/caller handles protect reference acquisitions.

Verified independent exhaustive CAS/exchange/store-buffering models, publication, ownership transfer, ordinary-race rejection, protected referents, barriers, incomplete/corrupted evidence, and work/state budgets. The full build, JDK 17/21 execution, strict Javadoc, native checks, offline verification, and replay pass. The selected HotSpot target establishes joint compiled/concurrent/GC coverage; interpreted execution leaves compilation unmet. [Stage 7 notes](./completed-2026-10-29-stage7.md) record the mechanisms and limits.

History arrays, live-binding/conditional checkpoints, weaker memory modes, arbitrary data races, and reclamation assertions remain unsupported. Large searches may exhaust their bounds without a verdict. Adding contended generator diversity follows stage 11.

## 8. Expand HotSpot collector and compiler coverage

Status: complete for available Serial, Parallel, G1, ZGC generations, and Shenandoah SATB/IU/passive profiles. Target-side facts queries resolve mode and flag availability; the workload handshake confirms selection and the saved profile freezes replay mode. GC request policy works independently of JIT, without falling back from unavailable controls.

JFR request spans and VM GC events provide completed whole-heap/major-cycle coverage separately from pause counters and graph verdicts. The full build, 260 Java tests, native checks, strict Javadoc, and JDK 17/21 matrix pass. Selected HotSpot C1/C2 runs cover all families, ZGC modes, and Shenandoah SATB/IU; interpreted execution leaves compilation unmet. Offline checking, replay, unavailable/ignored controls, and missing/unusable completion evidence are verified. [Stage 8 notes](./completed-2026-10-30-stage8.md) record the checks and build-specific limits.

Collection-complete checkpoints follow an explicit collect with validated cycle evidence. Completion does not force clearing, queue delivery, or reclamation; physical reclamation assertions remain unsupported without stronger evidence.

## 9. Complete the OpenJ9 integration

Goal: reuse the profile format, CLI, GIR, and oracle on another JVM.

Build on stage 3's probe. Implement the OpenJ9 driver, runtime bridge, collector policies, compilation evidence, and necessary observer adaptations behind the shared commands. Keep collector and compiler details within the integration.

Complete when common strong-reference, concurrent, JIT, and supported conditional-reference cases run on declared OpenJ9 profiles. `probe` must report requirements the selected build cannot establish. Any discovered assumption in the shared semantic contracts needs a separately reviewed correction.

## 10. Refactor code and documentation quality

Goal: refactor the whole project using practices from exemplary Java open-source projects.

Review mature projects' contribution guides and representative code, then select references and record the applicable conventions. Audit all modules, tests, and build support for code smells: duplication, oversized classes or methods, unclear naming, awkward APIs, and exception or resource-handling problems. Refactor in small, behavior-preserving steps.

Rewrite Javadoc to specify contracts and comments to explain necessary reasoning, following the [documentation standard](../../../docs/AGENTS.md). Add focused automated checks where they prevent recurring problems.

Complete when the conventions are applied consistently, identified smells are resolved or justified, and formatting, warning checks, tests, and supported-target regressions pass. Preserve GIR semantics, observation guarantees, CLI behavior, and reproducible artifacts.

## 11. Expand stress generation and campaign execution

Goal: extend `fuzz` to diverse workloads and parallel campaigns.

Expand generation with varied shapes, arrays, cycles, shared targets, allocation bursts, lifetime patterns, reference combinations, and supported synchronization. Use target measurements to guide physical workloads without changing semantic checks. Add parallel target jobs, campaign resource budgets, interruption handling, and summaries using stage 4's result categories. Worker and job limits remain independent.

Complete when seeds reproduce GIR inputs and retained artifacts explain each result. Concurrent jobs must isolate state, output, and deadlines; interruption must retain completed and partial runs. Crashes, timeouts, inconclusive checks, and unmet stress requirements remain visible. Rejected generation candidates must leave model state and bookkeeping consistent with the generation contract.

## 12. Reduce failures and validate the complete workflow

Goal: expose `reduce` and produce compact, credible reproducers.

Reduce GIR, graph shapes and contents, loop counts, workers, synchronization constraints, and optional profile settings. Use the shared validation, execution, and verification path for each candidate. Preserve the failure category and required stress evidence, use repeated trials for timing-sensitive findings, and retain the original case. Export reduced GIR, profile, generated Java, and replay instructions as a local reproducer bundle.

Complete when injected graph, trace, and execution defects reduce without losing their defining condition. A compiled concurrent failure must not become an unrelated interpreted or sequential failure. Exercise the CLI workflow from campaign finding through offline verification, repeated replay, and reduction, including interrupted runs, damaged artifacts, checker limits, and target resource exhaustion.

## Work boundaries

Each stage includes meaningful tests, artifact handling, and negative checks. CLI commands call shared engine operations; their implementation must not duplicate configuration, execution, or verification logic. Keep prototypes small and establish their contracts before expanding generation. Accepted contract changes belong in the owning design or implementation document.

The early generator uses a small supported subset. Phantom processing, finalization, resurrection, and deliberately racy programs require separate semantic profiles and follow-up plans.
