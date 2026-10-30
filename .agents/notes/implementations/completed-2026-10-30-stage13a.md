# Stage 13a array and controlled binding histories

Status: complete; approved by the user. This is the first slice of [stage 13](./2026-10-29-implementation-plan.md#13-complete-history-and-checkpoint-composition), whose [remaining work](./2026-10-30-stage13.md) stays open.

## Decisions

History emission supports reference/data arrays, including arrays as soft/weak referents, fresh worker-created arrays, and unrelated array declarations. Volatile/CAS/exchange element accesses use real array-element VarHandles with receiver/index coordinates. The model and clearing/SC algorithms remain unchanged; independent models exercise the added layouts.

Array identities come from actual published JVMTI weak tags. The native registry uses fixed storage and release/acquire publication; existing entries never move or change. Identity reads return primitive worker/site/occurrence cells without a Java registry, result allocation, or recorder lock. They pass the receiver through JNI, an explicit diagnostic effect. Registry capacity remains bounded. Version 1 captures and reference histories retain their original wire layouts.

Controlled caller-binding capture composes with sequential reference and contended histories. A shared projection path checks presence, thread/activation, and declaration coverage before graph matching. Only syntactic declaration keys come from history bounds; observed identities/contents come from target evidence. Missing declarations or physical origins remain inconclusive. Failure-prefix diagnosis uses the same coverage rules. Natural histories remain guarded until their liveness semantics are specified.

## Verification

The full build passes 330 Java tests: 245 unit, 83 HotSpot integration, and two observer checks. Strict Javadoc, native wire/loading checks, and four default/local-access capture fixtures pass on JDK 17/21. The fixtures read existing identities while another thread publishes 256 bindings and retain their node/array release checks. Independent clearing enumeration covers 9,216 object/array graphs; independent SC store-buffering permutations exercise object fields and long-array elements.

New integration cases cover initial/fresh array identities, separate creator/observer workers, soft/weak array targets, null and non-null exchange results, element CAS/volatile access, concurrent array ownership, caller-binding snapshots, missing declaration coverage, offline verification, and replay. Ordinary array races are rejected before compilation. An existing rejection test was removed because unused array declarations are supported; the creator test was reduced to its identity-publication operations after correctly exhausting the bounded all-schedules proof. Search limits were not raised globally.

Selected HotSpot Serial, Parallel, G1 C1/C2, both ZGC generations, and Shenandoah SATB/IU pass array histories. OpenJ9 gencon passes reference/atomic arrays and controlled graph capture; named-root array workers also satisfy compiled coverage. Selected offline checking/replay and an older stage 12 bundle pass. Local target profiles use the existing 60/120-second startup/run limits and a larger per-case search budget; committed defaults are unchanged.

OpenJ9 controlled local capture with VarHandle workers verifies its graph but leaves compiled coverage unmet. Verbose logs report compilationRestrictedMethod. The selected [VarHandleTransformer](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/compiler/optimizer/VarHandleTransformer.cpp) rejects VarHandle access in full-speed-debug mode; [CompilationThread](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/compiler/control/CompilationThread.cpp) maps that rejection to the observed status. The same workers compile under named-root observation without local-variable access. This is a supported diagnostic limitation, not a newly demonstrated GC bug; no fallback or weakened verdict is introduced. A later metadata-provider change could read retained class-file debug tables without requesting local-variable access, while still requiring physical root evidence.

Raw commands, captures, verbose logs, and results remain in ignored verification artifacts. The local bug archive is untouched and latest OpenJ9-trunk work remains deferred. [Critical review](../commits/completed-2026-10-30-stage13a-review.md) records the handoff.
