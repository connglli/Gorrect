# Gorrect

## Approach

The draft idea on validating the correctness of the implementations of the GC in an LVM is based on *Object Graph* and
manipulations on it:

1. Initialize a graph G = (V, E): Nodes represent objects, edges represent references.
2. Generate manipulation sequence t: A sequence of operations: add_node, remove_node, add_edge, remove_edge, where edges
   may be labeled with reference strength (strong/soft/weak/phantom/...).
3. Apply t to G: Resulting in a new graph state G*.
4. Object association: Each node n is mapped to a LVM object o.
5. Reference association: Each edge e is implemented as a reference field between objects.
6. Operation association: Each operation op is implemneted as a series of statements allowed by LVM.
7. Generate a test case T for the LVM: A program T that builds the initial state G and applies a sequence t (with
   correct reference types).
8. Run T in LVM: Execute with full GC enabled (or specific GC configurations).
9. Extract the reached heap state and translate it into a graph G$: Via JVMTI, jmap, heap dump, or Java Flight Recorder.
10. Compare G$ with expected G*: Differences suggest issues in GC implementation or test harness logic.

## Oracle

For step 10, the correctness oracle should be: For each edge e (from node A to B, with ID) in G*:

* Strong Reference: If B is reachable via strong paths in G*, then G$ must have the same strong edge. If B is
  unreachable, it’s fine for the edge to be absent.
* Soft Reference: G$ may or may not contain it. Missing soft edge ≠ bug.
* Weak Reference: If B is only reachable via weak paths in G*,
  G$ must not contain the edge. If B is still strongly/softly reachable, the edge may still exist in G$.
* Any unexpected strong reference in G$ (not in G*) → GC bug.
* Any weak reference in G$ whose referent was only weakly reachable in G* → GC bug.

## Operations

### Basic Operations

For step 2, the most basic operations should be:

* _AddObjOperation_: Adds a new object to the graph. This operation should take into account the type (root/nonroot) and
  size of the new object. This operation aims to increase the heap usage.
* _AddRefOperation_: Adds a new reference to the graph. This operation should take into account the referer, the
  referent,
  and the type or strength (strong/soft/weak) of the reference.
* _RemoveObjOperation_: Removes an existing object from the graph. This operation may cause some objects no longer
  reachable. This operation aims to reduce the heap usage.
* _RemoveRefOperation_: Removes an existing reference from the graph. This operation may cause some objects no longer
  reachable. This operation aims to reduce the heap usage.

All other operations should built upon the four.

### Advanced Operations

The following advanced operations may exist:

* _AddRootObjOperation_: Adds a new object to the graph as one of a root object. This operation opens a new channel for
  adding a large number of reachable objects in the future. This operation aims to increase the heap usage.
* _AddNonRootObjOperation_: Adds a new object to the graph as one of a non-root object. To make the object reachable,
  this
  operation requires a source object to reference to it. This operation also aims to increase the heap usage.
* _MakeNonRootObjRootOperation_: Makes a non-root object one of a root object. This adds the object into the root set of
  GC marking, opening a new channel for adding reachable objets. This operation also cause objects that are rooting from
  the this object live longer than before.
* _MakeRootObjNonRootOperation_: Makes a root node non-root. This removes the node from the root set of GC marking and
  consequently making a lot of
* _AddStrongRefOperation_: Adds a strong reference between two objects.
* _AddSoftRefOperation_: Adds a soft reference between two objects.
* _AddWeakRefOperation_: Adds a weak reference between two objects.
* _ReplaceReferentOperation_: Replaces the referent (target) of a reference to a new referent. This changes the
  reference
  relation in the graph, causing some object no longer reachable and reduce heap usage. On the other hand, it increases
  the chance that objects rooting from the new referent will possibly live longer than before replacing the target.
* _SwapReferentsOperation_: Swaps the referent (target) of two references. Even though the reachability of all objects
  remain unchanged. This drastically changes the reference relation in the graph,including the reference chains of each
  object, and the reachability strength of each object.
* _SoftenRefOperation_: Softens the type of a reference one layer up. This will drastically change the reachability
  strength
  of each object in the graph.
* _StrengthRefOperation_: Strengthens the type of a reference one layer up. This will drastically change the
  reachability
  strength of each object in the graph.

### More Advanced Operations

Here are some complementary operations that could enhance your test coverage:

##### Memory Pressure Operations

* _AllocateLargeObjectOperation_: Creates objects of specific sizes to test heap pressure scenarios
* _CreateObjectArrayOperation_: Creates arrays with many references to test bulk reference processing
* _CreateCircularRefOperation_: Creates circular reference chains of specified length
* _CreateDeepRefChainOperation_: Creates long linear reference chains to test deep marking

##### GC-Specific Stress Operations

* _CreateFinalizableObjectOperation_: Adds objects with finalizers to test finalization queues
* _CreateOffHeapRefOperation_: Tests references to/from off-heap memory (if applicable)
* _TriggerOutOfMemoryOperation_: Intentionally causes memory pressure to force GC
* _CreateInterGenerationalRefOperation_: Creates references between different generations (for generational GCs)

##### Reference Pattern Operations

* _CreateFanOutRefOperation_: Creates one object referencing many others (1:N pattern)
* _CreateFanInRefOperation_: Creates many objects referencing one target (N:1 pattern)
* _CreateBidirectionalRefOperation_: Creates mutual references between objects
* _CloneSubgraphOperation_: Duplicates a portion of the graph to test reference copying

##### Temporal Operations

* _DelayedReferenceOperation_: Schedules reference changes to occur after a delay
* _ConditionalReferenceOperation_: Changes references based on memory conditions
* _BatchOperationSequence_: Groups multiple operations to execute atomically

##### Advanced Reference Manipulation

* _PromoteRefStrengthOperation_: Converts weak→soft→strong in one step
* _DemoteRefStrengthOperation_: Converts strong→soft→weak→phantom in one step
* _CreateRefCycleWithMixedStrengthOperation_: Creates cycles with different reference strengths
* _IsolateSubgraphOperation_: Removes all incoming references to a subgraph except one

##### Monitoring and Verification Operations

* _SnapshotGraphOperation_: Captures current graph state for comparison
* _ValidateReachabilityOperation_: Checks if expected objects are reachable
* _MeasureMemoryUsageOperation_: Records heap usage at specific points
* _ForceGCOperation_: Explicitly triggers garbage collection

## Gorrect IR (GIR)

Based on the above, we can have an IR targeting object graphs. Let's name if Gorrect IR or GIR for short.

GIR is composed of a set of instructions, each mapping to a specific operation mentioned above. GIR also have some
set-up instructions that configure the heap initially. Textually, a GIR program should looks like (numbers before colons
indicate line numbers):

```shell
0: .set                 \
      heapsize=4g       \
      init=/path/to/state.g
1: add-root-obj 30m     # Add a root object of size 30MB which produced $1
2: add-nonroot-obj 25k, \
     strong, $1         # Add a non-root object $2 of size 25KB which is referenced by $1 and the new reference is %2
3: add-weak-ref $2, $1  # Add a weak reference $2->$1 and the reference's id is %3
4: add-root-obj 1k      # Add a root object of size 1k and the new object is $4
5: add-nonroot-obj 1k,  \
     weak, $4           # Add a non-root object $5 referenced by $4 and of size 1KB and the new reference is %5
6: add-soft-ref $4, $2  # Add a soft reference %6: $4->$2
7: remove-obj $4        # Remove the object $4
```

The GIR program yields the following object graph should be:

```text
Objects:
 $1 (root,30m)
 $2 (nonroot,25k)

References:
 %2: $1 -> $2 (strong)
 %3: $2 -> $1 (weak)
```

Based on GIR, we can have two interpreters:

1. An *Oracle Interpreter* that directly executes the program and yields an object graph which should be the oracle
   G*.
2. An *LVM Interpreter* that compiles the program into a LVM program T, executes the program, dumps the reached heap
   state,
   and translates the state into the actual graph G$.

We then can validate the implementation by checking whether G$ aligns with G*.

So our goal is to generate such GIR programs that definitely don't produce out of memories from an initial object graph
G. We insist an initial state rather than an empty state during our validation because, the initial state give us more
flexibility. Even though it is identical to generate such initial state via generating a random GIR prologue, we're not
able, at least not easy, to control the generated state in this way.

## Implementations

1. **Operation Weights**: Consider assigning probabilities to operations based on their real-world frequency.
2. **Precondition Checking**: Some operations need preconditions (e.g., we can't remove a reference that doesn't
   exist).
3. **Operation Composition**: Some complex scenarios might benefit from composite operations that combine atomic ones.
4. **Reference Type Constraints**: Different GC implementations handle reference types differently - ensure your
   operations can test these variations.
5. **Concurrent Operations**: Consider operations that simulate concurrent access patterns if testing concurrent GCs.
6. **Parallel GC**: Consider modeling parallelisms targeting multiple GC threads.
7. **Stress patterns**: Consider patterns that push specific GC limits
8. **Realistic workload patterns**: Consider patterns based on common application behaviors
