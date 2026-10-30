# Stage 11 access fidelity and observation boundaries

Status: complete; approved by the user. Scope is [stage 11](./2026-10-29-implementation-plan.md#11-preserve-access-modes-and-define-observation-boundaries). The accepted mechanism review is divided into stages 11 through 25; campaign expansion and reduction follow as 26 and 27.

## Decisions and review

Named roots use plain static storage. Direct accesses implement plain GIR operations; existing VarHandles supply explicit volatile/CAS/exchange effects on the same storage. No new abstraction or memory profile is needed. Worker publication still comes from Thread.start/join and Phaser barriers; primitive recording adds no shared recorder lock. Projection requires static roots, not volatile declarations, so old bundles remain readable.

The owning [GIR](../../../docs/gir.md#instructions) and [observation](../../../docs/observation.md#identity) documents distinguish minimum controlled lifetimes, logical/physical/class identity, diagnostic binding effects, and unsupported natural-liveness capture. Live-local projection and new identity providers belong to later stages. Formats remain version 1.

[RootAccessTest](../../../engine/src/test/java/io/connglli/gorrect/engine/run/RootAccessTest.java) checks actual captured field modifiers and compiled access instructions, including mixed modes on one root. It excludes initialization stores when checking GIR write bytecode: otherwise a missing plain program write could pass. Final graphs alone would not expose accidental volatile storage.

## Verification

The normal build passes 279 Java tests (218 unit, 59 HotSpot integration, two observer), native wire/loading/capture checks on JDK 17/21, and strict Javadoc. Four new root-access cases cover plain/mixed modes and mixed-mode offline verification/replay on JDK 17/21. Selected HotSpot fastdebug G1 C1/C2 cases satisfy compilation requirements and offline verification. OpenJ9 gencon passes mixed-root and contended execution, offline verification, mixed-root replay, and verification of an older bundle.

A one-visit compiler probe correctly leaves compilation unmet because its profile reserves the first visit for warm-up. The existing three-visit workload is used for compiler coverage. Selected fastdebug startup exceeded the original ten-second limit; those results are retained, and successful local retries extend only startup/run limits to 60/120 seconds. Committed defaults and JIT settings are unchanged.

Commands and run evidence remain in ignored local verification artifacts. The local bug archive is untouched, and latest OpenJ9-trunk reproduction remains deferred. Critical review checks source, tests, contracts, plan dependencies, and artifact compatibility; no outstanding code issue is identified.

[VarHandle](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/invoke/VarHandle.html) defines access-mode effects independently of storage modifiers; [reachabilityFence](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Reference.html#reachabilityFence(java.lang.Object)) establishes a minimum strong lifetime rather than reclamation after release.
