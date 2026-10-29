# Tool design proposal

Detailed reasoning and implementation proposals for the [conceptual design](../../../docs/design.md). These notes do not override that document.

## Main direction

Use a portable workload language, an independent semantic checker, generated Java, and JVM integrations that provide target configuration, collection control, and observation. Prefer Java for the engine and workload, with a small C or C++ JVMTI agent for heap observation. Select the target JDK separately from the engine JDK. Concurrent mutators and workloads that trigger JIT compilation are required capabilities.

The validation question is whether the recorded execution and observations admit a legal history under the workload's semantics and the checkpoint's guarantees. A single predetermined final graph is sufficient for deterministic cases; it is insufficient when mutations depend on reference processing.

## Portable model

Represent objects by stable numeric identities and explicit shapes. A slot is identified by its owner and position, independently of its target. Store the target and reference kind as slot state. Allow aliases, cycles, self-references, and multiple slots targeting the same object. Give each newly created weak or soft reference wrapper a distinct incarnation so clearing one wrapper cannot silently apply to a replacement.

Represent root slots and temporary strong handles separately from objects. Several roots can hold one object; cloning an object does not implicitly clone its roots. Handles are scoped, and reads through weak or soft references acquire a handle only on success.

Use a small operation set: allocate; load, store, and clear slots; bind and release roots or handles; read and write payload; conditional acquisition; bounded repetition; worker start and join; explicit synchronization; collection request; checkpoint. Lower clone, swap, fan-in, fan-out, and other graph patterns into these operations. Composite operations expose their intermediate states and temporary roots. Java object reclamation is not a mutator instruction.

Keep allocation shape and stress policy separate from graph semantics. A separate padding array is a separate physical allocation; it must not be mistaken for a large reference-bearing object. Generations, region boundaries, and large-object thresholds guide workload selection using target measurements.

## Conditional reference access

A reference read happens once, and its successful result remains strongly held for the declared scope. [Reference.get](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ref/Reference.html#get()) returns a strong reference; the runtime must preserve the intended lifetime and release its temporary roots before reclamation-sensitive checkpoints.

The following is pseudocode, with each scope releasing its local handles at its end:

```text
with acquire(a.soft_b) as b, if present:
    c = allocate(Node)
    store_strong(b.child, c)

with acquire(a.soft_b) as b, if present:
    c = load_strong(b.child)
    store_strong(a.saved, c)

checkpoint()
```

Record acquisition results, object identities, and the operations executed. If the first read returns null, the same wrapper cannot later return an object without a mutator replacement. If both reads succeed, the second block must obtain the same `b` and the `c` created in the first block. Once `a.saved` is written, `c` must remain strongly reachable. The collector may still clear the soft reference to `b` after its handle is released.

The checker must validate these choices before using their consequences to compute expectations. A null result is a violation candidate when the wrapper has not been explicitly cleared or replaced and its referent has remained protected by a required strong path throughout the relevant history. An arbitrary recorded branch is not automatically accepted.

## Reference semantics and checking

Replay the immutable workload with its validated control-flow observations. Check exact strong-slot contents, wrapper kinds, identities, root bindings, payloads, and the absence of invented reachable nodes or slots. A surviving softly reachable object still has mandatory strong fields. Preserve raw observations, including garbage and unexpected roots, instead of pruning them before analysis.

Reference processing is nondeterministic but constrained. [Soft references](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ref/SoftReference.html) have related clearing choices, and [weak references](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ref/WeakReference.html) have atomic clearing requirements. Clearing, enqueueing, and physical reclamation are distinct observations. A collector can retain garbage without violating strong-reference safety.

Use direct graph checks for deterministic obligations. For mixed-reference histories, specify an independent transition model and check whether some legal placement of clearing events explains the trace and snapshots. Reference incarnations clear at most once; this gives a finite event bound when finalization and resurrection are excluded. Represent choices symbolically rather than constructing a tree of every possible graph. Trace conditioning avoids enumerating untaken mutator branches, but does not eliminate difficult clearing constraints or solver cost.

Only report a semantic violation when the checker establishes inconsistency for a supported, adequately modeled case. Unsupported semantics, missing observation guarantees, and exhausted checking budgets produce an inconclusive result. The reference-history model and its soundness need specification and a small prototype before implementation choices are finalized.

## Concurrent workloads

A case contains shared graph state, per-worker programs, generated methods, and an execution plan. Workers have separate handle scopes and stable event identities. The language represents synchronization explicitly, including barriers, ownership transfer through safe publication, and supported volatile or atomic operations. Plain field and array accesses remain available inside correctly synchronized regions.

Generate concurrent programs with different oracle obligations:

* Disjoint or commuting mutations have an exact result after worker synchronization, even though their operations overlap. Workers can mutate distinct slots of the same object or references to shared objects without racing on those slots.
* Shared publication and atomic contention have multiple permitted outcomes. The checker validates reads, returned values, and publication against a consistent concurrent history and the operations' contracts.
* Deliberate data races require a stated Java memory-model contract or invariants valid across all allowed outcomes. Do not apply an exact sequential oracle to arbitrary racy code.

Correct synchronization still permits nondeterministic outcomes. [The Java memory model](https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html) supplies the relevant ordering rules; happens-before consistency alone is insufficient for checking arbitrary racy programs.

Use the union of all workers' live handles and shared roots for liveness obligations. A checkpoint distinguishes mutator quiescence, collection completion, and reference-processing completion. Observe selected states before requesting any additional full collection, since that request can change the behavior being examined.

Offer coarse schedule control to reproduce interactions and unconstrained stress intervals for timing diversity. Do not impose a global lock or sequence counter on every graph operation. Record per-worker scalar events and synchronization points, then check partial orders rather than treating logging timestamps as an execution order. Account for recorder side effects and root retention.

## JIT workloads and evidence

Generate reusable mutation methods and hot loops that directly execute field and array operations. Repeated dispatch through a generic harness interpreter does not provide equivalent coverage of generated mutator code. Vary method and loop shapes to exercise repeated invocation, loop compilation, inlining, receiver-type profiles, and allocation patterns while retaining the same semantic obligations.

Warm-up uses disposable graphs or an explicitly modeled prefix. It must not silently change the test graph. Stress plans can exercise steady compiled execution, compilation while workers are active, and supported transitions involving on-stack replacement, deoptimization, or recompilation.

Separate the semantic lifetime of a handle from its physical root placement. Generate cases with references held in Java locals across allocation points, calls, and supported compilation transitions. A global root table must not substitute for all stack-local tests. Preserve required lifetimes explicitly and inspect unexpected retention. Optimizer-eliminated or virtual objects need no physical heap presence until their use requires materialization; check program observations for those cases instead of declaring every absent logical allocation a GC error.

The JVM backend reports compiler capabilities and target-method compilation evidence. HotSpot's [WhiteBox interface](https://github.com/openjdk/jdk/blob/master/test/lib/jdk/test/whitebox/WhiteBox.java) provides method compilation requests, compilation-level queries, and deoptimization controls. OpenJ9 exposes different [JIT controls and logs](https://eclipse.dev/openj9/docs/xjit/). Keep compiler names, levels, and flags in their target integrations.

Warm-up counts and compiler flags are requests, not proof that the required target code compiled or ran in a specified mode. Save compilation, OSR, and deoptimization evidence where available, and distinguish code installation from evidence of compiled execution. A missing required stress condition is a coverage shortfall, separate from the semantic verdict.

Exercise plain and volatile field writes, reference arrays, bulk copies, cross-thread publication, and references between long-lived and newly allocated objects. Keep compiler optimizations enabled for ordinary stress runs. Use lightweight recording and checkpoint observations so logging, JNI calls, or forced materialization do not dominate every hot iteration or hide the intended optimization.

## Execution and observation

Generate a small family of Java classes by shape, using real fields and array elements. Emit mutation code and bounded loops directly so the target JIT and barriers execute the workload. The emitter does not maintain a second simulated heap. Log scalar identities and operation results without retaining test objects in a global strong registry.

Use JVMTI tags and heap-reference callbacks as the observation candidate. [JVMTI heap traversal](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#FollowReferences) offers a stable heap view and reports field and array references. The agent must avoid persistent JNI roots and decode only the declared workload layouts. Keep raw output and the logical projection separately. Check mutator reads as well as snapshots because snapshot traversal alone does not test all Java access paths.

Keep collection and observation as separate actions. A snapshot should report whether it requested a collection. [HotSpot heap dumping](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html) can itself request a full collection, so dumps are diagnostic artifacts rather than an implicit checkpoint definition.

Prototype root lifetimes and observer effects under interpreted and compiled execution. Unexpected stack or JNI retention must be diagnosed before interpreting retained objects as a collector defect.

## JVM integration

Keep the common Java semantics in the core. Each JVM integration provides target probing, launch configuration, collection and compilation controls, runtime measurements, and any necessary observation adaptation. Detect capabilities on the selected executable and collector build; unsupported requirements must not silently fall back to another collector or weaker check.

Use a shared JVMTI observer where supported. OpenJ9 documents its [JVMTI interface](https://eclipse.dev/openj9/docs/interface_jvmti/), but capability and behavior probes remain necessary. Put HotSpot-specific control behind a backend: [WhiteBox](https://github.com/openjdk/jdk/blob/master/test/lib/jdk/test/whitebox/WhiteBox.java) supplies collection and capability-dependent concurrent-phase controls. Do not make these controls prerequisites for portable graph semantics.

Collection requests express intent, such as a young or full collection or a concurrent-phase transition. Results report evidence and scope. Checkpoint assertions depend on that evidence; a request or a sentinel reference alone does not establish every desired reclamation property.

## Generation, reproduction, and testing

Save a versioned case containing the initial graph and program, a per-case seed, the exact target command and build identity, collection and compilation evidence, worker programs and schedule constraints, execution trace, snapshots, logs, and failure artifacts. Separate execution outcomes from semantic verdicts and preserve crashes, timeouts, resource failures, and extraction errors.

Generate operations from explicit preconditions. Compute candidate state changes before committing them; rejection leaves no partial semantic or budget changes. Use conservative allocation budgets and measured layouts without promising that a simulated byte counter can exclude every OOM.

Reduce failures by removing operations, loop iterations, workers, schedule constraints, nodes, roots, slots, payload, and optional VM settings while preserving the relevant outcome and case validity. Preserve the required concurrency or compilation condition during reduction; a reproducer that only fails in compiled concurrent execution must not be classified as equivalent after that condition disappears. Repeated runs are needed for timing-sensitive failures. Compare each collector against the semantic oracle; legal differences in reference policy do not establish a defect by themselves.

Test the checker with small reference graphs and deliberately invalid observations. Test generation by compiling and running its Java output. Test root discipline, observation, and control capabilities before expanding the random generator.

## Proposed order and unresolved choices

First specify slot identity, root and handle lifetimes, per-worker operations and synchronization, reusable methods and loops, and the conditional-access example. Prototype a concurrent workload with an exact post-synchronization result, real local roots, target-method compilation evidence, and observation before an additional full collection. Extend this foundation with conditional-reference checking, controlled contention, layout variation, and reduction.

Concurrent mutators and JIT-triggering workload generation are part of the first useful implementation. Deliberately racy programs require their own supported semantic profiles. Phantom processing, finalization, and resurrection also need their own semantics; phantom references are not another dereferenceable strength level.

The [implementation document](../../../docs/implementation.md) defines component boundaries and language choices. The [architecture note](./2026-10-29-implementation-architecture.md) retains prototype questions. Remaining semantic work includes the clearing-history model, supported concurrent-operation contracts, and the checkpoint and compilation guarantees each profile must establish.
