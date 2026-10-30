# Implementation plan

This plan orders the [implementation architecture](../../../docs/implementation.md) and the accepted [mechanism review](../designs/completed-2026-10-30-mechanism-coverage-review.md) into reviewable stages. The [design](../../../docs/design.md) owns the methodology; this note owns the work order and completion checks. Each stage can contain several focused commits. A stage is complete when its checks pass, including the relevant failure cases.

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
* Stages 11 through 25 expand mechanism coverage before expanding generation.
* Stages 26 and 27 deliver parallel campaigns and automated failure reduction.

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

History arrays, live-binding/conditional checkpoints, weaker memory modes, arbitrary data races, and reclamation assertions remain unsupported. Large searches may exhaust their bounds without a verdict. Adding contended generator diversity follows stage 26.

## 8. Expand HotSpot collector and compiler coverage

Status: complete for available Serial, Parallel, G1, ZGC generations, and Shenandoah SATB/IU/passive profiles. Target-side facts queries resolve mode and flag availability; the workload handshake confirms selection and the saved profile freezes replay mode. GC request policy works independently of JIT, without falling back from unavailable controls.

JFR request spans and VM GC events provide completed whole-heap/major-cycle coverage separately from pause counters and graph verdicts. The full build, 260 Java tests, native checks, strict Javadoc, and JDK 17/21 matrix pass. Selected HotSpot C1/C2 runs cover all families, ZGC modes, and Shenandoah SATB/IU; interpreted execution leaves compilation unmet. Offline checking, replay, unavailable/ignored controls, and missing/unusable completion evidence are verified. [Stage 8 notes](./completed-2026-10-30-stage8.md) record the checks and build-specific limits.

Collection-complete checkpoints follow an explicit collect with validated cycle evidence. Completion does not force clearing, queue delivery, or reclamation; physical reclamation assertions remain unsupported without stronger evidence.

## 9. Complete the OpenJ9 integration

Status: complete for the OpenJ9 driver, runtime controls, collector-policy checks, and compiled-frame evidence. Existing commands, profiles, GIR, and oracles serve gencon, balanced, optavgpause, optthruput, and metronome. Launch-time JIT policy and code-load events are checked independently against raw extended-stack witnesses. Replay tracks OpenJ9's separate implementation libraries. Cycle completion, reclamation, and existing unsupported observation/history profiles remain explicit.

The normal build passes 266 Java tests, native JDK 17/21 checks, and strict Javadoc. Selected OpenJ9 JDK 21 checks cover strong arrays, workers, conditional references, compiled/interpreted execution, offline verification, replay, and a seeded concurrent-JIT campaign. The Metronome contended case aborts during class unloading; a standalone Java/JVMTI reproducer confirms the assertion independently of Gorrect. The selected-target matrix retains this failure. [Stage 9 notes](./completed-2026-10-30-stage9.md) record verified coverage and limits; OpenJ9 JDK 17 remains untested.

## 10. Refactor code and documentation quality

Status: complete. [Stage 10 notes](./completed-2026-10-30-stage10.md) record the selected Google/JUnit/OpenJDK conventions and component audit. Execution phases and snapshot-projection passes have focused responsibilities; structural GIR queries are shared independently of execution. Typed runtime controls, explicit native wire types, buffered codecs, clearer contracts/imports, tagged test suites, and normal-build Javadoc checks improve maintainability. Cohesive search algorithms and format-specific decoders remain explicit.

The normal build passes 275 Java tests and native/Javadoc checks, preserving all original cases and adding nine regressions. Eleven emission baselines preserve Java bytes and layout values; selected HotSpot C1/C2, OpenJ9 contended execution, old-bundle offline verification, and exact-class replay pass. Malformed fuzz profiles report input errors before creating a campaign. Target suites run fresh against external VM builds. The OpenJ9 matrix retains its known Metronome assertion (15/16 passing), without a driver workaround. Formats remain version 1; the local bug archive stays untracked.

## 11. Preserve access modes and define observation boundaries

Status: complete. Named roots use plain storage and direct plain accesses; volatile/atomic operations retain explicit VarHandle effects. The contracts distinguish controlled lifetimes, diagnostic binding effects, logical/physical/class identity, and unsupported natural-liveness observation. [Stage 11 notes](./completed-2026-10-30-stage11.md) record the review and checks.

The full build, JDK 17/21 storage/bytecode checks, mixed-mode verification/replay, selected HotSpot C1/C2 and OpenJ9 contended cases, and old-bundle verification pass. Selected fastdebug runs need longer local startup deadlines; committed defaults are unchanged. Live-local capture follows in stage 12; formats remain version 1.

## 12. Add root and observation profiles

Status: complete for quiescent caller snapshots and worker semantic uses. [Stage 12 notes](./completed-2026-10-30-stage12.md) record controlled/natural profiles, actual local-variable/root mappings, primitive binding evidence, same-object comparisons, bounded future-use analysis, and structured observation capabilities. Missing optimized origins remain inconclusive. Reports distinguish availability, exercise, and verified obligations; no hidden strong registry supplies roots.

The full build passes 316 Java tests and native/Javadoc checks. JDK 17/21 captures and retention pass with and without local-variable access; 128 independent use patterns check natural liveness. Selected HotSpot C1/C2 and OpenJ9 cases, offline verification, replay, old-bundle compatibility, and 32 observer-effect calibration runs pass. General call frames, conditional/reference/contended binding composition, and active-worker heap snapshots retain their later-stage boundaries.

## 13. Complete history and checkpoint composition

Status: in progress. [The first slice](./completed-2026-10-30-stage13a.md) completes array and controlled caller-binding histories. [Remaining work](./2026-10-30-stage13.md) covers conditional visits, coordinated worker capture, and natural-history liveness.

Compose arrays, binding profiles, reference/atomic histories, conditional checkpoints, and coordinated mid-worker capture. Natural histories need path-sensitive future-use and clearing rules; unrelated array declarations must not cause blanket rejection. Specify checkpoint phase, live-root coverage, and synchronization before extending projection or search.

Complete when independent small histories cover array aliasing, publication, acquisitions, and conditional visits. Missing workers, partial captures, invalid phase evidence, races, and exhausted budgets cannot pass. Existing ordinary-object cases retain their verdicts.

## 14. Add scalar control, frames, and exception semantics

Goal: express decisions and call paths that exercise real Java frames.

Add a compact typed arithmetic/comparison basis, scalar branches, computed indices and length queries, calls/returns, and bounded recursion. Key handles by activation, not declaration alone. Model expected Java exceptions, exceptional unwind, and finally effects separately from invalid GIR; operations specify normal/exceptional post-states and access footprints, including permitted partial effects.

Complete when CAS results control execution, repeated/recursive frames keep distinct lifetimes, and generated normal/unwind behavior matches independent models. Rejected GIR operations retain transactional failure behavior.

## 15. Make references and queues first-class

Goal: model Reference objects as graph participants and cover asynchronous cleanup.

Allow wrapper aliases, roots, container movement, wrapper referents, and lifetimes independent of their original slots. Add named/optional queues, explicit enqueue outcomes, phantom processing, and Cleaner actors with modeled effects. Phantom observation must not reuse weak/soft acquisition semantics.

Complete when independently enumerated wrapper/queue histories match execution, including replacement, duplicate/delayed delivery, wrapper chains, and cleanup effects. No wrapper registry strengthens referents. Finalization and resurrection remain a separate stage.

## 16. Add collection and phase controls with evidence

Goal: exercise young, full, and concurrent collector paths deliberately.

Add capability-gated young collection, concurrent-cycle start/await, and phase rendezvous. Record actual allocation size, generation/region placement, and mutation/phase overlap where supported. Cover old-to-young publication, mark deletion/insertion, remembered sets, relocation, evacuation/promotion, and humongous pressure. HotSpot controls come first; OpenJ9 supplies equivalent certificates or explicit unsupported results.

Complete when independent VM evidence establishes each requested path and negative fixtures reject absent/incorrect phase evidence. Measurements stay stress facts; collection requests or completion never imply mandatory clearing or reclamation.

## 17. Scale allocation and checking

Goal: support bounded-live-set churn and larger histories without weakening proofs.

Retire provably unrecoverable garbage with sound summaries, bound evidence growth, and add explicit allocation-pressure workloads with actual-allocation evidence. Extend checking through incremental obligations, exact state merging, and sound partial-order reduction where measured bottlenecks justify them. Preserve wrapper, pending-cleanup, and future-resurrection constraints.

Complete when long allocate/release runs stay bounded by live state rather than cumulative garbage, and small exhaustive models agree with optimized checking. Preserve all-schedules validity/race proofs and work limits. Partial evidence may refute an obligation but cannot establish a complete pass.

## 18. Add layouts, bulk operations, and identity observations

Goal: reach VM copying/barrier paths and realistic layouts.

Add dynamically sized and typed arrays, mixed primitive payloads, inheritance/interfaces, declared final/volatile fields, range operations, real System.arraycopy with overlap, and real array/object clone. Separate logical allocation identity from copied diagnostic fields and class-definition identity. Add alias equality and stable opaque identity-hash observations; collisions remain legal.

Complete when independent normal/exceptional models check copies and clones, bytecode/runtime evidence confirms real bulk/clone calls, requested intrinsification has separate evidence, and copied metadata cannot alias distinct logical allocations. Layout measurements do not become portable semantic assumptions.

## 19. Add monitors and targeted JIT transitions

Goal: exercise lock/header behavior and roots through compiler transitions.

Model monitor scopes and exception-safe release. Add capability-gated OSR, deoptimization, uncommon-trap, inlining, and spill-oriented workloads with relevant execution witnesses. Cover hash/lock interactions and caller/callee roots using the established frame contracts.

Complete when independent synchronization models match execution and evidence identifies the requested transition or interior compiled operation. Installation and entry/exit witnesses alone cannot certify continuous compiled execution. Missing target control remains an unmet capability.

## 20. Add weaker memory profiles

Goal: exercise opaque, acquire/release, and weak-CAS operations under sound permitted outcomes.

Define separate memory semantics, synchronization, spurious-failure rules, access footprints, and checking limits before exposing each mode. Retain the race-free SC profile and reject unsupported combinations rather than reusing its oracle for weaker ordering. Arbitrary data races require a separately justified contract.

Complete when independent memory litmus models agree with allowed/rejected outcomes and emitted operations preserve each mode. Check unsupported modes, mismatched evidence, and budget exhaustion without inventing stronger synchronization.

## 21. Add deliberate native roots and critical arrays

Goal: cover JNI local/global/weak-global roots and critical-array access.

Model native root scopes and distinguish deliberate roots from observer leaks. Add primitive-array critical regions with bounded coordination that obeys JNI call/blocking restrictions. Record whether copying/pinning and collector overlap actually occurred; requesting critical access does not establish either.

Complete when scope expiry, weak-root acquisition, exceptions, and concurrent collection match independent obligations. Leak guards still reject unintended observer roots. Target/JDK differences are explicit capabilities.

## 22. Add loader lifetimes and unloading

Goal: verify graphs whose classes and static roots can disappear.

Represent loader and class-definition identities independently of signatures. Support loader-owned types/statics, release, and unload evidence. Metadata and method registries must not retain loaders or conflate same-named classes.

Complete when duplicate signatures from distinct loaders remain distinct, retained loaders prevent invalid unload claims, and released loaders can be observed without diagnostic retention. Keep the known OpenJ9 assertion visible; latest-trunk reproduction remains separate work.

## 23. Add expected heap exhaustion

Goal: test allocation failure and soft-reference behavior without losing diagnostics.

Model expected heap-allocation failure separately from invalid GIR, checking limits, and native/metaspace/direct-memory failure. Preallocate emergency evidence and specify failure/cleanup state. Apply stronger soft-reference guarantees only to the VM failure domain that provides them.

Complete when bounded-heap cases preserve usable evidence and independent checks distinguish expected heap exhaustion from unrelated crashes or failed diagnostics. Validate both normal allocation and recovery paths.

## 24. Add finalization and resurrection profiles

Goal: cover post-reachability callbacks and resurrection without weakening ordinary reference histories.

Use a separate semantic profile with explicit asynchronous actor effects, reachability transitions, resurrection roots, and target availability. Revisit garbage retirement and cleanup ordering before enabling it; GC can change more than wrapper state in this profile.

Complete when independent small models cover callback execution, resurrection, and later release, with unsupported/disabled finalization explicit. Unobserved progress is not a reclamation guarantee.

## 25. Add special runtime adapters

Goal: reach VM paths ordinary nodes cannot reproduce.

Add focused adapters for virtual threads/continuations and thread-local roots, MethodHandle/call-site relinking, Strings/interning/deduplication, and direct-buffer cleanup. Each adapter needs portable semantic obligations, target-specific execution evidence, and a declared minimum JDK; preserve the Java 17 base profile.

Complete each adapter independently with conformance and unsupported-target checks. Add only mechanisms that exercise a distinct VM path or enable a stronger sound observation; no unchecked arbitrary-Java/Unsafe escape hatch.

## 26. Expand stress generation and campaign execution

Goal: extend `fuzz` to diverse workloads and parallel campaigns.

Expand generation with varied shapes, arrays, cycles, shared targets, allocation bursts, lifetime patterns, reference combinations, and supported synchronization. Use target measurements to guide physical workloads without changing semantic checks. Add parallel target jobs, campaign resource budgets, interruption handling, and summaries using stage 4's result categories. Worker and job limits remain independent.

Complete when seeds reproduce GIR inputs and retained artifacts explain each result. Concurrent jobs must isolate state, output, and deadlines; interruption must retain completed and partial runs. Crashes, timeouts, inconclusive checks, and unmet stress requirements remain visible. Rejected generation candidates must leave model state and bookkeeping consistent with the generation contract.

## 27. Reduce failures and validate the complete workflow

Goal: expose `reduce` and produce compact, credible reproducers.

Reduce GIR, graph shapes and contents, loop counts, workers, synchronization constraints, and optional profile settings. Use the shared validation, execution, and verification path for each candidate. Preserve the failure category and required stress evidence, use repeated trials for timing-sensitive findings, and retain the original case. Export reduced GIR, profile, generated Java, and replay instructions as a local reproducer bundle.

Complete when injected graph, trace, and execution defects reduce without losing their defining condition. A compiled concurrent failure must not become an unrelated interpreted or sequential failure. Exercise the CLI workflow from campaign finding through offline verification, repeated replay, and reduction, including interrupted runs, damaged artifacts, checker limits, and target resource exhaustion.

## Work boundaries

Each stage includes meaningful tests, artifact handling, and negative checks. CLI commands call shared engine operations; their implementation must not duplicate configuration, execution, or verification logic. Keep prototypes small and establish their contracts before expanding generation. Accepted contract changes belong in the owning design or implementation document.

The early generator uses a small supported subset. Mechanism stages include explicit semantic profiles and capability checks before generation uses them. Each stage may contain focused commits, but support is complete only across semantics, lowering, runtime, observation, target evidence, and independent checks.
