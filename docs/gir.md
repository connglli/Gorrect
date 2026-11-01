# GIR

GIR (Gorrect Intermediate Representation) describes an object graph and workload independently of a JVM. Version 1 supports strong graphs, soft/weak references and bounded concurrent histories. Unsupported versions and combinations are rejected.

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

Object 2 changes from 9 to 42; a fresh object stores its original 9. All three objects remain reachable through `main` after the handles leave scope.

## Objects and declarations

A file starts with `gir 1;`, followed by declarations and one `program { ... }` block. `//` introduces a line comment. Names use ASCII letters, digits and underscores, start with a letter/underscore, and cannot be `null`.

Objects have stable logical identities, declared shapes and indexed reference/data slots. Object data fields use `int`/`long`; scalars/data arrays stay signed 64-bit. Int initializers/stores/atomic operands narrow; reads/old results sign-extend. Data creates no edges. Shapes specify fields/array elements; identity metadata, JVM headers and addresses are separate.

| Declaration | Meaning |
| --- | --- |
| `shape N object 2 1;` / `shape N object 2 [int, long];` | Two reference slots; long-field count or kind list; allocates nothing. |
| `shape Links refs 4;` | Reference-array layout with four elements. |
| `shape Payload data 8;` | Long-array layout with eight elements. |
| `shape W weak;` / `shape S soft;` | Nominal wrapper type without indexed payload slots. |
| `object 1 N [2, null] [7];` | Initial object ID 1: first reference targets ID 2, second is null, data is 7. |
| `root main = 1;` / `root saved = null;` | Named strong root slots with the stated initial targets. |
| `queue ready W;` | Named reference queue admitting only wrapper type `W`. |

Initial references are IDs, `null`, wrapped `soft(2)`/`weak(2)` or cleared `soft(null)`/`weak(null)`. Lists match shape counts; non-null targets must be declared. Forward references, aliases, cycles and self-references are allowed. Declaration names/IDs are unique within their namespaces. Initial IDs are positive signed 64-bit integers; counts are nonnegative signed 32-bit integers.

## Identities and access

Initial IDs are chosen by the author, need not be sequential, and select targets only in declarations. Programs obtain handles through roots, slots or allocation; there is no ID lookup operation. Mutation preserves identity, and several handles can share a target.

Labels name instructions, not objects: `created` names the allocation above, while `fresh` holds its result. Labels are optional and unique; internal instruction IDs are automatic. Diagnostic identities use `(worker, site, occurrence)`, with initial ID 1 represented as `(0, 0, 1)`. Programs need no identity arithmetic.

## Instructions

[Instruction.java](../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) defines exact preconditions, effects, step costs and rejection behavior. Reference operands are live handles or `null`; scalars are bindings or signed 64-bit literals. Reference/data indices are separate zero-based signed 32-bit literals. Access needs a live non-null owner and in-range slot; non-null reads must match their declared shape. Validation includes skipped arms and zero-iteration loops.

| Form | Purpose |
| --- | --- |
| `allocate h N;` | Fresh zero-filled instance and strong handle. |
| `clone copy original;` | Fresh shallow array copy with the source's nominal shape. |
| `copy-array source sourceIndex destination destinationIndex length;` | Copy a complete shallow range, preserving identities and overlap semantics. |
| `allocate-ref r W value;` / `allocate-ref r W null ready;` | Fresh soft/weak wrapper, optionally registered with a named queue. |
| `enqueue-ref inserted r;` | Clear even on failure; bind long 1 for first insertion, otherwise 0. |
| `read-root h N root;` | Copy a named root into a strong handle. |
| `read-volatile-root h N root;` | Volatile root read into a strong handle. |
| `write-root root h;` | Replace a named root's target. |
| `write-volatile-root root h;` | Volatile root write. |
| `read-ref h N owner index;` | Copy a strong slot into a handle. |
| `read-volatile-ref h N owner index;` | Volatile strong-slot read. |
| `write-ref owner index h;` | Replace any slot with a strong value. |
| `write-volatile-ref owner index h;` | Volatile strong-value write. |
| `write-soft owner index h;` | Install a fresh soft wrapper, including for null. |
| `write-weak owner index h;` | Install a fresh weak wrapper, including for null. |
| `acquire h N owner index { ... }` / `acquire h N r { ... }` | Acquire once; run the child scope if non-null. |
| `is-cleared value owner index;` / `is-cleared value r;` | Observe cleared state as 0/1 without acquiring the referent. |
| `clear-ref owner index;` / `clear-ref r;` | Explicitly clear without enqueueing. |
| `poll-queue;` / `poll-queue delivered ready;` | Implicit delivery, or consume any named member into a strong handle; null only if empty. |
| `read-data value owner index;` | Copy a data slot into a scalar. |
| `read-volatile-data value owner index;` | Volatile data-slot read. |
| `write-data owner index value;` | Replace a data slot. |
| `write-volatile-data owner index value;` | Volatile data-slot write. |
| `same-object equal left right;` | Bind 1 for identical targets, including two nulls; otherwise 0. |
| `identity-hash owner;` | Observe an opaque hash without binding a value; strong sequential controlled only. |
| `compare-set-root won root expected update;` | Strong CAS on a root, returning 0/1. |
| `compare-set-ref won owner index expected update;` | Strong CAS on a strong reference slot. |
| `compare-set-data won owner index expected update;` | Strong CAS on a data slot. |
| `exchange-root old N root update;` | Replace a root and bind its old target. |
| `exchange-ref old N owner index update;` | Replace a strong slot and bind its old target. |
| `exchange-data old owner index update;` | Replace a data slot and bind its old value. |
| `if-present h { ... }` | Child scope when a strong handle is non-null. |
| `if-nonzero value { ... } else { ... }` | Scalar-selected child scope; else is optional. |
| `release name;` | End a current-scope binding. |
| `scope { ... }` | Introduce a scope. |
| `monitor owner { ... }` | Synchronized child scope; non-null object/array owner; strong sequential controlled. |
| `repeat count { ... }` | Literal nonnegative repetitions, each with a fresh scope. |
| `checkpoint;` | Capture graph obligations. |
| `collect;` | Request collection. |
| `parallel { worker name { ... } ... }` | Start workers together and join all. |
| `barrier;` | Complete a worker phase and wait for peers. |

Root, strong-reference and data reads/writes are plain unless explicitly volatile. CAS/exchange have volatile effects; failed CAS has only read effects. CAS never fails spuriously. `if-nonzero` evaluates once: zero selects else, every nonzero value selects then. Branch scopes export no bindings; heap/root writes persist.

Reference handles stay strong until release or scope exit; acquired handles last through their whole body. Scalars retain nothing. Names cannot shadow bindings or repeat within scopes. Release requires no reclamation. The outer scope ends before final capture. [Profiles](./observation.md#coverage-and-observer-effects) define physical obligations for executed checkpoints.

Array cloning and range copying require strong sequential programs with controlled observation.

## Reference histories

`allocate-ref` creates wrappers for objects or older wrappers. Roots, strong slots and wrapper handles retain wrappers; acquired handles retain referents. Aliases share clear state; null and cleared wrappers differ. Immutable registration does not retain wrappers; queue members are strong roots before soft/weak processing.

Queued construction with an operand other than literal `null` needs the [qualified adapter](../engine/src/main/java/io/connglli/gorrect/engine/run/AutomaticQueues.java). Publish the constructor binding into an initially-null root before release; construction and publication must be top-level, and that root permits no other writes. Other aliases and publications are allowed.

First-class wrappers need sequential controlled [capture](./observation.md#tagged-reference-targets). Atomic/concurrent or natural execution, initial wrapper declarations, phantom references and Cleaner actors are unsupported. Legacy slot wrappers remain opaque, accept ordinary objects/null, and cannot become first-class values, even in skipped paths.

Histories validate acquisition/state/clear choices against Java's [soft](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/SoftReference.html)/[weak](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ref/WeakReference.html) rules, including supported legacy concurrency. Clearing is permanent per wrapper; soft/weak slot writes create fresh [incarnations](../engine/src/main/java/io/connglli/gorrect/engine/model/WrapperId.java). Accessible referents retain exact contents. Initial null and explicit clear alone never enqueue. Empty polls and collection requests prove neither clearing nor delivery. Retained garbage is allowed; reclamation, finalization and resurrection checks are unsupported. See [examples](../examples/).

## Parallel workers

Workers have private scopes and reach shared state through named roots, not caller bindings. Blocks allow up to 16 workers and 64 worker declarations per program, with unique names per block and no nested parallelism. Ordinary exact phases must be conflict-free: a slot written by one worker cannot be accessed by another. Shared reads and distinct-slot writes are allowed; aliases count as the same slot.

Barriers and worker checkpoints appear directly in every worker body with the same ordered sequence of kinds. They preserve bindings and publish preceding writes before the next phase. A worker checkpoint pauses all workers and the caller for one capture; the first declared worker's site/visit identifies it. See [concurrent.gir](../examples/concurrent.gir) and [worker-capture.gir](../examples/worker-capture.gir).

Worker capture requires `roots-diagnostic`. A capture-bearing parallel block can be inside a literal repeat/scope, but not under acquisition or a conditional. Worker checkpoints cannot be nested inside worker-local control blocks. Missing participants or physical roots leave coverage inconclusive.

## Contended histories

Volatile/atomic operations, parallel reference processing or worker checkpoints select bounded history checking. Before launch and again offline, every sequentially consistent schedule must satisfy preconditions and be free of ordinary data races. Any possible ordinary race is unsupported; a rejected precondition is invalid input; proof-budget exhaustion is inconclusive.

The checker matches per-worker order, values/identities, choices, synchronization and snapshots to a legal schedule. File order/timestamps impose no extra cross-worker order, and all live caller/worker handles protect referents. [contended.gir](../examples/contended.gir) demonstrates ownership transfer. Weaker ordering and arbitrary data races need separate contracts.

## Stress requirements

Declare these before `program`; profile requirements also apply:

| Declaration | Required evidence |
| --- | --- |
| `require concurrent-overlap;` | All workers' active intervals overlap in one phase, with at least two workers. |
| `require compiled-execution;` | Installation requests and actual compiled generated-method frames at worker entry/exit after modeled warm-up. |
| `require gc-during-workers;` | GC pause progress during a recorded worker collection request. |

Required conditions must hold in one parallel-block visit. Overlap plus GC needs collection in the overlapping phase; compiled execution plus GC needs frame witnesses immediately before/after that call. Evidence proves neither simultaneous CPU execution, continuously compiled code, the requested tier, nor collector-cycle completion.

## Oracle obligations

At each internal site/visit, required roots and accessible objects must have the expected identities, shapes, reference kinds, targets and data. Partial evidence may refute an obligation but cannot establish satisfaction. Budgets bound steps, allocations including wrappers, slots and retained snapshot cells independently of target heap capacity; [OracleLimits](../engine/src/main/java/io/connglli/gorrect/engine/oracle/OracleLimits.java) defines their accounting.
