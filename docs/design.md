# Object Graph Verification

Object Graph Verification checks objects, references and data against an independent model of program behavior. Gorrect uses it to validate garbage collectors by generating and running Java programs.

## Approach

1. Describe an initial object graph and a workload in GIR (Gorrect Intermediate Representation).
2. Translate the case into Java and run it on a selected JVM and collector.
3. Record graph observations and the execution choices needed to interpret them.
4. Independently interpret GIR and check whether the observations describe a legal execution.
5. Retain the case, settings and evidence for investigation and replay.

A deterministic case can have one expected graph. Reference processing and concurrent execution can permit several outcomes; the oracle checks their constraints rather than predicting one collector policy or schedule.

## Object graph

Objects have stable logical identities, shapes and data. Shapes describe fields or array elements. Reference slots hold a target or null, with strong, soft or weak strength. Named roots and live handles in every thread/frame keep objects reachable.

References can share targets, form cycles or point to their owner:

```text
root -> a
        a.left  --strong--> b
        a.right --weak----> b
```

Changing a slot's target preserves the slot and object identities. Releasing roots makes objects eligible for collection; no operation directly frees a Java object. Headers, addresses and collector bookkeeping are outside the portable graph model.

## Graph program language (GIR)

[GIR](./gir.md) expresses allocation, graph/data access, handle lifetimes, calls, conditionals, bounded loops, worker threads, synchronization and checkpoints. The oracle interprets these operations; the Java emitter implements them independently through real Java allocations and accesses.

Checkpoints observe the graph under a declared [coverage contract](./observation.md). A collection request is a separate operation and does not determine which references clear or which objects are reclaimed.

## Soft and weak references

An acquisition reads a referent once. A successful result is held strongly while its scope executes; a null result skips that body. For example, a first acquisition may create a child and a later acquisition may publish it through a strong root. If both succeed, the published identity and contents must agree. Once strongly published, the child must survive even if the original soft/weak reference clears.

The oracle validates recorded choices, including related-reference clearing rules. Clearing is permanent for one wrapper; replacement creates a new wrapper. Clearing, queue notification and reclamation are distinct, and retained garbage alone is not a failure.

## Oracle

At each observation, required roots and accessible objects must retain their expected identities, shapes, references and data, allowing only program effects and legal reference processing. Choices used to derive later expectations must first satisfy the model.

A result is satisfied, violated or inconclusive. Missing evidence and exhausted checking budgets cannot establish satisfaction. A mismatch is evidence to investigate in the JVM, generated code, observer and oracle; execution failures remain separate.

## Concurrency and JIT compilation

Workers mutate concurrently under explicit synchronization. Conflict-free phases can have exact results; competing atomic operations admit several legal schedules. Arbitrary data races need a separate memory-model contract.

Generated methods and loops exercise ordinary Java accesses and JIT compilation. Warm-up belongs to the modeled workload. Compilation, overlap and collection requirements need their own execution evidence, independently of the graph verdict. Recorders retain no workload objects or global mutation lock; observation can still affect timing and optimization.

## JVM integration and stress generation

A [JVM integration](./implementation.md#jvm-integration) supplies launch settings, capability checks, controls and evidence interpretation while preserving graph semantics. Unsupported capabilities remain explicit.

Generation combines graph structures and lifetime patterns. Measurements and collector heuristics can guide stress, but do not determine correctness.

## Reproduction

A saved run contains GIR, executable classes, settings, observations and diagnostics. Replay obtains fresh execution evidence. Reduction seeks a smaller case while preserving the failure and any required concurrency or compilation conditions.
