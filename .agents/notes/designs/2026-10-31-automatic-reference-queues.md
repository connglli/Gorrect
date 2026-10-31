# Automatic reference queue proposal

Status: unaccepted stage 15 continuation, conditional on the [named-queue slice](../../../docs/gir.md#reference-histories). This proposes controlled sequential soft/weak notification using existing GIR and version 1 formats, without enabling support. The [reference design](2026-10-30-stage15-reference-semantics.md) retains broader lifetime/concurrency gates.

## First admission rule

Allow `allocate-ref r W value ready;` with an operand other than literal `null` only when construction and sole publication of its binding into a dedicated, initially-null GIR root both occur once at program top level. Publication may follow intervening instructions, including collection, but must precede release of that binding. Check top-level ordering and all root writes, including skipped blocks; existing binding rules exclude shadowing and child-scope release. The live binding protects the wrapper until publication; the unchanged root protects it through terminal capture. Both remain graph/capture obligations. Reject unsupported publication before execution or offline checking.

This source proof needs no whole-history analysis or new syntax/registry. Aliases, receiver operations and wrapper referents retain their contracts. Literal-null construction keeps detached support. Detached/weak-only non-null registered wrappers, legacy exposure, natural capture, shared workers, phantom references, Cleaner and finalization remain gated. Broader admission requires independent lifetime reasoning, without changing reference semantics.

## Joint reference and queue histories

Distinguish immutable constructor target/type/queue, clearing, GC-notification debt, permanent insertion and consumption per wrapper. Each frontier candidate owns its complete unordered queue membership. Debt records admissible processing, not a portable strong root; the admission rule makes pending ownership irrelevant to required wrapper lifetime. Derive consumption from permanent insertion and absent membership; canonicalize debt after insertion because later handler attempts have no admitted semantic effect. Notification proves neither reclamation nor a collection deadline.

| Action | Candidate transition |
| --- | --- |
| Construct | Fix actual registration and target. Null starts cleared without debt; non-null starts uncleared. Never inserted or consumed. |
| GC processing | Optionally apply the existing strength-specific atomic clearing cohort; newly cleared registered wrappers gain debt. Preserve soft/weak predecessor differences. |
| `clear-ref` | Clear this wrapper only. Preserve debt, insertion, membership and consumption. Initial null and program clearing create no debt. |
| `enqueue-ref` | Clear even on false. Insert into its registered queue only if never inserted; return true exactly for this insertion. Preserve existing debt. No-queue and already inserted/consumed attempts return false under qualified target behavior. |
| Handler | Optionally settle existing debt by inserting if never inserted, or settling without duplication after insertion/consumption. |
| Named poll | Remove one actual member and bind that wrapper strongly, recording consumption. Null requires that candidate's queue to be empty. Other queues and debt alone supply no members. |

Allow arbitrary processing/delivery delay, including no delivery after `collect`. Consider handler insertion around API linearization when validating actual results. API order supplies no FIFO/LIFO rule. [WeakReference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/WeakReference.html), [SoftReference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/SoftReference.html) and [Reference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Reference.html) own the Java clearing/notification contract.

Move automatic membership out of deterministic machine bookkeeping into the reference frontier. Key and budget all remaining joint state. Before each candidate's strong/soft closure, add its full semantic member roots; partial physical components never become semantic roots. Match physical ownership against that candidate, rather than repairing membership from expected identities or successful API calls. Preserve existing first-class atomic/concurrent guards.

## Consistent physical capture

A safepoint can stop the ReferenceHandler between queue-link/count/sentinel writes. Same-walk capture and stable heap bytes alone cannot certify a completed queue state. Keep automatic support disabled until a selected-target adapter excludes every mutation of every declared queue throughout the graph/resource walk.

The proposed adapter acquires qualified mutation locks before entering the observer's native state lock, in resource-ID order, and releases in reverse order after capture using guaranteed cleanup. It performs no JNI, allocation or locking inside heap callbacks. HotSpot 17's private monitor and selected [HotSpot 21's `ReentrantLock`](https://github.com/openjdk/jdk21u-dev/blob/ab5cf6f46553c67020133fcd6c5307a8b7841436/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java) need different operations; monitor-entering a `ReentrantLock` is insufficient. OpenJ9 needs its own queue/reference-lock ordering proof before admission. Existing target/library admission proves none of these conditions.

Require actual queue-to-lock identity, lock kind and ownership evidence bound to run/capture and the same physical tags, plus successful acquisition of all locks across the walk. Qualify loaded library/helper behavior, acquisition ordering, absence of handler deadlock and wrapper-free helper roots independently. Lock acquisition may allocate, permit GC or advance handlers; the observation point is the locked walk, never the pre-lock request. Holding locks delays delivery; retain these observer effects. Never drain queues, invoke `get()`, or retain wrapper/referent lookup tables. A blocked acquisition uses the existing process deadline; any failed acquisition, cleanup, witness or capture prevents satisfaction. Without consistency coverage, a legal transient is an extraction gap, never a graph refutation; preserve independent covered contradictions.

## Enabling evidence

Join existing actual constructor-target and constructor-queue records, scalar-positioned API results and physical same-walk membership. A mutable private registration field cannot establish original registration, debt or consumption: OpenJ9 may clear it before acquiring the queue lock. Add bounded version 1 consistency evidence selected from source features; missing evidence has no fallback.

Require independent candidate-set tests for aliases/chains, roots and both cohorts; null/program-clear without debt; GC-clear then clear; pending then explicit insertion/poll/late handler; wrong queues, duplicate insertion, delayed/empty polls and post-consumption retention. Test delayed publication across `collect`, release-before-publication rejection and transactional/budget failures. Force handler cuts within updates, wrong locks, omitted/truncated witnesses and resource exhaustion; require inconclusive coverage and preserved earlier refutations. Enable each target only after actual GC-handler activity, physical-cut qualification with both metadata providers and complete run/verify/replay agree. Promote accepted semantics to GIR/source, capture guarantees to observation documentation and adapter details to implementation documentation.
