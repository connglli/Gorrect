# Mechanism coverage review

Reviewed baseline: `09c4c81`. Status: review complete; the user accepted the recommendations into stages 11 through 25 of the [implementation plan](../implementations/2026-10-29-implementation-plan.md). Scope is graph semantics, execution, VM controls, observation, and checking. Fuzz generation, campaign scheduling, and reduction are excluded. No runtime/model behavior is changed by this review. Formats remain version 1. The untracked local bug archive and deferred OpenJ9-trunk work are untouched.

## Conclusion and completeness criterion

The implementation is a useful bounded core, not a complete GC mechanism toolkit. A primitive is complete only when its semantics, lowering/runtime behavior, oracle, capture coverage, target capability, and stress evidence compose. Merely parsing or executing it is insufficient. Keep a small semantic core with explicit optional adapters rather than an unrestricted Java/Unsafe escape hatch whose effects cannot be checked.

Existing strengths are logical identities, cycles/aliases, explicit controlled handle lifetimes, direct field/array mutations, immutable obligations, conditional soft/weak histories, bounded SC race-free contention, isolated worker recording, independent compiled-frame evidence, HotSpot cycle evidence, and retained raw captures. Incomplete coverage does not pass. These boundaries should survive extensions.

## Confirmed weaknesses

| Priority/type | Finding and consequence | Proposed action |
| --- | --- | --- |
| P1 translation fidelity | [JavaEmitter](../../../engine/src/main/java/io/connglli/gorrect/engine/emit/JavaEmitter.java) emits every named root as a volatile static field. GIR plain-root operations use direct accesses, acquiring volatile ordering and restricting optimization. Compiled bytecode confirms ACC_VOLATILE. This is stronger execution, not evidence that the existing supported SC oracle is unsound. | Preserve requested access modes; make deliberate diagnostic strengthening explicit. Test emitted field/access modes, not only final graphs. |
| P1 observation coverage | Live-handle checkpoints are rejected; graph projection covers named static roots only. Fenced lifetimes and array JNI bindings intentionally perturb liveness/escape. | Define separate controlled/diagnostic and natural-liveness observation contracts. Add live worker/caller root obligations with validated mappings or semantic-use witnesses; no hidden strong registry. |
| P1 composition | Any array shape combined with reference or contended history is rejected, even when an unrelated array is merely present. Conditional checkpoints and worker checkpoints are also absent. | Complete combinations before multiplying primitives: array histories/atomics, conditional captures, and coordinated mid-worker observation. Preserve explicit scope, phase, and coverage conditions. |
| P1 scale | OracleLimits.DEFAULT fixes 100,000 steps, 10,000 objects and 100,000 slots. Allocated garbage stays in model state; every operation emits evidence. A 12,000-iteration allocate/release program fails with object-budget exhaustion before compilation. | Add sound retirement/summaries for provably unrecoverable objects and bounded live-set churn. Define explicit allocation-pressure semantics and actual allocation evidence. Raising limits alone does not solve growth. |
| P1 GC path coverage | Current requests are System.gc or WhiteBox fullGC; no young/concurrent-cycle/phase primitive exists. Pause progress and worker overlap do not establish mutation during a particular collector phase. OpenJ9 cycle completion is unavailable. | Add capability-gated collection kinds and phase controls with independent event certificates. Keep collector policies outside graph semantics. |
| P1 representation constraints | Shapes fix array length and long-only payload counts; node identity lives in final object fields. Real clone would copy those fields; interfaces/inheritance and duplicate class signatures are rejected by native registration. | Design logical/physical identity and class identity before clone, typed layouts, or dynamic loaders. Preserve alias/freshness checks and account for observer effects. |
| P1 execution expressiveness | Scalar literals/copies and CAS results exist, but no scalar arithmetic/comparison branch, dynamic index/length, general call/return, or expected exception path exists. | Add a compact typed expression/control basis and frame-aware handle identities. Avoid implementing a second general Java language. |

## Focused checks

Read-only source review was paired with ignored local probes. A live local at checkpoint, arrays plus weak references, and arrays plus root CAS returned UNSUPPORTED without compiling. Discarded churn returned CHECKING_LIMIT for objects. A plain-root GIR case emitted a field with ACC_PUBLIC/ACC_STATIC/ACC_VOLATILE. Probe sources, commands, results, and bytecode are under untracked verification artifacts; committed notes contain no machine-specific paths. These are capability/translation checks, not newly demonstrated GC corruption.

## High-value mechanism families

### Roots, lifetimes, and observation

Support stack/register locals, shared statics with faithful modes, thread-local roots, deliberate JNI local/global/weak-global roots, and eventually long-lived/virtual-thread contexts. The current observer rejects any JNI global root to a workload node; legitimate modeled JNI roots must be distinguished from observer leaks.

Controlled lifetimes use explicit fences to guarantee minimum reachability. Natural liveness must follow observable future uses; simply removing fences and still requiring lexical-scope objects at a checkpoint would create false violations. Heap walks, stack inspection, recording, and identity binding have different effects. Provide declared observation profiles and calibration against no-agent/tagging/witness/capture modes. A minimally instrumented semantic read/alias path should complement diagnostic heap capture; neither is universally transparent.

Class/loader and handle identities need activation context. Handles indexed only by a declaration site cannot represent multiple active recursive frames. New root mappings must cover caller and worker frames, and capabilities must admit missing optimized-local origins without declaring a liveness bug.

### GC controls and evidence

Add young collection, full collection, concurrent-cycle start/await, and supported phase rendezvous. Old-to-young publication followed by young GC is a distinct test from full-heap tracing. Concurrent mark deletion/insertion, remembered sets, relocation/load barriers, evacuation/promotion pressure, and humongous/pinned regions need relevant phase/placement evidence.

Target measurements of allocation bytes, object/array size, generation, region category, installed compilation/OSR/deoptimization state, and phase overlap are stress facts, not portable graph truths. Existing WhiteBox source supplies youngGC, concurrent control, generation and size queries, but client/build/collector compatibility must be exercised rather than assumed. OpenJ9 needs equivalent evidence or an unmet capability.

No portable rule should equate System.gc return with clearing, enqueueing, or reclamation. Stronger progress assertions require explicit VM contracts and adequate root coverage; ObjectFree/weak/phantom observations can be evidence but are not interchangeable meanings of death.

### Arrays, copying, identity, and layouts

Add dynamic array lengths/indices, typed primitive/reference arrays, range operations, System.arraycopy (including overlap), and array/object clone. These exercise VM bulk barrier and intrinsic paths that scalar store loops do not establish. Add mixed primitive layouts, inheritance, interfaces, and final/volatile payload fields as declared layout capabilities. Large physical padding can use sampled sizes without turning size estimates into semantic assumptions.

Real clone exposes an architectural constraint: final diagnostic IDs would be duplicated and Cloneable currently fails native layout restrictions. Choose an identity provider/profile that supports real copying, rather than implementing clone as ordinary reconstruction and claiming intrinsic coverage. Keep logical identity, physical allocation identity, class-definition identity, and opaque identity hashes distinct. Reference equality and stable identity-hash constraints are useful observables; hash collisions remain legal.

### Calls, synchronization, and JIT transitions

Add calls/returns, bounded recursion, normal/exceptional scope exit, scalar predicates/branches, and monitor scopes. These exercise root maps across frames, inlining, spills, OSR, deoptimization, uncommon traps, unwinding, inflated headers, and hash/lock interactions. Querying installed code and entry/exit frames does not prove a particular interior operation or tier executed continuously.

Retain race-free SC checking for supported shared-memory programs. Monitors need modeled synchronization and exception-safe release. Acquire/release/opaque or weak-CAS profiles need their own permitted-memory outcomes; do not run them through the SC oracle unchanged. Arbitrary races are not a prerequisite for the first useful expansion.

Expected Java exceptions must differ from invalid GIR. The existing rejected-operation contract is transactional; a valid Java operation can have effects before throwing (for example a checked reference-array copy). Specify both normal and exceptional post-states, with precise access footprints, before adding those paths.

### Reference objects and processing

Make reference wrappers first-class enough to alias, root, move between containers, target other wrappers, and outlive their original owner. The current slot abstraction always creates a fresh incarnation for a soft/weak store and hides the wrapper from ordinary handles. This prevents several real Reference-object graphs.

Add named/optional queues, explicit enqueue results, phantom references, and Cleaner actions. Phantom acquisition cannot reuse soft/weak get semantics. Cleanup actions are asynchronous modeled actors with explicit graph effects. Finalization/resurrection should be a separate later profile because they invalidate the assumption that GC only changes wrapper state and complicate garbage retirement.

Expected heap allocation failure is also valuable. Soft-reference handling has stronger constraints around heap exhaustion than ordinary explicit-GC return; identify the allocation failure domain before asserting such a guarantee. Add preallocated emergency evidence so OOM testing does not depend on successful post-failure allocation or get mislabeled by failed diagnostics.

### Native, class-loader, and special runtime paths

Add deliberate JNI-root scopes and primitive-array critical access. Critical-region code must obey JNI restrictions, with bounded peer coordination; a request for pinning does not prove a copy was avoided or GC overlapped. Collector/JDK support differs, so record actual effects.

Add loader/type instances, static roots tied to a loader, loader release, and unload evidence. Class names alone are insufficient: the native registry currently rejects duplicate signatures, and a class/method registry must not keep the loader alive. This extends a failure surface already reached incidentally by the Metronome/JVMTI finding without claiming that finding is fixed or new upstream.

Virtual threads, continuations, MethodHandle/call-site relinking, Strings/interning/deduplication, and direct-buffer cleanup are later adapters where ordinary node graphs cannot reproduce special VM paths. Keep Java 17 base compatibility and advertise newer target features explicitly.

## Capability and checking architecture

Expose structured capabilities with legal combinations, preconditions, semantic effects, memory footprints, observer profile, required target evidence, and unsupported reasons. This is a contract catalog, not a new plugin framework. It makes configured availability, exercised behavior, and verified obligations separate facts. Every mechanism needs model/oracle semantics, faithful lowering, runtime support, observation, positive/negative independent tests, and both supported and unsupported-target checks.

Checker capacity is a mechanism boundary. Incremental obligations, sound partial-order reduction, exact canonical state merging, and garbage summaries can extend supported histories. They must preserve all-schedules race/precondition proof and budget accounting. Partial validated observations may refute an obligation without establishing a full pass; missing facts must never become expected defaults.

## Suggested implementation order

1. Correct root-mode fidelity and define observation/lifetime/identity contracts, with explicit diagnostic effects.
2. Complete array/reference/atomic composition and scalar predicates; specify frame-aware handles, expected exceptions, and first-class wrapper/queue identities.
3. Add young/concurrent GC controls and allocation/placement/phase evidence, plus a scalable bounded-live-set allocation mechanism.
4. Implement bulk copy/clone, call/return/unwind, monitors, and targeted JIT transitions on those contracts.
5. Add JNI roots/critical arrays and loader lifetime/unloading; then newer-thread and special-runtime adapters.

The implementation plan splits this order into reviewable mechanism stages before stress-generation expansion. Each item must add a distinct VM path or a stronger sound observation, and must have a small independent conformance test. Broadening APIs without closing end-to-end coverage would not meet the goal.

## Primary references

[JVMTI heap traversal and ObjectFree](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html), [JNI roots and critical-array restrictions](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html), [JMM synchronization](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html), [VarHandle access modes](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/invoke/VarHandle.html), [System.gc limits](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#gc()), [Object.clone](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Object.html#clone()), [Reference enqueue/fences](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Reference.html), [SoftReference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/SoftReference.html), [PhantomReference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/PhantomReference.html), [reachability/finalization/unloading](https://docs.oracle.com/javase/specs/jls/se17/html/jls-12.html), and [virtual-thread API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html). Current HotSpot WhiteBox controls were also inspected in the selected target source tree.
