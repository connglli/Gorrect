# History checking scale proposal

Status: unaccepted stage 17 proposal, based on bounded host-audit measurements of the reviewed worker-capture candidate. No production algorithm, budget, artifact or GIR change is implemented. The [stage 17 plan](../implementations/2026-10-29-implementation-plan.md#17-scale-allocation-and-checking) owns the wider allocation/checking work.

## Measured problem

[ConcurrentHistory](../../../engine/src/main/java/io/connglli/gorrect/engine/oracle/ConcurrentHistory.java) proves dynamic validity and ordinary-access race freedom over every bounded sequentially consistent schedule. It reuses equal states only after a whole worker group joins. Matched barriers and worker captures reset phase race clocks, but their completed states have no reuse entry. All these checks remain necessary; equivalent prefixes can still repeat the same future proof.

The measured programs have two workers, two captures per group and either one or two group visits. They contain only strong references: the baseline allocates worker locals, publication additionally stores/clears separate named roots, and the preexisting-root variant reads its locals from separate roots. An independent host harness invokes public `audit` directly. It launches no target JVM and uses a 512 MiB host heap bound.

| Case | Work bound | Audit result | Charged work | Completed forks | Peak counted states |
| --- | --- | --- | --- | --- | --- |
| Local allocation, two visits | 1,000,000 | Accepted | 977,589 | 22,070 | 19 |
| Preexisting roots, two visits | 10,000,000 | Accepted | 1,044,247 | 22,070 | 19 |
| Publication, one visit | 10,000,000 | Accepted | 8,411,817 | 135,361 | 12 |
| Publication, two visits | 10,000,000 | Checking limit | 9,999,997 | 154,205 | 23 |

The preexisting-root case exceeds the default 1,000,000 work bound. The publication case has exactly the same default-bound outcome and counters with state capacity 32 as with 4,096. Its observed peak is 23, so additional state capacity does not address this case. The supported larger work bound permits the preexisting-root host audit; target execution and compiled coverage remain separate untested facts.

Nine case/bound combinations have identical outcomes and limit diagnostics under the unchanged approved engine jar and separately compiled observational instrumentation. Instrumentation preserves search order, all charges and limits. It counts fork work, phase boundaries and memo hits without merging or pruning anything. Complete commands, source/jar hashes, outputs and scripts are retained in ignored `.gradle/verification/history-scaling/` in the assigned worktree.

## Cause and finite model

The profile field `historyTransitions` limits cumulative work, including model/context copies, vector-clock copies, reference closure, keys and race checks. In the accepted one-visit publication audit, machine/context copies cost 4,614,255 units, race copies 2,017,092, and ordinary race comparisons 1,730. Repeated exploration causes most of this copying; ordinary conflict comparisons are a small component of this example.

A separate Python model enumerates worker action order and resulting strong graphs for each phase. Each worker has 3, 3, 2 tasks in the baseline/root cases, and 4, 4, 2 in publication. Every phase has one terminal graph in this restricted model. The baseline completes 20 first-cut schedules, then 400 second-cut schedules and 2,400 end schedules. Publication completes 70, 4,900 and 29,400 respectively. Existing join reuse prevents multiplying those complete-group counts across group visits.

The model predicts all five completed measured runs' fork and boundary counts exactly: 11,039 forks for one baseline/root visit, 22,070 for two, and 135,361 for one publication visit. This establishes the repeated-prefix cause for these straight-line examples. It proves no general reference-processing, temporal-liveness or state-key theorem.

## Smallest proposed integration slice

Add exact state reuse at existing matched phase boundaries in the all-schedules audit, initially for strong-reference programs. Keep trace matching and reference-history exploration on their existing paths. Use the existing join mechanism and budget facilities; add no scheduler, user option, language construct or partial-order framework.

A phase state must have a pure immutable key covering the complete logical heap/roots; every worker and caller scope, binding and reserved name; all continuations and worker control context; allocation/site occurrences and semantic resource counters; and relevant cursor/checkpoint positions. Include actual phase, group/round and synchronization state. Normalize the model's incidental active-context selection through an explicit full-context representation. The existing caller-only join key cannot represent live worker continuations. Future frame/completion or reference extensions require their own key review before eligibility expands.

Reuse only after all participants have reached a matched boundary and the existing race checker has accepted every preceding access. Eligibility belongs to that completed transition and must not survive copying into later steps. The barrier's happens-before edge makes a fresh race history valid; the completed prefix has already checked its preconditions and snapshot budget. Equal full state then has the same possible future audit transitions. Work still charges key construction and exploration, while all retained memo/frontier entries count against the existing state bound. A limit still aborts the proof; a partially explored state cannot establish acceptance.

For the straight-line finite model, ideal boundary reuse would reduce one-visit fork counts to 171 for baseline/roots and 535 for publication. These are predictions for a restricted model, not measured production speedups or an implementation guarantee. Key construction, eligibility and all-path validation must be implemented and tested before claiming a supported case fits its bound.

## Enabling checks and limits

Use independent exhaustive small schedulers to compare completed outcomes, validity and race rejection with and without the optimization. Cover disjoint publication, shared reads, aliasing, conflicting equal writes, atomic ownership transfers, live scalar/reference bindings, scope exit, repeated groups and barriers/cuts. An invalid or racy alternate prefix must still fail before any boundary reuse. Heap equality alone is insufficient: different local values, pending tasks, protected handles or remaining semantic budgets can change the future.

Keep existing malformed/incomplete evidence, earlier-refutation and budget regressions. Matching is unchanged in the first slice, preserving definite graph violations and missing-evidence handling. Repeat relevant run/verify/replay checks after implementation; host-audit measurements supply no GC, target or compilation evidence.

The proposal addresses multiplication across phases. Distinct intra-phase states, future reference frontiers and long allocation churn can still exhaust limits. No garbage retirement, byte-bounded evidence, callback/resurrection policy or general partial-order reduction belongs to this slice; those retain separate stage 17 proof obligations.
