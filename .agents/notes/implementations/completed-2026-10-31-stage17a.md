# Stage 17a phase boundary audit reuse

Status: implementation, both independent reviews and coordinator integration complete; awaiting user review. This is the first slice of [stage 17](./2026-10-29-implementation-plan.md#17-scale-allocation-and-checking), following the [measured design](../designs/2026-10-30-history-scaling.md). No language, profile, artifact, scheduler or budget change is introduced.

## Scope and invariant

Strong-reference safety audits may reuse an equal complete state after a matched multiworker barrier or checkpoint. Every incoming prefix still checks dynamic validity and races before clocks reset. The immutable key includes shared heap/roots, occurrence and semantic resource counters, all worker/caller scopes and released names, scalar/reference bindings, actual control/phase/group/round, continuations and cursors. Key creation reads explicit contexts rather than changing the model's active context. The boundary flag belongs only to its transition and is not copied into later steps.

Instruction and shape eligibility is positive and conservative. Reference processing and trace matching keep their join-only reuse. A single worker has no alternative phase prefixes, so it keeps its existing small state bound without retaining unproductive phase keys. New retained entries and key construction use existing state/work accounting; limits still abort proof. Garbage retirement, intra-phase reduction, wider reference/frame keys and other stage 17 scaling remain separate.

## Checks and measurements

Unit cases cover immutable normalized keys, scalar and nullable-handle histories with equal final heaps, both CAS winners, reserved names, semantic counters, invalid/racy prefixes and suffixes, repeat/cut composition and unchanged weak-history accounting. A separate small scalar scheduler checks 24 binary initial/write/fault combinations. A negative control omitting context snapshots incorrectly accepts the unsafe-left CAS case; the complete candidate rejects either unsafe winner.

Read-only counters agree with unchanged candidate outcome/work for all six host audits. Default-bound measured work/forks are baseline 9253/171 for one visit and 19311/334 for two; preexisting roots 15867/171 and 31925/334; publication 83773/535 and 170497/1062. Two publication visits previously failed to complete under the supported 10,000,000 bound; this candidate completes at 170,497 under the unchanged 1,000,000 default. Retained+pending states peak at 27 for that case. These are host-audit measurements, not target allocation or GC facts.

Generated publication with real collection, caller roots and repeated worker cuts passes Java 17/21 run/verify/replay at default history bounds. Selected HotSpot C1/C2 and OpenJ9 gencon pass the same workflow. C1/C2 profiles explicitly satisfy compiled-execution; no escape-analysis flag is disabled. These witnesses establish the particular escaping workload's entry/exit coverage, not continuous or generally transparent compiled capture. OpenJ9 does not request compiled coverage in this case.

The final combined primary check, including natural conditional capture and young controls, passes 507 Java tests: 356 unit, 149 HotSpot integration and two observer checks, with no failures/errors/skips. Strict compiler/Javadoc checks, native wire/loading/observer and worker-cut checks on Java 17/21 pass. Existing changed-file hooks pass. An isolated older version 1 bundle preserves its satisfied graph and expected unmet compiled-coverage result; its original files remain untouched. Ignored instrumented copies, counter comparisons, commands, negative controls and target bundles retain reproducible evidence; no target build/download/trunk test or local bug-archive change belongs to this unit.
