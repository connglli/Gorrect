# Stage 16 GC control proposal

Status: proposal for review, not accepted behavior or a production implementation. This note scopes [stage 16](../implementations/2026-10-29-implementation-plan.md#16-add-collection-and-phase-controls-with-evidence) while scalar/frame/exception work continues independently. GIR and artifacts remain version 1.

## First slice

Add a profile-owned HotSpot young-collection policy using the existing `collect;` instruction. Draft names are `gc.control: "whitebox-young"` and requirement `young-gc`; the current parser accepts neither. Keep the first supported collector set to Serial, Parallel, and G1. This changes request selection and stress evidence, without changing portable graph/reference semantics, GIR syntax, or frame lifetimes.

A capability report says whether the configured method and event capture are usable. A returned request says only that the call returned. The `young-gc` requirement needs a completed qualifying young-event interval for every executed request; it certifies that the young path ran, without claiming a young-only run or successful promotion. These are separate facts. Do not substitute a full collection, allocation burst, or `System.gc()` when young control is unavailable.

The profile policy applies to every `collect` in the case; this first slice cannot mix young and full requests at different sites. Per-site collection kinds, concurrent-phase operations, placement queries, and OpenJ9 certificates are later slices. The [existing `gc-completion` contract](../../../docs/usage.md#profiles) continues to require whole-heap/major cycles; its combination with the new young policy is initially unsupported rather than reinterpreted.

## Selected VM facilities

Source inspection checked HotSpot's recorded build revision `ab5cf6f46553c67020133fcd6c5307a8b7841436` and OpenJ9 revision `6e16eb1a4fc681949ccc6b100b984ab217d8cb3f`. The probes use the existing selected JDK21 installations. Availability below is limited to those sources and runs, not every JDK or collector mode.

| Facility | Source and observed boundary |
| --- | --- |
| HotSpot young request | [WhiteBox `youngGC`](https://github.com/openjdk/jdk21u-dev/blob/ab5cf6f46553c67020133fcd6c5307a8b7841436/src/hotspot/share/prims/whitebox.cpp) requests cause `WhiteBox Initiated Young GC`. Selected Serial, Parallel, and G1 produce `DefNew`, `ParallelScavenge`, and `G1New` respectively, wholly within the request span; matching JFR young events corroborate their kind. |
| Other HotSpot modes | The same call produces `ZGC Minor` on selected generational ZGC, `Z` on non-generational ZGC, and `Shenandoah` on selected Shenandoah SATB. A method name or cause containing "Young" is insufficient to certify a young generation. Generational ZGC needs its own mode-aware extension; the other two runs supply no young certificate. |
| HotSpot full request | `fullGC` requests a full cycle and sets the soft-reference clearing policy. The selected G1 comparison reports `G1Full` with the full-request cause. This control effect is diagnostic policy, not a portable clearing or reclamation guarantee. |
| HotSpot concurrent control | [WhiteBox](https://github.com/openjdk/jdk21u-dev/blob/ab5cf6f46553c67020133fcd6c5307a8b7841436/test/lib/jdk/test/whitebox/WhiteBox.java) exposes support discovery, acquire/release, run-to-breakpoint, and run-to-idle. Selected support queries are false for Serial/Parallel and true for G1, both ZGC modes, and Shenandoah. Only G1's phase path was exercised: both marking stops and release are corroborated by VM breakpoint logs and the same JFR old-cycle ID. |
| Object placement/size | WhiteBox exposes `isObjectInOldGen`, `getObjectSize`, `g1IsHumongous`, and `g1RegionSize`. These were inspected, not exercised here. Passing an object through these diagnostic APIs can affect escape/materialization; a sampled location is not a guarantee about later placement or physical allocation. |
| OpenJ9 requests | Internal `com.ibm.oti.vm.VM.localGC/globalGC` are callable with an explicit module export. [Local dispatch](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/gc_base/modronapi.cpp) uses the default memory subspace. The gencon probes report scavenge/global activity respectively; the balanced `localGC` probe produces a global cycle. "Local" is not a portable young promise. |
| OpenJ9 cycle/phase evidence | The [cycle extension callbacks](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/jvmti/jvmtiHook.c) expose start/finish without cycle ID, kind, or cause. Safe interval pairing and additional kind/request evidence would be needed. Verbose GC supplies richer records, but no production correlation or equivalent phase-control certificate is established by this investigation. |

## Request and certificate contract

Reuse `CollectionEvidence`, the existing JFR request envelope, run/process identity checks, and validated execution's operation/worker/group/round/phase/ordinal/thread identities. Add a separate young classifier in the collection verifier rather than weakening its whole/major classifier. For the initial collector set, require the configured control, normal return, cause `WhiteBox Initiated Young GC`, the exact collector-specific young name above, and a positive complete interval wholly inside the matching request. Earlier cycles, wrong causes, full-only fallback, and pause counters cannot satisfy it. Existing coalesced-request rules remain valid when one qualifying cycle lies inside each request.

An unavailable control or unsupported collector/profile combination reports unsupported before workload execution. Missing, damaged, truncated, or unrecognized event evidence leaves the requirement unmet with an extraction diagnostic; it is never a graph mismatch or a complete pass. Preserve a separately proved graph violation alongside such gaps. Native or Java control failure retains runtime-failure classification; a hung native call remains subject to the existing external target deadline. A startup self-probe precedes workload construction and cannot satisfy an executed GIR request.

Request APIs may trigger safepoints, reference processing, compilation changes, and allocation/timing effects; JFR also perturbs execution. None establishes continuous compiled execution, mutation during a specific GC phase, generation survival, pinning, or reclamation. Portable strong-graph and reference-history obligations remain unchanged.

## Concurrent and placement follow-ups

The [breakpoint implementation](https://github.com/openjdk/jdk21u-dev/blob/ab5cf6f46553c67020133fcd6c5307a8b7841436/src/hotspot/share/gc/shared/concurrentGCBreakpoints.cpp) has process-global state and waits without a Java timeout. A later adapter needs one controller-owned lease, bounded host supervision, `finally` release on every exit, and explicit handling of missed/aborted cycles. Reuse stage 13's worker rendezvous for mutator cuts; do not add a competing worker scheduler. A collector stopped at a breakpoint and mutation between controlled stops are different coverage claims. Certify cycle/phase identity and the actual mutation interval before claiming phase overlap.

Generation, region, and size observations need logical-object identity, sampling points, valid collector-specific interpretation, and declared diagnostic effects. Add old-to-young, humongous, relocation, and promotion workloads only with the relevant evidence. OpenJ9 can add supported request policies independently, but completion/phase claims stay unavailable until an independently checked certificate exists.

## Integration units and checks

| Unit | Ownership and completion |
| --- | --- |
| 16a: young requests and certificates | Update `TargetProfile`, VM launch/capability handling, `VmControl`/`HotSpotControl`, and `CollectionVerifier`; reuse existing collection records. Update profile/usage documentation and focused tests. No GIR, graph/oracle, emitter, or frame-protocol changes are needed, so this can proceed beside stage 14. |
| 16b: request-kind extensions | Add generational-ZGC minor evidence and investigated OpenJ9 controls as separately gated capabilities. Per-site young/full selection needs an agreed GIR control contract and emitter/trace updates; the first slice does not preselect that syntax. |
| 16c: concurrent phases and placement | Agree lease/cycle/phase/cleanup and sampling contracts, then integrate with the finished worker rendezvous. Add independent mutation/phase and placement certificates before enabling their stress requirements. |

For 16a, test normal requests on Serial/Parallel/G1 with and without JIT settings; repeated, conditional, and worker requests; JDK17/21 compatibility; offline verification and exact-class replay. Independent evidence fixtures must reject absent/duplicate/wrong-context requests, incorrect causes/kinds/VM identities, incomplete/outside intervals, full-only fallback, ignored or unavailable controls, missing JFR, and exhausted evidence bounds. Preserve old version 1 bundles and the whole/major completion tests. Failure injection must retain graph refutations, timeout behavior, and usable diagnostics. No new CLI switches belong to this work.

## Investigation checks and limits

Eight bounded HotSpot invocations cover six young-policy collector/mode cases, G1 full comparison, and one G1 concurrent path; three bounded OpenJ9 invocations cover gencon local/global and balanced local calls. All eleven return successfully and a separate assertion script checks their narrow reported outcomes. Java probe compilation uses host JDK21 with `--release 17 -g -Xlint:all -Werror`; no JDK17 execution, graph workload, compiled-frame capture, or GC-bug claim is made.

Sources, commands, full output, JFR/XML/log records, and hashes are retained in ignored `.gradle/verification/gc-controls/` in the assigned experiment worktree. The reused WhiteBox client emits an unrelated `validateCgroup` native-registration warning; the exercised methods return, but whole-client compatibility is not established. Production integration still requires a compatible client and per-method exercise. No target build/download, trunk test, production edit, or local bug-archive change belongs to this investigation.
