# Object Graph Verification

Object Graph Verification checks whether the objects, references, and data observed during a program's execution satisfy an independent model of its behavior. Gorrect applies this approach to garbage collector validation using generated Java programs that manipulate object graphs.

A test combines an initial object graph with a program written in GIR (Gorrect Intermediate Representation), the graph program language. The program can be sequential, use concurrent workers, or contain hot methods and loops that trigger JIT compilation.

## Approach

1. Build an initial object graph with explicit roots, references, and object contents.
2. Generate a GIR program that manipulates the graph, including loops, worker threads, synchronization, and observation points.
3. Translate the initial graph and GIR program into Java code.
4. Run the Java program on the selected JVM and garbage collector.
5. Record relevant execution choices, collection and compilation activity, and graph observations.
6. Interpret the GIR program with the oracle and check whether the observations describe a legal execution.
7. Save failing cases and simplify them into smaller reproducers.

A deterministic program can have one expected final graph. Reference processing and concurrent execution can permit several outcomes. The oracle checks the conditions that each outcome must satisfy.

## Object graph

The graph contains objects, reference slots, and roots.

* An object has a stable identity, a shape, and data. Its shape describes fields or array elements; its data lets the test exercise allocation sizes and check for corruption.
* A reference slot belongs to an object or array position. It holds a target or null, and has a reference kind such as strong, soft, or weak.
* A root is a named strong reference held by the program. Roots include shared references and temporary references held by individual threads.

A slot has its own identity, independent of its target. Changing `a.left` from `b` to `c` changes the slot's contents. It does not create a different slot. Several slots can point to the same object, and the graph can contain cycles and self-references.

For example, this graph has two references from `a` to `b`:

```text
root -> a
        a.left  --strong--> b
        a.right --weak----> b
```

Objects become eligible for collection as references and roots are released. There is no operation that directly frees a Java object.

## Graph program language (GIR)

GIR describes allocation, reference reads and writes, root lifetimes, and data access. It also describes conditionals, bounded loops, worker threads, and synchronization. Larger operations, such as swapping targets or cloning a subgraph, are compositions of these smaller operations.

The initial graph and GIR program are shared inputs to two independent paths. The oracle interprets GIR to derive permitted behavior. The Java generator translates the same graph and program into executable Java. The checker compares the observed Java execution with the behavior permitted by GIR.

GIR expresses graph operations independently of JVM-specific APIs and flags. The JVM integration supplies the target-specific execution controls and observation facilities.

Temporary references have explicit lifetimes. Reading an object into a local variable can keep it alive while an operation runs. The oracle accounts for these references across all threads, and the generated program releases them at the intended points.

Observation points are called checkpoints. A checkpoint states which workers must have reached it and which collection or reference-processing conditions its checks require. Taking an observation and requesting a collection are separate actions.

## Soft and weak references

A program can manipulate an object obtained through a soft or weak reference. It reads the reference once, checks whether the object is present, and holds the successful result strongly for the duration of the operation.

Consider the initial graph:

```text
root -> a
        a.cache --soft--> b
```

The following pseudocode illustrates a GIR program that first gives `b` a child, then tries to save that child through `a`. Each scope releases its temporary references when it ends.

```text
with acquire(a.cache) as b, if present:
    c = allocate(Node)
    b.child = strong(c)

with acquire(a.cache) as b, if present:
    a.saved = strong(b.child)

checkpoint()
```

If `b` is present during both acquisitions, `a.saved` must point to the `c` created in the first scope. If the soft reference is cleared between the scopes, the second scope is skipped. Once `a.saved` holds `c`, clearing the soft reference to `b` must not cause `c` to disappear.

The program records which acquisitions succeeded. The oracle checks whether those choices and their consequences are consistent with the reference history. For example, a cleared reference cannot return its former target later unless the program replaces it with a new reference.

This gives a conditional program several legal outcomes without requiring every softly or weakly reachable object to survive.

## Oracle

The oracle derives expectations from the initial graph, the GIR program, and the permitted execution choices. A recorded choice must itself be valid before the checker uses it to determine subsequent expectations.

The checks cover both objects and their contents:

* Required roots and strongly reachable objects retain their expected identities and data.
* Reference slots have the expected kind. Their targets change only through program operations or clearing permitted for that kind.
* A softly reachable object that remains accessible still has its expected ordinary strong fields.
* Concurrent reads and updates follow the program's synchronization and operation contracts.
* Reference clearing follows the rules for the reference kind, including relationships between references to connected objects.

Reference clearing, queue notification, and physical reclamation are distinct events. Reclamation-sensitive checks require evidence that the relevant processing has completed. The presence of inaccessible garbage alone does not establish a correctness failure.

The checker reports whether the observations satisfy the oracle, violate it, or cannot be decided with the available guarantees and checking budget. A mismatch is a case to investigate: the fault can be in the JVM, generated program, observer, or oracle. Crashes, timeouts, and resource failures are recorded separately.

## Concurrency and JIT compilation

Generated programs contain multiple workers that allocate and manipulate objects concurrently. Synchronization is part of the program so that the oracle can reason about publication, ownership transfer, and shared updates.

Some workloads have an exact result after synchronization. For example, two workers can update different fields of the same object. Other workloads, such as competing atomic updates to one reference, have several permitted results. Deliberately racy workloads need checks that account for their allowed memory-model behavior.

The generator emits reusable Java methods and loops that directly access fields and arrays. Repeated execution makes these methods candidates for JIT compilation. Workloads also exercise objects held in local variables across calls, allocations, and compilation transitions.

A run can warm up the generated methods before entering a concurrent stress phase. Warm-up uses separate objects or forms an explicit part of the modeled program. A run that requires compilation checks that the relevant generated methods compiled. Compilation and collection activity are recorded so that a run can distinguish a satisfied correctness check from a missing stress condition.

The observer and recorder must preserve the intended test. They must not keep every object alive or serialize every mutation. Heap observations and checks performed by the program complement each other, including when compiler optimizations eliminate allocations that need no physical object.

## JVM integration and stress generation

The graph semantics and oracle are shared across JVMs. A JVM integration supplies launch configuration, capability detection, collection and compilation controls, runtime measurements, and graph observation. Adding a JVM extends these facilities while preserving the common validation rules.

Each run uses an explicitly selected JVM and collector. The integration reports unavailable capabilities rather than silently selecting a different collector or weakening a required check.

Generation combines graph patterns with physical workloads: chains, cycles, shared targets, varied object shapes, arrays, allocation bursts, and references between long-lived and newly allocated objects. Target measurements guide size and generation choices. Heap layout and collector heuristics influence stress generation without determining semantic correctness.

## Reproduction

A saved case contains the initial graph, GIR program, generation seed, worker structure, JVM configuration, and observations needed to investigate the result. Collection and compilation evidence accompany the execution trace, snapshots, logs, and failure information.

Reduction removes unnecessary operations, objects, loop iterations, workers, and settings while preserving the relevant failure. If reproduction depends on concurrency or compilation, reduction must retain those conditions. Timing-sensitive cases may require repeated execution.
