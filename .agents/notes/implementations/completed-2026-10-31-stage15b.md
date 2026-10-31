# Named reference queues

The initially-null registered slice is implemented. [GIR](../../../docs/gir.md#reference-histories) and [observation](../../../docs/observation.md#tagged-reference-targets) define supported behavior; source contracts own the exact operations and decoding.

Critical review fixed incomplete poll-prefix refutations, inconsistent shared object/class/value records and ignored inactive OpenJ9 array slots. Independent semantic, native/API, decoder and final composition reviews confirmed that coverage gaps preserve definite resource identities and ordinary graph refutations.

The final build passes 815 Java tests (600 unit, 213 target and two observer), nine native tests, JDK 17/21 runtime checks, strict compiler/Javadoc and both hooks. Decoder review exercised 31 copied bundles; final composition added 30 assertions and four copied-bundle checks. Existing author evidence covers selected HotSpot/OpenJ9 gencon execution/replay; a fresh combined OpenJ9 matrix was not run. No continuous compiled-observer claim follows.

Automatic notification, broader wrapper lifetimes and concurrent/natural capture remain separate work. The design rationale and its pending-root counterexample are retained in the [completed design note](../designs/completed-2026-10-31-reference-queues.md).
