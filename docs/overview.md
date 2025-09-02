# Gorrect

## Approach

The draft idea on validating the correctness of the implementations of the GC in an LVM is based on *Object Graph* and
manipulations on it:

1. Initialize a graph G = (V, E): Nodes represent objects, edges represent references.
2. Generate manipulation sequence t: A sequence of operations: add_node, remove_node, add_edge, remove_edge, where edges
   may be labeled with reference strength (strong/soft/weak/phantom/...).
3. Apply t to G: Resulting in a new graph state G*. This is the expected, oracle state.
4. Object association: Each node n is mapped to a LVM object o.
5. Reference association: Each edge e is implemented as a reference field between objects.
6. Operation association: Each operation op is implemneted as a series of statements allowed by LVM.
7. Generate a test case T for the LVM: A program T that builds the initial state G and applies a sequence t (with
   correct reference types).
8. Run T in LVM: Execute with full GC enabled (or specific GC configurations).
9. Extract the reached heap state and translate it into a graph G$: Via JVMTI, jmap, heap dump, or Java Flight Recorder.
   This is the actual, runtime state.
10. Compare G$ with G*: Differences suggest issues in GC implementation or test harness logic.

## Oracle

For step 10, the correctness oracle can be viewed from two aspects: the object view and the reference view.

### Object View

1. Root objects in G* should still be roots in G$.
2. Any root object in G$ that is not found in G* should be reported.
3. Strongly reachable objects in G* should remain strongly reachable.
4. Strongly reachable objects in G$ that are not found or not strongly reachable in G* should be
   reported.
5. Softly reachable objects in G* can be softly reachable or cleared in G$.
6. Softly reachable objects in G$ that are not found or not softly reachable in G* should be
   reported.
7. All weakly reachable objects in G* should not exist in G$ since they were all cleared after the last GC
   cycle.
8. There shouldn't be any weakly reachable objects in G$.

### Reference View

For each reference in G*, the reachability of the referer and referent and the
existence of the reference in G$ must satisfy the following matrix (where v means
must exist, o means it's okay if it doesn't exist, and x means must not exist):

```text
              referer
                 |
                 v
             +--------+--------+------+------+
referent --> |        | STRONG | SOFT | WEAK |
             +--------+--------+------+------+
             | STRONG |   v    |  o   |  x   |
             +--------+--------+------+------+
             |  SOFT  |   o    |  o   |  x   |
             +--------+--------+------+------+
             |  WEAK  |   x    |  x   |  x   |
             +--------+--------+------+------+
```

The matrix should be satisfied for all references in G*, regardless of its type.

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
  operation requires a source object to reference it. This operation also aims to increase the heap usage.
* _MakeNonRootObjRootOperation_: Makes a non-root object one of a root object. This adds the object into the root set of
  GC marking, opening a new channel for adding reachable objets. This operation also cause objects that are rooting from
  the this object live longer than before.
* _MakeRootObjNonRootOperation_: Makes a root node non-root. This removes the node from the root set of GC marking and
  consequently making a lot of
* _AddStrongRefOperation_: Adds a strong reference between two objects.
* _AddSoftRefOperation_: Adds a soft reference between two objects.
* _AddWeakRefOperation_: Adds a weak reference between two objects.
* _ReplaceReferentOperation_: Replaces the referent (target) of a reference with a new referent. This changes the
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

Here are some complementary operations that could enhance in the future:

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
4. **Reference Type Constraints**: Different GC implementations handle reference types differently - ensure the
   operations can test these variations.
5. **Concurrent Operations**: Consider operations that simulate concurrent access patterns if testing concurrent GCs.
   The GIR program generator should control whether to generate operations that can cause concurrent GCs (while the
   Object Graph and Orale Interpreter are kept clean without considering such things). For example, when generating, the
   generator measures the heap usage. When the heap usage reaches the limit of the Eden space, it knows the LVM will
   start STW marking and concurrent marking. At this time, the generator can choose to generate many operations that can
   cause reference changes. There should also be other GC event-aware policies.
6. **Parallel GC**: Consider modeling parallelisms targeting multiple GC threads. For example, adding operations like:

```shell
# Example GIR extension for thread awareness
.thread-context main, worker1, worker2
on-thread worker1: add-ref $1, $2  # Cross-thread reference
on-thread worker2: remove-ref $3, $1  # Concurrent modification
sync-point all-threads  # Synchronization barrier

.allocation-burst 100ms, 50MB  # Burst allocation to trigger TLAB pressure
.allocation-pause 200ms        # Pause to let concurrent GC catch up
```

7. **Stress patterns**: Consider patterns that push specific GC limits
8. **Realistic workload patterns**: Consider patterns based on common application behaviors

## Limitations and Future Work

For now, our operations are all based on strongly reachable objects. Soft/weak references are only added to them. This
treatment is due to the implementation challenges incurred by them. Let's take Java as an example:

Suppose our initial state is:

```
Objects:
 a (root,30m)
 b (nonroot,25k)

References:
 a: a -> b (soft)
```

Or simply:

```
a --soft--> b
```

We're applying the following two operations

```shell
c <- add-nonroot-obj b, strong // add a non-root object c which is connected strongly from b
r <- add-strong-ref a, c // add a strong reference between object a and object c
```

After executing these two operations, it is apparently that our oracle graph should be:

```
a --soft--> b
b --strong--> c
a --strong--> c
```

This oracle state asserts that objects a and c are strongly reachable, while object b is softly reachable.

The generated Java program might look like (where `?.` is the safe navigation operator like that in TypeScript):

```typescript
A a = new A();
a.soft_to_b = new SoftReference<B>(b);
a.soft_to_b.get()?.strong_to_c = new C();
a.strong_to_c = a.soft_to_b.get()?.strong_to_c;
```

Since `b` is softly reachable at the initial state, it is likely that the execution of this program can have multiple
different yet correct actual state:

1. **Object b exists all the time**: In this execution, the two operations will succeed and our actual state exactly
   matches our oracle state.
2. **Object b does not exist any more after the initial state**: In this execution, the first operation will fail and
   object c won't be created successfully. This leads to the second operation failing as well since c does not exist.
   The our actual state becomes: `a`. Neither b nor c exists.
3. **Object b exists after the initial state but got collected after executing the first operation**: In this execution,
   c will be successfully created after the first operation. However, it is immediately collected together with b since
   it is softly reachable. Thus, the second operation will fail and our actual state is: `a`.
4. **Object b exists after the initial state but got collected after executing the second operation**: This execution is
   similar to the first execution, but our actual state is: `a --strong--> c`.

In fact, all the four actual states are correct but mismatch our oracle state. This is a challenge.

A future work may consider modeling state divergence in the implementation of the Oracle Interpreter so that each GIR
program can lead to multiple different oracle state. These states form a state divergence tree. However, this may cause
exponentially-increased oracle states, a new challenge to be addressed.
