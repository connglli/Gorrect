# Stage 5 implementation

Status: complete for HotSpot Serial GC, conflict-free parallel workloads, and compiled-frame stress witnesses. Extend the existing CLI path rather than creating a separate fuzz runner. GIR and artifact formats remain at version 1 during initial development; intermediate layouts do not require compatibility branches. No commits until review; stage all changes at handoff. Scoped commit titles are required.

## Agreed implementation choices

* GIR 1 includes `require` stress declarations, named workers in structured `parallel` blocks, direct worker `barrier` statements, and `collect`. Worker IDs are internal declaration-order indices. Nested parallel blocks, worker checkpoints, and nested barriers are rejected. Worker locals cannot capture caller bindings and survive top-level barriers.
* The exact oracle serially interprets each phase, records concrete root/field/element read and write footprints, and rejects cross-worker write/read or write/write overlap. Checkpoints follow joins. Worker contexts preserve locals across phases; allocations use worker/site/occurrence IDs.
* Trace events include worker, parallel operation, visit, and phase. Compare per-worker order, not file order across workers. Include start/end, barrier/phase-start, and main join markers. Allocate primitive recorder buffers from per-worker scalar-event counts within the profile total bound; only actual appends become evidence.
* Compilation settings and the optional external WhiteBox client belong in profiles. Modeled parallel visits provide the warm-up prefix; the runtime requests installation only after the configured number of visits. Do not execute unmodeled warm-up mutations.
* WhiteBox installation alone does not establish compiled execution. JFR custom stack events at worker entry/exit and before/after collection requests must identify the actual generated method as compiled. The local prototype confirms JFR reports a compiled worker frame. Requested compilation tiers describe installed-code requests, not a guarantee about every executed instruction.
* Runtime evidence contains only scalar worker windows, joins, GC pause counters, and installation results. A collection request passes no workload reference through JNI. `gc-during-workers` requires observed pause progress during a worker collection request; it does not claim concurrent collector completion.
* Stress requirements are separate from semantic verdicts and must hold together within the same worker-group visit. Missing or interpreted JIT witnesses cannot pass compiled stress. Keep raw JFR and scalar artifacts.
* The seeded generator uses disjoint mutable partitions and shared immutable objects. It clears a root while retaining the object locally across collection, calls, allocations, and a barrier, then republishes it. `fuzz` adds workload, workers, duration, seed, and output options; VM configuration remains profile-only. Saved cases replay through RunEngine.

## Validation and limits

Fixture tests and short campaigns validate implementation behavior; effectiveness at finding real GC bugs remains unestablished.

The clean build, strict Javadoc, Java tests, and native wire check pass. JDK 17/21 tests cover worker execution, barriers, local lifetimes across collection, independent recording, initial trace framing, deterministic generation, and campaign bounds. Real JFR fixtures reject installation-only claims, interpreted frames, wrong methods/signatures, duplicates, and mismatched thread evidence. Missing synchronization evidence remains an extraction failure.

The selected HotSpot fastdebug target passes seeded concurrent JIT campaigns with joint overlap, compiled-frame, and GC progress evidence. An interpreted launch preserves the semantic verdict while leaving compiled execution unmet. Verification works without the original profile or WhiteBox client; replay uses exact classes and bundled support while recording installation changes. Repeated measured visits reuse installed code. Bytecode inspection confirms direct graph operations and primitive worker recording.

Compilation requests do not prove a continuously compiled invocation or the tier active at a witness. One successful collection request is retained per phase; earlier requests can remain unproven if a later one replaces their summary. Missing witnesses remain unmet coverage. Live-binding checkpoints, contended histories, other collectors, and OpenJ9 execution follow their planned stages.

The supplied local WhiteBox client logs an unused cgroup registration warning. Required compilation and full-collection methods work; this establishes compatibility only for the exercised API subset, not every WhiteBox method.
