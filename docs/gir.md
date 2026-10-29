# GIR

GIR (Gorrect Intermediate Representation) describes the graph and its workload independently of a JVM. Version 1 supports sequential programs, conflict-free parallel workers, barriers, collection requests, and stress requirements. It executes strong references only. The format is under development; unsupported versions and operations are rejected.

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

`parent` obtains object 1 through `main`; `child` follows its reference slot 0 to object 2. `saved` retains the copied 9 after object 2's data slot 0 becomes 42. Object 1's reference slot 1 is updated to point to the new object, whose data holds the saved 9. All three objects remain reachable through `main` after scope exit.

## Objects and declarations

A file starts with `gir 1;`, followed by declarations and one `program { ... }` block. `//` starts a line comment. Names contain ASCII letters, digits, and underscores, start with a letter or underscore, and cannot be `null`.

Each logical object has:

| Part | Information |
| --- | --- |
| Identity | A logical identity local to the case, separate from the payload. |
| Shape | A declared name, layout kind, and reference/data slot counts. |
| Reference slots | A kind (strong, soft, weak) and target identity or `null` at each index. Executable GIR 1 permits strong only. |
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

The first bracketed list supplies reference targets; the second supplies data values. Their lengths must match the shape. Non-null targets must be declared, with forward references, aliases, cycles, and self-references permitted. Shape names, initial IDs, and root names are unique in their respective namespaces. Initial IDs are positive signed 64-bit integers; counts and lengths are nonnegative signed 32-bit integers.

## Identities and access

Initial IDs are chosen by the author or generator: `object 1 ...` declares ID `1`, and `root main = 1;` selects that object. IDs need not be consecutive or follow declaration order. Mutation preserves object identity.

Programs access objects through handles obtained from roots, reference slots, or allocation. Reference operands accept handles or `null`; object-ID literals select targets only in initial declarations. GIR provides no lookup-by-ID operation, and an ID does not keep an object alive. Multiple roots and handles can share a target.

Labels identify instructions, not objects: `created` names the allocation above; `fresh` holds its result. Access requires no internal instruction number.

The engine exposes logical identities as `(worker, allocation site, occurrence)` in oracle results. Initial object `1` uses `(0, 0, 1)`; main-program allocations use `(0, s, occurrence)`. Workers receive internal identities in declaration order; their allocations use that worker identity. Site `s` is assigned internally, and occurrences start at one per site. Repeating a parallel block preserves logical worker identities and advances allocation occurrences. Handles provide access without calculating these diagnostic identities.

## Instructions

Instructions accept optional labels such as `updated: checkpoint;`. Labels follow the naming rules above and are unique across the program. Formatting preserves labels and execution order; internal instruction IDs are assigned automatically.

The [instruction types](../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) define each operation's preconditions, effects, and failure behavior. Slot indices are signed 32-bit integer literals. Reference and data indices start at zero in separate namespaces; a slot's identity is its owning object, namespace, and index.

| Instruction | Effect |
| --- | --- |
| `allocate h N;` | Allocate a zero-filled object and bind handle `h`. |
| `read-root h N root;` | Read a root into a handle declared with shape `N`. |
| `write-root root h;` | Set an existing root to a handle's target. |
| `read-ref h N owner index;` | Read a reference slot into a handle declared with shape `N`. |
| `write-ref owner index h;` | Retarget a reference slot. |
| `read-data value owner index;` | Bind a scalar to a data slot's contents. |
| `write-data owner index value;` | Write a scalar or integer literal. |
| `release name;` | End the lifetime of a binding in the current scope. |
| `scope { ... }` | Execute a block with its own bindings. |
| `repeat count { ... }` | Execute a block a nonnegative, literal number of times. |
| `checkpoint;` | Record the graph obligations at this point. |
| `collect;` | Request collection without changing the logical strong graph. Actual progress is recorded separately. |
| `parallel { worker name { ... } ... }` | Start workers together and join all before continuing. |
| `barrier;` | Finish a worker phase and wait for every peer before the next phase. |

Reference operands are live handles or `null`; scalar operands are live scalar bindings or signed 64-bit literals. Reference slots and named roots can hold any target shape. A non-null reference read asserts the declared destination shape exactly. Field and array access requires a live, non-null owner and an index within the corresponding reference or data count. Static validation checks all blocks, including zero-iteration loops, for names, types, slot bounds, and labels.

Bindings live until release or scope exit. Live reference bindings are strong handles; scalar bindings do not keep objects alive. Names cannot shadow another declaration in an active scope or be reused after release in that scope. Each loop iteration has a fresh scope. The program's outer scope ends before the final graph is derived.

## Parallel workers

Workers have separate local scopes and access shared state through named roots. They cannot capture caller bindings. Worker names are unique within their parallel block; users supply no numeric worker IDs. The implementation accepts up to 16 workers per block and 64 declarations per program. Nested parallel blocks and worker checkpoints are rejected; observe after joining workers.

Within a phase, a slot written by one worker must not be read or written by another. Slots include named roots, reference fields/elements, and data fields/elements. Aliases identify the same slot. Shared reads and writes to different slots of one object are allowed; concurrent writes to one slot are rejected even if their values agree. The oracle checks concrete accesses before launching the JVM, so the final graph and per-worker reads are independent of the phase's interleaving.

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

Barriers must appear directly in worker bodies, and every worker must have the same number. They preserve local bindings and establish visibility of all preceding phase writes before any following phase starts. This permits ownership transfer between phases. [concurrent.gir](../examples/concurrent.gir) retains objects locally across collection, allocation, calls, and a barrier before publishing them again.

## Stress requirements

Declare `require concurrent-overlap;`, `require compiled-execution;`, and `require gc-during-workers;` before `program` to constrain execution coverage independently of the expected graph. Profile requirements also apply.

| Requirement | Evidence |
| --- | --- |
| `concurrent-overlap` | At least two workers, with all workers' measured active intervals overlapping in one phase. This does not claim simultaneous CPU instruction execution. |
| `compiled-execution` | Successful installation requests and VM-reported compiled generated-method frames at worker entry and exit, after the configured modeled warm-up visits. |
| `gc-during-workers` | Reported GC pause progress during a retained worker collection request. This does not establish concurrent collector completion. |

Required conditions must hold together in one parallel-block visit. If overlap and GC are required, the collection belongs to an overlapping phase. If compiled execution and GC are required, that request also needs compiled-frame witnesses immediately before and after the call. Missing conditions leave requirements unmet even when the graph satisfies the oracle. Witness points do not prove that the whole invocation, or the requested compiler tier, executed continuously as compiled code.

## Oracle obligations

Checkpoint visits are distinguished by internal instruction ID and visit number; the program maps IDs to labels. Expectations include every named root, live reference binding, and logical object reachable through them, with exact shape, reference kind, targets, and data. Unreachable garbage may remain in an observation. Missing or extra required slots, changed roots or handles, and corrupted contents violate the expectations. Incomplete observations are inconclusive.

The interpreter has explicit budgets for steps, allocated objects, slots, and cumulative snapshot cells. Each instruction, loop iteration, worker start/end, phase-start, and join marker consumes a step. A snapshot consumes one cell plus its objects, roots, handles, and reference and data slots; the final graph also counts. Budgets constrain the model, not target heap capacity. Exhaustion produces a checking-limit error, not a semantic verdict. Rejected primitive operations leave model state, identities, and budgets unchanged.
