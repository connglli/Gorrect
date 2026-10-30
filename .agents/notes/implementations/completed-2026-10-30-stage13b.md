# Stage 13b conditional checkpoint visits

Status: complete; staged for user review. Scope is the second slice of [stage 13](./2026-10-30-stage13.md): conditional main-thread captures under controlled observation, preserving version 1 formats. Worker checkpoints and natural conditional/reference liveness remain guarded.

## Contract and work

HistoryPlan reserves all-taken storage/control bounds. CheckpointPlan extracts per-site maximum visits and lexical declaration keys without interpreting placeholder graph values. Live control accepts a bounded subset for histories, requires monotonically increasing per-site visits and capture sequences, and requires the final capture before completion. Exact cases retain their known order.

Offline control identities must match actual checkpoint markers in the main-thread scalar trace. This is a structural cross-check, not acceptance of branch choices: the independent oracle/history checker must still validate the full trace, choices, snapshots, and suffixes. Actual site identity selects binding coverage, so skipped captures cannot shift later frames onto the wrong plan entry.

Acquired bindings receive emitted method/local metadata and remain protected through the body, including captures. Missing graph observations remain inconclusive, while a definite impossible reference choice still violates the oracle even when later heap coverage is missing. Missing binding declarations never become guessed nulls or a graph mismatch.

Check deterministic branches, taken/skipped acquisitions, mixed repeated visits, arrays, contention-dependent capture presence, cross-channel damage, missing captures/declarations, completed suffixes, bounds, old bundles, verification/replay, and selected JVMs. Preserve the existing all-schedules audit and natural-history guard, the local bug archive, and deferred OpenJ9-trunk work.

## Integration and checks

Integration Lead merged Evidence Tests' two independent regression files and Contract Documentation's owning-doc/Javadoc changes. Oracle Review reproduced two failure-prefix defects in actual retained bundles, reviewed the fixes, and reran the original reproducers. The coordinator separately reviewed the completed metadata prototype and stage 14 proposal; they remain isolated ready-queue results and are not part of this review unit. The producer worktrees remain available for review. [Dashboards](../dashboard/README.md) contain ownership, communications, review feedback, and handoffs.

The stage 13b full build passes 350 Java tests: 252 unit, 96 HotSpot integration, and two observer checks, plus native wire/loading/capture/retention and strict Javadoc checks. Independent cases cover skipped/taken and nested bounded visits, actual occurrence numbering, acquired array roots, illegal branch markers, cross-channel capture damage, extra/missing binding declarations, missing later captures with earlier scalar/graph violations, and complete versus damaged failure prefixes. The previous orphan-identity/incomplete-scalar behavior remains guarded without hiding impossible present reads.

Selected HotSpot C1/C2 contended conditional captures and OpenJ9 object/array, never-taken, and contended captures pass graph verification. Selected exact-class replay and an older stage 13a bundle's verification/replay pass. Existing local 60/120-second startup/run limits and per-case history budgets are retained; committed defaults are unchanged. The local target script attempted one duplicate output name, which the engine correctly refused rather than overwrite; the existing compiled case and its verification/replay passed.

The retained-class metadata prototype and revised stage 14 proposal are separately reviewed and queued in their task-group worktrees. They are not included in this staged unit. No later GIR syntax, observer policy, or worker capture is enabled. All GIR/artifact formats remain version 1; the local bug archive and deferred OpenJ9-trunk work remain untouched.
