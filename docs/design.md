# Object Graph Verification

Object Graph Verification checks observed objects, references, and data against an independent model of program behavior. Gorrect applies it to garbage collector validation through generated Java programs.

A test combines an initial graph with a program in GIR (Gorrect Intermediate Representation). Workloads include sequential mutations, concurrent workers, and hot methods and loops for JIT stress.

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

A slot's identity is independent of its target: changing `a.left` from `b` to `c` changes its contents, not the slot. References can share targets, form cycles, or point to their owner.

For example, this graph has two references from `a` to `b`:

```text
root -> a
        a.left  --strong--> b
        a.right --weak----> b
```

Objects become eligible for collection as references and roots are released. There is no operation that directly frees a Java object.

## Graph program language (GIR)

GIR describes allocation, reference/data access, root lifetimes, conditionals, bounded loops, workers, and synchronization. Larger operations compose these primitives. JVM-specific APIs and flags belong to the JVM integration.

The oracle interprets the graph and GIR program to derive permitted behavior; independently, the Java generator translates them into executable code. The checker compares that execution with the oracle.

Temporary references have explicit lifetimes across threads. The oracle accounts for objects held in locals, and generated code preserves and ends those lifetimes at the intended points.

Checkpoints are observation points with required worker and processing conditions. Observation and collection requests are separate actions.

## Soft and weak references

A program reads a soft/weak reference once and holds a successful result strongly while manipulating it.

Consider the initial graph:

```text
root -> a
        a.cache --soft--> b
```

This pseudocode gives `b` a child, then tries to save it through `a`. Each scope releases its temporary references on exit.

```text
with acquire(a.cache) as b, if present:
    c = allocate(Node)
    b.child = strong(c)

with acquire(a.cache) as b, if present:
    a.saved = strong(b.child)

checkpoint()
```

If both acquisitions succeed, `a.saved` must point to the created `c`. Clearing the soft reference between scopes skips the second body. Once saved strongly, `c` must survive even if the soft reference to `b` clears.

The oracle validates recorded acquisition choices and their consequences, allowing legal clearing policies. A cleared wrapper cannot return its former target again; replacement creates a new wrapper.

## Oracle

The oracle derives expectations from the initial graph, GIR, and permitted choices. It validates a recorded choice before using it to determine later expectations.

The checks cover both objects and their contents:

* Required roots and accessible objects retain exact identities, fields, and data.
* Slots retain their expected kind and targets except for program writes or legal clearing, including clearing rules for connected objects.
* Concurrent reads and updates obey synchronization and operation contracts.

Clearing, queue notification, and reclamation are distinct. Reclamation checks need processing-completion evidence; retained garbage alone is not a failure.

Verdicts are satisfied, violated, or inconclusive. A mismatch warrants investigation of the JVM, generated code, observer, and oracle. Execution failures remain separate.

## Concurrency and JIT compilation

Workers allocate and mutate concurrently. Explicit synchronization lets the oracle reason about publication, ownership transfer, and shared updates.

Independent updates, such as writes to distinct fields, can have an exact synchronized result. Competing atomic updates admit several results; deliberately racy programs need a separate memory-model contract.

Reusable Java methods and loops access fields and arrays directly, making repeated execution eligible for JIT compilation. Workloads retain locals across calls, allocations, and compilation transitions.

Warm-up forms part of the modeled program. Required compilation and collection conditions need execution evidence independently of the graph verdict.

Recorders must not retain workload objects or serialize ordinary mutations. Heap capture and program checks complement each other when optimization eliminates physical allocations.

## JVM integration and stress generation

JVM integrations supply launch settings, capability detection, VM controls, measurements, and observation. They share graph semantics and the oracle.

Each run explicitly selects a JVM and collector; missing capabilities remain visible without substitution or weaker checks.

Generation combines chains, cycles, sharing, shapes, arrays, allocation bursts, and references across object lifetimes. Target measurements and collector heuristics guide stress without determining correctness.

## Reproduction

A saved case retains the graph, GIR, seed, JVM settings, traces, snapshots, VM evidence, and diagnostics.

Reduction removes operations, objects, iterations, workers, and settings while preserving the failure and any required concurrency or compilation conditions. Timing-sensitive cases may need repeated execution.
