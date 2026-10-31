# First-class reference semantics proposal

Status: unaccepted design proposal for [stage 15](../implementations/2026-10-29-implementation-plan.md#15-make-references-and-queues-first-class). No GIR, runtime, observer, oracle, capability, or artifact behavior is enabled by this note. Keep formats at version 1. The [observation contract](../../../docs/observation.md) and [instruction contracts](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) remain authoritative for supported behavior.

## Small first slice

Start with sequential controlled observation of first-class soft/weak wrappers without queues. Propose the following additions; these spellings are not accepted by the current parser. Wrapper type names share the existing shape namespace.

| Proposed form | Contract |
| --- | --- |
| `shape W weak;` / `shape S soft;` | Declare a wrapper type with that strength and no ordinary reference/data slots. Its referent may have any supported type or be null. |
| `allocate-ref r W value;` | Construct a fresh no-queue wrapper of type W around the current operand, binding strong handle r. The operand is an ordinary object/array, an older no-queue wrapper, or null. Ordinary `allocate` does not construct wrapper types. |
| `clear-ref r;` | Clear the identified wrapper; all its aliases observe the same state. |
| `is-cleared flag r;` | Bind the existing long-valued 0/1 predicate using refersTo(null), without acquiring the referent. |
| `acquire held N r { ... }` | Call get() once; for a non-null result of declared type N, bind a strong handle in the child scope. N may itself be a wrapper type. |

`allocate-ref` is the only new instruction keyword. `clear-ref`, `is-cleared`, and `acquire` gain unambiguous receiver arities; their owner/index forms retain slot semantics. Existing `read-root`/`read-ref` accept declared wrapper destinations, `write-root`/`write-ref` store the same wrapper pointer, and `same-object` compares aliases. No new scalar type or expression syntax is needed.

Receiver operations require a live non-null wrapper, and a non-null load/acquisition result must match its declared type. Invalid preconditions retain transactional invalid-GIR behavior. Release/scope rules are unchanged; `if-present` tests wrapper nullness, not referent clearing.

This complete example uses proposed GIR. The box stores and loads one wrapper identity; clearing alias is observed through wrapper:

```text
gir 1;
shape Node object 0 1;
shape Box object 1 0;
shape Weak weak;
root saved = null;
program {
  allocate value Node;
  allocate box Box;
  allocate-ref wrapper Weak value;
  write-ref box 0 wrapper;
  read-ref alias Weak box 0;
  write-ref box 0 null;
  acquire held Node alias { write-data held 0 7; }
  clear-ref alias;
  is-cleared cleared wrapper;
  write-root saved wrapper;
  checkpoint;
}
```

`allocate-ref` is the only construction form for declared wrapper types in 15a; `object` declarations keep ordinary shapes and legacy inline soft/weak slots. Referents have no ordinary setter, so arbitrary cyclic initial constructor dependencies are unsupported; mutable holders can form cycles after construction. Do not use Unsafe initialization or reconstruct wrappers when moving them. The [reference package contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/package-summary.html) describes their otherwise immutable referent, apart from clearing.

## Canonical identity and graph

[Graph](../../../engine/src/main/java/io/connglli/gorrect/engine/model/Graph.java) contains ordinary objects, while [Value.Reference](../../../engine/src/main/java/io/connglli/gorrect/engine/model/Value.java) folds a slot's wrapper identity and referent into one value. [ReferenceFrontier](../../../engine/src/main/java/io/connglli/gorrect/engine/oracle/ReferenceFrontier.java) retains detached incarnation state. [SnapshotProjector](../../../engine/src/main/java/io/connglli/gorrect/engine/observe/SnapshotProjector.java) resolves wrapper referents only to ordinary identities and collapses incoming wrapper edges. Those representations need a common wrapper vertex before aliases can share state or target another wrapper.

Recommend a small tagged heap identity containing the existing ObjectId or WrapperId, preserving their separate namespaces and all legacy IDs. A strong reference can target either. Ordinary nodes keep their shapes and payloads; reference nodes keep immutable wrapper type, construction target, and optional queue registration. The declared type supplies strength. Clearing and notification state belongs to the history candidate, keyed by WrapperId. Its current special referent edge is derived from construction target and clear state; owner slots do not store a second mutable copy. Copying or moving a wrapper pointer preserves its ID, referent, registration, and notification state. Fresh construction alone creates a new incarnation.

Holding a reference handle protects the wrapper, not its special referent. A non-null acquired result is protected through its child scope independently of that wrapper. A null wrapper handle is distinct from a non-null wrapper whose referent is null. Ordinary owner/index checks remain in force for container access; wrappers have no indexed ordinary slots.

Keep existing slot-operation preconditions. Legacy read-ref with an ordinary destination must not acquire a referent implicitly; legacy CAS/exchange must not become legal on a soft/weak slot merely because its physical storage is a strong pointer to a wrapper. A read-ref with a declared wrapper destination reads the wrapper pointer directly and remains subject to the no-queue exposure guard. Wrapper-valued atomics remain unsupported. Legacy soft/weak stores still create fresh registered wrappers, including for null, and normalize to the same canonical state. Their referent operands remain ordinary objects or null in 15a.

In 15a, only no-queue wrappers can become first-class GIR values. Registered legacy incarnations remain usable through unchanged slot APIs and the existing discarding poll; new wrapper loads, wrapper-valued referents, acquisitions, roots, and stores cannot expose them. Prove that boundary over every legal modeled history before launch and offline checking; possible registered exposure is unsupported, regardless of the recorded path. Proof-budget exhaustion retains its checking-limit/inconclusive outcome. Legacy-only programs and guarded mixtures with no-queue wrappers remain supported. Lifting this guard requires the legacy implicit-queue/pending-retention model and independent mixed-graph checks; it can be a focused compatibility slice before named queues or explicit enqueue.

## Clearing and reachability

Compute reachability over explicit ordinary and wrapper vertices. Ordinary fields, roots, live handles, and actual queue ownership lead strongly to wrappers; the wrapper-to-referent edge has its declared strength. A rooted inner wrapper can have its own referent cleared while an outer reference to that wrapper remains non-cleared. A live acquired referent protects that referent independently of the wrapper's current slot or container.

GC transitions remain optional atomic cohort transitions between modeled operations. For soft clearing, preserve the strongly reachable set and clear the selected softly reachable referent together with soft references to softly reachable predecessors connected to it through strong paths. For weak clearing, preserve strongly/softly reachable objects and include weakly reachable predecessors connected through strong and soft paths. Apply each transition to all affected wrapper IDs, including aliases and wrappers whose referents are wrappers. These rules come from the [SoftReference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/SoftReference.html) and [WeakReference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/WeakReference.html) contracts; do not replace their different predecessor closures with one strength comparison.

Retaining a primitive model record creates no runtime root. Registration alone does not keep a wrapper alive. Queued ownership, prior admissible processing, and permitted lingering physical references must be distinguished from declaration scope. Release ends a minimum lifetime obligation and proves neither collection nor absence of later notification. A detached wrapper's delivery must have an admissible processing history; knowing its ID is insufficient. Preserve the current controlled profile's allowance for retained garbage, and do not infer stronger natural-liveness guarantees.

## Queues and explicit enqueue

The next slice adds named queues with stable per-run identities and an optional no-queue registration. Named queue objects are deliberate retained support roots; they do not root every wrapper registered with them. Start with generated nonblocking poll operations and no external consumers. Queue objects and Cleanable values need no general-purpose user fields or loader behavior in this slice.

Keep independent state for whether the referent is cleared, whether GC notification is pending, and queue disposition: unregistered, registered, enqueued, or consumed. Pending GC notification can coexist with explicit enqueue or consumption; a later handler attempt cannot duplicate delivery. Initial null and explicit clear create no new GC notification. A clear after prior GC processing does not cancel its already pending notification. These distinctions fit the [OpenJDK reference state description](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/ref/Reference.java); they describe semantic possibilities rather than requiring its private field layout on every JVM.

| Action | Required transition or observation |
| --- | --- |
| Construct | Fix strength, target, and registration. Null starts cleared without pending GC delivery. |
| Explicit clear | Clear only this incarnation, even with a strongly held referent; preserve existing queue/pending state. |
| Explicit enqueue | Clear this incarnation, then attempt insertion. Return true only for a successful insertion; false is a successful Java operation and can still change referent state. |
| GC processing | Apply the strength-specific atomic clearing cohort; registered affected wrappers become eligible for delayed notification. |
| Queue delivery | Insert an eligible registered incarnation at most once. Registration and pending status alone do not imply it is already available to poll. |
| Poll | Remove one available wrapper and bind that wrapper, never its referent. Preserve identity and mark consumption. |

[Reference.clear/enqueue](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Reference.html#enqueue()) specifies the different effects of clear and enqueue. A registered cleared or initially null wrapper can still be explicitly enqueued. Repeated enqueue after insertion or consumption is false in the inspected OpenJDK implementation and selected-target checks; retain that terminal disposition in the proposed supported contract. Queue operations must identify the actual queue and returned wrapper, including no-queue, wrong-queue, duplicate, and unknown-identity failures.

[ReferenceQueue.poll](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/ReferenceQueue.html#poll()) removes an immediately available entry. Allow arbitrary delivery delay and no portable FIFO/LIFO assumption. A known successful enqueue followed by poll with no intervening consumer cannot yield null. The current frontier's unconditional empty-poll allowance must not be carried into that case. Blocking remove and its interruption/deadline contract can follow separately. Notification, removal, and object reclamation remain distinct observations.

## Concurrency, phantom references, and Cleaner

First-class shared-wrapper and shared-queue operations need separate access footprints before being enabled. A slot load touches its owner location; clear/acquire/state operations touch the identified wrapper, so aliases through different containers cannot evade the conflict audit. Queue operations touch the identified queue and wrapper disposition. Preserve the existing all-schedules validity/race proof and per-worker trace order. Do not infer publication or ordinary-data happens-before edges from reference operations without a documented memory contract. Reuse the coordinated capture and scheduler rather than add another general scheduler.

Phantom support is a separate slice. Its get() always returns null even while refersTo(nonNullTarget) is true, so neither get()==null nor the soft/weak acquire operation can establish clearing. Use non-acquiring state tests and an independently checked phantom-reachability/cohort rule from [PhantomReference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/PhantomReference.html). Restrict initial support to generated classes without user finalizers; finalization/resurrection remains stage 24. Explicit clear/enqueue tests are distinct from automatic phantom processing, and queue notification does not certify physical reclamation.

Cleaner adds actual asynchronous graph-mutating actors, breaking the current frontier invariant that GC changes only wrapper state. Generate a static action class with declared modeled captures and a bounded body. Captured state is deliberately retained by the Cleaner and must appear in the graph/root obligations. If it strongly reaches the monitored object, automatic cleaning cannot be required. Invoke the real Cleaner API; a simulated worker callback does not exercise that mechanism. Keep Cleanable opaque rather than cast it to Reference or expose implementation-private queue controls. The [Cleaner contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Cleaner.html) requires at-most-once action execution and warns against referent-retaining action captures.

Model explicit clean and background dispatch separately, including the winning thread/activation, successful-prefix effects, and at-most-once completion. [Cleanable.clean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/Cleaner.Cleanable.html#clean()) can request cleanup while the referent remains strongly reachable. Inspected OpenJDK code dispatches direct clean without a catch, while the [background Cleaner loop](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/jdk/internal/ref/CleanerImpl.java) suppresses callback exceptions. Selected direct-call checks propagate the action exception; this does not establish a portable exception policy for every target. Initially use nonthrowing actions. Exceptional actions require stage 14's origin/completion contract and target conformance checks.

Every dispatched callback needs bounded primitive entry/completion/failure evidence, actor identity and ordinary access footprints in the existing scheduler. A swallowed harness or recorder failure must still make capture/completion incomplete, even when graph contents happen to match. Inject those failures before enabling callbacks. Coordinated captures must account for all active callback effects/roots; a user-worker barrier alone cannot establish their quiescence. Missing actor/frame correspondence remains inconclusive, and no automatic-cleaning deadline or shutdown progress is promised.

## Integration boundaries and checks

| Boundary | Proposed complete change |
| --- | --- |
| Frontend/model | Wrapper binding type, tagged targets, canonical wrapper nodes/state, explicit creation/load/handle operations; preserve legacy shape and transactional rejection rules. Reuse current long-valued predicates without requiring deferred numeric features. |
| Oracles | Generalize reachability/cohort enumeration and identity matching; include reference and queue state in fork keys and bounded storage. Preserve earlier definite violations beside later evidence gaps. |
| Emitter/runtime | Real constructors and Reference/ReferenceQueue/Cleaner calls; copied pointers remain aliases. Recording keeps primitive identities only; deliberate support roots and declared action captures remain explicit. |
| Observation | Index all ordinary/wrapper identities before resolving any referent edge. Project wrapper vertices and actual local/root targets without get(). Keep raw native records and require physical origin coverage for live bindings. |
| Evidence | Existing version 1 object-only referent records cannot identify a wrapper target with its full WrapperId. Define a version 1 envelope with tagged targets, selected by validated retained-program features, and retain decoding of legacy envelopes. Missing required evidence cannot fall back to an empty history. |

The first slice reuses direct SoftReference/WeakReference subclass emission and inherited-referent metadata. Each declared wrapper type gets a distinct registered generated class, allowing actual class metadata to establish its type; no new class-loader mechanism is needed. Queue identity/registration evidence and phantom/native class roles need their own layout/capability checks before their slices. Retain private queue links as raw diagnostics without imposing one VM's layout as portable semantics. Same-signature classes from multiple loaders remain unsupported until stage 22, and wrapper APIs remain outside natural-history observation until its independent liveness policy is complete. Use stage 14's agreed activation keys when calls/recursion are composed, without inventing another frame identity.

| Slice | Completion gate |
| --- | --- |
| 15a: first-class soft/weak wrappers | Sequential controlled semantics, lowering, projection and typed evidence agree with independent models for no-queue aliases, detached wrappers and no-queue wrapper referents. The all-history guard keeps registered legacy incarnations outside first-class operands. |
| 15b: named queues and enqueue | Settle implicit-queue/pending retention before lifting the legacy exposure guard; agree queue identity/registration and membership evidence. Check initial null, no queue, clear then enqueue, duplicate/consumed enqueue, wrong queue, delayed notification and nonempty polling. |
| 15c: concurrency and phantom processing | Alias-aware footprints and all-schedules checks compose with worker cuts; independent phantom closure and actual state/queue observations pass without get-based clearing claims. |
| 15d: Cleaner actors | Establish stage 14 completion/origin requirements where used, active-actor capture coverage and failure evidence, then verify explicit/background dispatch, captured-state retention and at-most-once effects independently. |

Extend the existing exhaustive explicit-wrapper test model rather than invoke production cohort code as its oracle. Enumerate small graphs with ordinary and wrapper vertices, mixed strengths, roots to wrappers, wrapper-as-referent chains, holder cycles, aliases, clear/replace/acquire sequences, and pending/queued/consumed combinations. Check guard rejection through every wrapper-valued path and preserve legacy-only verdicts. Before lifting it, model a no-queue weak A targeting registered B: process B's ordinary referent while B is held, release B, and distinguish registration, pending retention and actual queued ownership when deciding whether A can clear. Cross-check candidate sets, not one successful VM run. Check rejected operations and resource exhaustion before any heap, identity, trace, notification, or budget mutation. Bound wrapper nodes, queue entries, actor registrations/events, candidate state and transitions; exhausting those bounds cannot become a graph pass or expected Java exception.

A deterministic ignored API probe compiles with javac --release 17 -g -Xlint:all -Werror and passes on local JDK 17, engine JDK 21, and the selected HotSpot/OpenJ9 JDK 21 installations. It checks no-queue enqueue clearing with false, successful/duplicate/consumed enqueue, initial-null manual enqueue, explicit clear without delivery, detached aliases and wrapper referents, phantom get versus refersTo, and direct Cleaner at-most-once dispatch with a propagated action exception. It requests no GC and proves no asynchronous progress, compiled capture, production projection, or portable callback-exception guarantee. Probe sources, commands/results and a hash manifest remain ignored in the assigned worktree.

Before implementing 15a, agree the proposed GIR forms, tagged identity representation, evidence selection and the all-history legacy exposure guard. Settle detached/pending-wrapper retention before lifting that guard, even without new queue syntax. Named queues additionally require actual queue identity/registration evidence. Later concurrency, phantom, actor and exception gates remain separate from the guarded no-queue basis.
