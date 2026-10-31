# Explicit reference queues proposal

Status: unaccepted stage 15b proposal. Start with initially-null registered soft/weak wrappers, explicit enqueue and nonblocking poll. This tests queue ownership and wrapper aliases without assuming a portable GC-pending root policy. The broader [reference design](2026-10-30-stage15-reference-semantics.md#queues-and-explicit-enqueue) retains the later automatic-notification scope; supported behavior remains in [GIR](../../../docs/gir.md#reference-histories).

## Small surface

| Proposed form | Meaning |
| --- | --- |
| `queue ready W;` | Declare a case-lifetime queue accepting only nominal wrapper type W. Queues have their own names and stable per-run primitive IDs; they are retained support resources, not general graph operands. |
| `allocate-ref r W null ready;` | Reuse reference construction with an optional final queue name. Registration is immutable and occurs in the real constructor. Omission retains no-queue behavior and its supported referents. Registered construction requires the literal `null` in this slice. |
| `enqueue-ref inserted r;` | Invoke the live non-null wrapper's real `enqueue()` once and bind its boolean result as the existing long 0/1 value. |
| `poll-queue delivered ready;` | Poll once and bind the actual returned wrapper, or null. The queue declaration supplies the binding's nominal type. Bare `poll-queue;` retains legacy discarding behavior on its separate implicit queue. |

One nominal type per queue prevents a poll from consuming a wrong-typed entry before discovering invalid GIR. Different queues may accept the same type. No separate registration instruction is appropriate: the Java API fixes registration at construction. Existing receiver operations, roots, strong fields, identity comparisons and child scopes retain their contracts. Static checks reject unknown queues, type mismatches and non-null registered construction before execution, including in skipped blocks.

This proposed program keeps `wrapped` alive through queue ownership after its handle ends. The weak reference from `witness` to `wrapped` cannot be GC-cleared before the poll, whose result becomes a strong handle:

```text
gir 1;
shape W weak;
queue ready W;
root saved = null;
program {
  allocate-ref wrapped W null ready;
  allocate-ref witness W wrapped;
  enqueue-ref added wrapped;
  release wrapped;
  checkpoint;
  poll-queue delivered ready;
  write-root saved delivered;
}
```

## State and ownership

Keep immutable registration, referent clearing and queue disposition separate. Each named registered wrapper starts cleared and registered, with no GC-notification debt. It transitions from registered to enqueued on successful explicit insertion, then to consumed on poll. A queue retains its enqueued wrappers strongly; registration and primitive identity records retain none. After removal, the returned handle protects the wrapper until release/scope exit. Ending either protection permits retention or reclamation, rather than requiring disappearance. The one-sided registration contract is defined by the [reference package](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/package-summary.html).

Explicit enqueue clears the receiver even when it returns false. A fresh registered wrapper may be enqueued despite starting null or being explicitly cleared; a no-queue receiver returns false. Repeated insertion and insertion after consumption return false under the proposed qualified-target contract. Explicit clear changes neither membership nor prior notification debt. These effects use the real [Reference API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Reference.html#enqueue()); no replacement wrapper or synthetic successful result is permitted.

Track actual membership as an unordered set, not just an eligibility bit. Poll removes exactly one member and cannot return null while this queue is known nonempty and has no other consumer. Polling another queue changes nothing here. Do not infer FIFO/LIFO order from selected implementations. The [poll contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/ReferenceQueue.html#poll()) supplies the availability rule.

Include mandatory queued-wrapper roots before computing either strength-specific reachability/cohort closure; preserve the existing different soft and weak predecessor rules. Aliases share one wrapper state. A queued wrapper may itself be another reference's referent, as in the example; that reference cannot be GC-cleared while queue ownership protects its target. Explicit clear/enqueue may still clear it.

Registered non-null construction remains unsupported. It introduces delayed GC notification and pending ownership that may survive explicit enqueue, poll or clear. Initial-null registration and explicit clear do not create that debt; clearing a wrapper after prior GC processing cannot erase it. In the excluded counterexample, weak A targets registered B, whose ordinary referent is processed while B is held; releasing B leaves A's clearing dependent on actual pending/queue ownership. Neither a primitive pending bit nor registration proves a portable strong root. Keep all legacy exposure guards until an independent retention model handles that case. Legacy exposure can be a separate compatibility unit without named queues, but it also needs a genuine observed nominal type for the otherwise opaque legacy wrapper; matching strength alone cannot invent one.

## Evidence and capture gate

Retain current legacy and tagged reference-history bytes. Select a separate bounded version 1 queue sidecar from validated source features, with run identity, completeness and worker/scalar-position linkage. Record actual constructor queue arguments, complete wrapper identities, enqueue results, polled queue identity and returned wrapper identity/null. Primitive recording arrays retain neither wrappers nor referents. Join these records with the existing constructor, state and identity evidence; unknown identities, wrong-queue delivery, duplicates and incompatible scalar results cannot pass.

Give generated queue objects primitive IDs and a dedicated observation role. Capture each resource's actual static root and physical queue identity. Registration evidence identifies the actual constructor argument; it does not claim to read a VM's private registration field. Successful enqueue and poll provide API membership evidence. A qualified observer provider must decode the actual entry set and its strong ownership paths, distinguishing entry links from unrelated helper objects and excluding special referent edges. Compare that set with API history; mere reachability from a queue is not membership evidence. Do not manufacture graph roots by looking up expected members.

Ownership capture is an unresolved enabling gate. The current native walk skips opaque-to-opaque records; inspected HotSpot uses linked wrappers, while OpenJ9 uses an intermediate array. Extend bounded capture to retain and interpret actual entry paths and certify their complete coverage, or report inconclusive ownership. Main-only execution and initially-null registration keep these queues stable during capture; no GC handler or external consumer can add/remove their entries. Missing paths, missing queue roots, sidecar truncation or exhausted budgets cannot become satisfied graphs. No portable contract depends on private field names or assumes current raw records contain complete queue paths.

## Evidence and implementation order

An ignored independent finite model covers nine queue states and 7,546,580 histories of lengths one through eight by state/path-count enumeration. A separate Floyd-Warshall check covers 1,536 small ownership graphs, including 560 special edges whose targets gain protection from queue ownership, and reproduces the excluded pending-root counterexample. This checks queue transitions and root effects; it is not a full oracle/capture proof.

A deterministic 13-check Java 17-compatible API probe passes on JDK 17, engine JDK 21, selected HotSpot and selected OpenJ9. It checks initially-null registration, clear without insertion, success/duplicate/consumed insertion, wrong queue, unordered identity delivery and false no-queue enqueue clearing a live referent. No GC is requested. Inspected [HotSpot queue source](https://github.com/openjdk/jdk21u-dev/blob/db16554afc2bf284697f4f9f10672b37f0e464a6/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java) uses linked wrappers; actual [OpenJ9 queue source](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/jcl/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java) uses an array. Their inspected GC paths skip null referents. An OpenJ9 negative control with `jdk.lang.ref.disableClearBeforeEnqueue=true` prevents clearing on false enqueue. Feature qualification must exercise effective launch settings and reject incompatible behavior; these probes establish no automatic-processing or observer coverage claim.

First agree this narrow contract and its value against the added syntax. Then implement model/frontend/evidence together with independent candidate-set checks, followed by actual queue capture certification and target integration. Test queue-root loss, omitted intermediate paths, forged membership, duplicate delivery, alias clearing, wrapper-as-referent cohorts, resource limits and unchanged legacy/no-queue artifacts. Keep rejected model transitions transactional; recorder failure after a real API effect instead makes execution evidence incomplete. Require complete run/verify/replay evidence before enabling the feature. Non-null registration/pending retention, heterogeneous queues, blocking removal, concurrency, natural capture, frames and callbacks remain separate work.
