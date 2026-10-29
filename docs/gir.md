# GIR

GIR (Gorrect Intermediate Representation) describes the graph and workload independently of a JVM. Version 1 supports exact strong graphs, soft/weak references, and bounded concurrent histories with volatile and atomic operations. Unsupported versions and operations are rejected.

## Example

```text
gir 1;
shape N object 2 1;
object 1 N [2, null] [7];
object 2 N [null, null] [9];
root main = 1;

program {
  scope {
    read-root parent N main;
    read-ref child N parent 0;
    read-data saved child 0;
    write-data child 0 42;
    created: allocate fresh N;
    write-data fresh 0 saved;
    write-ref parent 1 fresh;
    updated: checkpoint;
  }
  finished: checkpoint;
}
```

`parent` reads object 1 from `main`; `child` follows slot 0 to object 2. `saved` copies 9 before that data becomes 42. The new object receives the saved 9 and is linked through the parent's slot 1. All three objects remain reachable through `main` after scope exit.

## Objects and declarations

A file starts with `gir 1;`, followed by declarations and one `program { ... }` block. `//` starts a line comment. Names contain ASCII letters, digits, and underscores, start with a letter or underscore, and cannot be `null`.

Each logical object has:

| Part | Information |
| --- | --- |
| Identity | A logical identity local to the case, separate from the payload. |
| Shape | A declared name, layout kind, and reference/data slot counts. |
| Reference slots | A kind (strong, soft, weak) and target identity or `null` at each index. Soft/weak values also identify their wrapper incarnation. |
| Data slots | Signed 64-bit payload values. A value equal to an object ID still forms no graph edge. |

Shapes are reusable layouts: `object` selects fields, `refs` reference arrays, and `data` long arrays. Counts specify fields or elements. Identity is separate metadata; JVM headers, addresses, alignment, and collector bookkeeping are outside this model.

| Declaration | Meaning |
| --- | --- |
| `shape N object 2 1;` | Declares layout `N` with reference slots 0 and 1, and data slot 0. Creates no instance. |
| `shape Links refs 4;` | Declares a reference-array layout with element indices 0 through 3. |
| `shape Payload data 8;` | Declares a long-array layout with element indices 0 through 7. |
| `object 1 N [2, null] [7];` | Creates initial object ID `1` using `N`: reference slot 0 targets object `2`, reference slot 1 is null, and data slot 0 contains `7`. |
| `root main = 1;` | Declares named strong root slot `main`, initially targeting the object declared with ID `1`. |
| `root saved = null;` | Declares a root slot whose initial target is null. |

The first list supplies reference values; the second supplies data. References use an ID or `null` for strong slots, `soft(2)` / `weak(2)` for wrapped targets, and `soft(null)` / `weak(null)` for cleared wrappers. Roots are strong.

List lengths must match the shape, and non-null targets must be declared. Forward references, aliases, cycles, and self-references are allowed. Shape names, initial IDs, and root names are unique in their namespaces. Initial IDs are positive signed 64-bit integers; counts and lengths are nonnegative signed 32-bit integers.

## Identities and access

Initial IDs are chosen by the author or generator: `object 1 ...` declares ID `1`, and `root main = 1;` selects that object. IDs need not be consecutive or follow declaration order. Mutation preserves object identity.

Programs access objects through handles from roots, reference slots, or allocation. Operands accept handles or `null`; ID literals select targets only in declarations. IDs provide no lookup operation or liveness guarantee. Multiple roots and handles can share a target.

Labels identify instructions, not objects: `created` names the allocation above; `fresh` holds its result. Access requires no internal instruction number.

Diagnostic identities are `(worker, allocation site, occurrence)`: initial object `1` uses `(0, 0, 1)`; main allocations use `(0, s, occurrence)`. Workers are numbered in declaration order. Sites are internal instruction IDs; occurrences start at one per site and advance across repeated worker visits. Handles require no identity arithmetic.

## Instructions

Instructions accept optional labels such as `updated: checkpoint;`. Labels follow the naming rules above and are unique across the program. Formatting preserves labels and execution order; internal instruction IDs are assigned automatically.

The [instruction types](../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) define each operation's preconditions, effects, and failure behavior. Slot indices are signed 32-bit integer literals. Reference and data indices start at zero in separate namespaces; a slot's identity is its owning object, namespace, and index.

| Instruction | Effect |
| --- | --- |
| `allocate h N;` | Allocate a zero-filled object and bind handle `h`. |
| `read-root h N root;` | Read a root into a handle declared with shape `N`. |
| `write-root root h;` | Set an existing root to a handle's target. |
| `read-ref h N owner index;` | Read a strong slot into a handle declared with shape `N`. |
| `write-ref owner index h;` | Replace a slot with a strong reference. |
| `write-soft owner index h;` / `write-weak owner index h;` | Install a fresh soft/weak wrapper, including for `null`. |
| `acquire h N owner index { ... }` | Read a soft/weak referent once; execute the body with strong handle `h` if non-null. |
| `is-cleared value owner index;` | Bind scalar 1 for a cleared wrapper, otherwise 0, without acquiring the referent. |
| `clear-ref owner index;` | Explicitly clear the current wrapper without enqueueing it. |
| `poll-queue;` | Record one wrapper incarnation from the case's reference queue, or an empty result. |
| `read-data value owner index;` | Bind a scalar to a data slot's contents. |
| `write-data owner index value;` | Write a scalar or integer literal. |
| `compare-set-root won root expected update;` | Compare root identity and conditionally update; bind scalar 1 on success, otherwise 0. |
| `compare-set-ref won owner index expected update;` | Compare and conditionally update a strong slot by identity. |
| `compare-set-data won owner index expected update;` | Compare and conditionally update a long slot by value. |
| `exchange-root old N root update;` | Atomically replace a root and bind its old strong target. |
| `exchange-ref old N owner index update;` | Atomically replace a strong slot and bind its old target. |
| `exchange-data old owner index update;` | Atomically replace a long slot and bind its old value. |
| `if-present h { ... }` | Execute a child scope when the existing strong handle is non-null. |
| `release name;` | End the lifetime of a binding in the current scope. |
| `scope { ... }` | Execute a block with its own bindings. |
| `repeat count { ... }` | Execute a block a nonnegative, literal number of times. |
| `checkpoint;` | Record the graph obligations at this point. |
| `collect;` | Request collection without changing the logical strong graph. Actual progress is recorded separately. |
| `parallel { worker name { ... } ... }` | Start workers together and join all before continuing. |
| `barrier;` | Finish a worker phase and wait for every peer before the next phase. |

Reference operands are live handles or `null`; scalar operands are live scalar bindings or signed 64-bit literals. Reference slots and named roots can hold any target shape. A non-null reference read asserts the declared destination shape exactly. Field and array access requires a live, non-null owner and an index within the corresponding reference or data count. Static validation checks all blocks, including zero-iteration loops, for names, types, slot bounds, and labels.

Root, strong-reference, and data reads/writes have volatile variants with the same operands: for example, `read-volatile-root h N root;` and `write-volatile-data owner 0 value;`. Compare-set and exchange always have volatile read/write effects; failed compare-set has read effects and never fails spuriously.

Bindings live until release or scope exit. Reference bindings are strong handles; scalars retain no objects. Names cannot shadow active-scope declarations or be reused after release in the same scope. Each loop iteration has a fresh scope. The outer scope ends before the final graph is derived.

## Reference histories

Reference histories use ordinary-object shapes and can include parallel workers under the [contended-history rules](#contended-histories). Acquisition results stay strong until scope exit; checkpoints must follow that scope. [references.gir](../examples/references.gir) shows a complete example.

The checker validates recorded choices before using them. GC follows Java's [soft](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/SoftReference.html) and [weak](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/WeakReference.html) reference rules, including atomic clearing of related references. Clearing is permanent for each wrapper; stores create fresh [incarnations](../engine/src/main/java/io/connglli/gorrect/engine/model/WrapperId.java). Accessible referents retain exact fields and data.

Clearing, queue delivery, and reclamation are separate. Empty queue polls prove no processing progress, and collection requests force neither clearing nor reclamation. Unreachable nodes and detached wrappers may remain. Impossible histories violate the oracle; missing coverage or exhausted search is inconclusive. Reclamation, finalization, and resurrection checks are unsupported.

## Parallel workers

Workers have independent scopes and share state through named roots, without capturing caller bindings. Names are unique within each block; numeric IDs are internal. Limits are 16 workers per block and 64 declarations per program. Nested parallel blocks and worker checkpoints are unsupported; observe after joining.

The exact profile requires conflict-free phases: a slot written by one worker cannot be read or written by another, even with the same value. This covers roots and reference/data fields or elements, including aliases. Shared reads and distinct-slot writes are allowed. The oracle rejects concrete conflicts before launch, making graph contents and per-worker reads independent of interleaving.

For example, both workers can mutate separate data fields of one object:

```text
gir 1;
shape N object 0 2;
object 1 N [] [0, 0];
root shared = 1;
program {
  parallel {
    worker left { read-root n N shared; write-data n 0 11; }
    worker right { read-root n N shared; write-data n 1 22; }
  }
  checkpoint;
}
```

Barriers appear directly in worker bodies, with equal counts per worker. They preserve bindings and make all preceding phase writes visible before any following phase starts. [concurrent.gir](../examples/concurrent.gir) exercises local retention and publication across GC, allocation, calls, and a barrier.

## Contended histories

Volatile/atomic operations or parallel reference processing select bounded history checking. Before launch, exhaustive exploration must establish that every sequentially consistent schedule satisfies preconditions and has no ordinary data race. Any possible ordinary race is unsupported; a rejected precondition is invalid input. Exhausting the proof budget is inconclusive.

The checker matches worker order, returned identities/values, branches, barriers, and snapshots to a legal schedule. File order and timestamps impose no extra cross-worker order. All live worker and caller handles protect referents. [contended.gir](../examples/contended.gir) transfers ownership using exchange-to-null.

History execution currently requires ordinary-object shapes and checkpoints outside conditional scopes. [Java's memory model](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5) and [VarHandle](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/invoke/VarHandle.html) define the ordering guarantees; acquire/release-only modes and arbitrary data races are outside this profile.

## Stress requirements

Declare `require concurrent-overlap;`, `require compiled-execution;`, and `require gc-during-workers;` before `program` to constrain execution coverage independently of the expected graph. Profile requirements also apply.

| Requirement | Evidence |
| --- | --- |
| `concurrent-overlap` | At least two workers, with all workers' measured active intervals overlapping in one phase. This does not claim simultaneous CPU instruction execution. |
| `compiled-execution` | Successful installation requests and VM-reported compiled generated-method frames at worker entry and exit, after the configured modeled warm-up visits. |
| `gc-during-workers` | Reported GC pause progress during a retained worker collection request. This does not establish concurrent collector completion. |

Required conditions must hold in one parallel-block visit. Overlap plus GC requires collection in an overlapping phase; compiled execution plus GC requires compiled-frame witnesses immediately before and after that call. Missing evidence leaves requirements unmet regardless of graph verdict. Witnesses cannot prove continuously compiled execution or the requested tier.

## Oracle obligations

Checkpoint visits are distinguished by internal instruction ID and visit number; the program maps IDs to labels. Expectations include every named root, live reference binding, and logical object reachable through them, with exact shape, reference kind, targets, and data. Unreachable garbage may remain in an observation. Missing or extra required slots, changed roots or handles, and corrupted contents violate the expectations. Incomplete observations are inconclusive.

Model budgets bound steps, allocated objects (including wrappers), slots, and cumulative snapshot cells. [OracleLimits](../engine/src/main/java/io/connglli/gorrect/engine/oracle/OracleLimits.java) defines cell accounting; [instruction contracts](../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) define step costs and rejection behavior. These bounds are independent of target heap capacity. Exhaustion produces a checking limit or inconclusive history check.
