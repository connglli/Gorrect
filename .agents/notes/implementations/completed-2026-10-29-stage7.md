# Stage 7 contended histories

Status: complete for bounded race-free ordinary-object histories. Stage changes for user review before committing. GIR and artifact formats remain version 1.

## Implemented choices

* Volatile root/reference/data access, strong CAS, exchange, and if-present scopes extend GIR. VarHandles execute directly in generated methods; only used variables receive handles. Exchange-to-null supports ownership transfer. [GIR](../../../docs/gir.md#contended-histories) and instruction Javadoc own the contracts.
* Exhaustive bounded SC exploration proves preconditions and race freedom before launch. Vector clocks model volatile publication, failed CAS reads, successful atomic writes, and phase synchronization. All earlier writes to a volatile variable contribute to later reads. Keep ordinary accesses even when later atomic accesses touch the same slot.
* Verification repeats the audit, then matches per-worker results, identities, branches, barriers, and snapshots. Recorder file order supplies no cross-worker order. Every worker and suspended caller contributes strong handles to GC reachability. Queue delivery remains separate from clearing and reclamation.
* Search forks share cumulative work accounting. Retained pending and memo states count their GC possibilities toward the state bound. Work includes graph/context copies, traversal, and race evidence. Equivalent states merge only after joining workers; keys preserve the complete heap, scopes, counters, remaining tasks, cursors, budgets, and canonical clearing states. This supports modeled warm-up visits without merging active phases.
* Private primitive recorders retain no workload objects. Identity observations carry worker-local positions; strong-result presence is recorded independently of its identity. Formats remain at version 1 without intermediate-layout compatibility branches. HistoryPlan reserves syntax-based upper bounds, while the exact oracle handles conflict-free strong programs.

## Verification

The full build, 233 Java tests, native wire/loading/capture checks, strict Javadoc, and JDK 17/21 integration pass. Final focused regressions cover shared work/state bounds and independent result presence. Independent permutation models check CAS, exchange, volatile store buffering, and publication. Tests reject impossible returns, ordinary races, stale published data, duplicate/damaged evidence, unsupported shapes, invalid paths, and unproved searches. Reference tests keep caller and worker acquisitions strong across peer GC and barriers. Offline verification and exact-class replay pass.

The selected HotSpot fastdebug target passes a contended CAS program across modeled warm-up visits with joint overlap, compiled-frame, and GC evidence. Its interpreted counterpart preserves the semantic verdict and leaves compiled execution unmet. Selected-target protected-reference checking and contended replay pass. Artifacts and machine paths stay in ignored local configuration. These checks establish implementation behavior, not effectiveness at finding real GC bugs.

## Boundaries and sources

History execution supports ordinary-object shapes; exact strong execution retains array support. Live-binding checkpoints, conditional checkpoints, acquire/release-only modes, arbitrary data races, and reclamation/completion assertions remain unsupported. Generation diversity follows stage 11. Search limits produce inconclusive/checking-limit results and may exclude large contended programs.

[JLS 17.4.5](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5) supplies the race-freedom/SC basis; [VarHandle](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/invoke/VarHandle.html) defines access-mode and atomic-operation effects.
