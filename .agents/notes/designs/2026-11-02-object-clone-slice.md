# Ordinary object clone proposal

Status: proposed stage 18 continuation; no implementation is accepted. Extend existing `clone result owner` to ordinary object shapes, retaining GIR/artifact version 1 and the [array-clone contract](../implementations/completed-2026-10-31-stage18a.md). `Shape` continues to describe semantic slots, without a user-facing Cloneable qualifier.

## Semantics and admission

A live non-null ordinary object clones to a fresh allocation occurrence with the same nominal shape, reference targets and normalized int/long payloads. Self-references still point to the source; aliases stay shared, while later source/clone slot writes are independent. Constructor effects are absent. Same-object distinguishes allocations; [opaque hashes](../implementations/completed-2026-10-31-stage18c.md) retain per-allocation stability and permit collisions.

Admit strong sequential controlled execution with plain accesses. Whole-source guards include declarations, unused definitions, skipped arms and zero repeats; reject wrappers/reference processing/queues, workers/concurrent histories, natural observation, native roots and method definitions/calls whenever ordinary object clone is selected. Apply shared guards at frontend, observation, exact/history and direct backend entries before evidence exits. Preserve existing array-only compositions, including native-root and forthcoming call programs; their acceptance does not prove object-clone composition.

Null, scalar, released, missing or wrapper owners are invalid workloads; unsupported combinations are unsupported. Neither becomes a modeled Java exception. Exception syntax, constructors-as-clone, a second heap model and arbitrary native calls are outside this slice.

## Real copying and diagnostic identity

Generalize `GirValidator`'s existing lexical clone-shape map to infer ordinary objects and arrays once. Select only ordinary shapes appearing as clone owners, including skipped sites. For those final generated classes, emit exact `java.lang.Cloneable` and one typed helper invoking `(T) super.clone()`. [Object.clone](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Object.html#clone()) supplies the actual shallow copy.

Keep `idWorker` final: admitted execution stays on worker zero. Make only selected shapes' `idSite` and `idOccurrence` nonfinal. The helper assigns these two diagnostic fields through ordinary Java stores before returning the copy. They never become graph payload. Avoid JNI/Unsafe/reflection final-field rewriting and its stale-read concerns. Extending array weak-tag identity to objects would add binding/lookup machinery and JNI materialization effects without a current requirement; retain field identity here.

`ObservationLayout.ObjectType` gains an optional source-derived clone-identity marker, absent for legacy layouts. Source/layout checks require exactly the inferred selection; the marker authorizes only these two nonfinal diagnostic roles. `SnapshotProjector` validates actual descriptors/modifiers, reads actual identity values and retains duplicate-identity rejection. All other identity roles keep their existing final-field requirement; Cloneable alone authorizes no relaxation. No ordinary-object clone means identical generated Java/layout bytes, including method-free legacy source.

Native registration remains a direct-Object-subclass contract. For ordinary nodes only, allow either no interfaces or exactly the bootstrap `Cloneable` mirror, compared with standard JNI identity checks. Require that marker to have no fields or superinterfaces; retain no-interface root-holder and wrapper restrictions. [JVMTI field indices](https://docs.oracle.com/en/java/javase/17/docs/specs/jvmti.html#jvmtiHeapReferenceInfoField) therefore retain zero interface contribution. This is narrow marker admission, without general inheritance/interface support or new wire framing.

## Effects, bounds and acceptance

Preflight declaration/owner, step, cumulative ordinary-allocation and slot limits before model effects. Commit the fresh vertex, binding, occurrence, counters and event together; rejection preserves all state. Retirement never refunds charges or identities. Java computes a candidate occurrence without incrementing, performs clone/diagnostic stores, then commits the result/counter. Clone/resource failure leaves the source and committed counter unchanged, aborts execution and cannot pass; an unexposed physical allocation is not rollback of the VM heap. Later recorder/capture failure retains existing incomplete-prefix handling, without undoing completed effects.

Audit map consumers in `HistoryPlan`, emitter cleanup and the in-flight `MethodProgram` bounds. Preserve source traversal versus execution quotas, both branch arms, repeat multiplication and saturation; count object clones toward ordinary allocation/slots without charging array-binding storage. Calls remain guarded until their own resource/frame proof covers objects.

Acceptance requires independent shallow/self/alias/mixed/empty/repeated models, transactional failures and cumulative-budget boundaries; selected-marker/modifier/identity/evidence corruption; real `invokespecial Object.clone` and Java stores without clone constructors; warning-free release-17 compilation, JDK 17/21 run/verify/exact-class replay with completed-GC certificates, both metadata providers, selected HotSpot and separately qualified OpenJ9. Preserve old source/layout bytes and bundles. Diagnostic stores, copying, hashes and capture can affect optimization/liveness/timing; compilation requests establish neither intrinsification nor continuous compiled execution.

A standalone release-17 probe passes interpreted/compiler-request runs on installed JDK 17, host JDK 21 and selected HotSpot 21. It checks copied final IDs, mutable fresh IDs, shallow mixed slots and absent constructor calls; bytecode confirms real clone/stores. It establishes no Gorrect integration or observer qualification.

Author: GPT 6.1 Sol <codex@openai.com>.
