# First real-call slice

Status: accepted by the user and implemented as the bounded void-call slice. [GIR](../../../docs/gir.md), [Instruction.Call](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) and [MethodProgram](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/MethodProgram.java) own the implemented contracts; the [implementation note](../implementations/2026-11-02-call-methods.md) records review and validation limits. The broader [stage 14 exploration](./2026-10-30-stage14-execution-semantics.md) remains deferred. GIR and artifact formats remain version 1.

## Useful workload and syntax

A caller keeps an unpublished object while a helper allocates another object, requests GC, mutates a passed alias and publishes its local through that alias. After return, the caller links its private object into a named graph for verification. This exercises caller, parameter and callee-local retention across real Java calls without requiring interior frame capture.

Approved workload sketch, omitting the `gir 1;` header:

```text
shape N object 1 1;
root saved = null;
method mutate(ref N node, long value) {
  allocate local N;
  collect;
  write-data node 0 value;
  write-ref node 0 local;
}
program {
  allocate callerOnly N;
  allocate passed N;
  call mutate(passed, 7);
  write-ref callerOnly 0 passed;
  write-root saved callerOnly;
  checkpoint;
}
```

Methods are void by construction, statically named, nonoverloaded and nonrecursive. Parameters use existing nominal reference shapes or long values; null is allowed. No results, return statement, arithmetic, new value types, exception syntax or caller-local capture. Helpers may access named roots. Fallthrough ends the helper.

## Source and execution boundaries

Add method definitions before `program`. Collect signatures first, then validate every body and call's arity/types/shapes, including unused definitions and skipped/zero-repeat paths. `GirProgram.instructions()` visits each definition once, then the program, in source preorder; it never follows calls. A separate call graph checks targets/cycles and derives bounds. Preserve globally unique labels and automatic instruction IDs. Assign parameter declaration IDs after all instruction IDs, in method/parameter order, with checked integer bounds; source supplies no IDs.

Each call validates arguments and entry capacity before frame/marker/counter effects, then copies arguments once into the callee's outer scope alongside top-level declarations. Lookup stops at that frame; names may repeat between methods. Release requires the current scope: top-level parameter release is legal, child-scope release is not. Suspended callers retain all live handles. Heap/root writes persist after return. `GraphMachine.graph()` and retirement account for every active frame; allocation occurrences continue per site.

Keep declaration-keyed handles: a single-thread acyclic call graph cannot have two active instances of one definition. No activation-key or multi-frame wire overhaul is needed. Bound combined lexical/call nesting with the existing nesting cap and enforce Java parameter-slot, method/code/local and provider ceilings before execution.

## Lowering and evidence

Emit one direct static Java helper per definition, passing session and primitive call-site ID alongside typed arguments. No interpreter or workload-reference frame registry. Callee `CALL_ENTER` and normal `CALL_EXIT` are zero-valued call-site records, each costing one step, including empty calls; append kinds without renumbering. Compare complete exact traces, including branches and nested calls, against independent interpretation and require an empty call stack at captures/completion. An operations-only filter cannot validate missing branches/empty calls.

Capacity planning expands call quotas separately from syntax traversal, includes both branch arms, and multiplies literal repeats using saturated bounds before allocating storage. Definitions alone consume no execution quota. Preserve existing collection site/thread/ordinal certificates; a call marker proves neither GC completion nor a compiled/noninlined frame.

Controlled Java `finally` fences/clears caller/helper reference locals and parameters on every exit; predeclare nullable locals to cover failures before later releases. Model cleanup always pops callee scopes/parameters and restores the caller without spending budget. After normal body cleanup, check/charge `CALL_EXIT`. Exhaustion preserves completed effects and the valid prefix, reports a checking limit and emits no exit; no whole-call rollback. Recorder/capture failures retain the existing incomplete-failure protocol and cannot complete success.

## Admission and acceptance

Initially admit sequential strong/plain graph execution under controlled observation with `retained-class` metadata only. Reject workers, reference-processing/wrapper/queue mechanisms, native roots, natural observation and helper checkpoints across declarations and every body, including unused methods. Other unsupported composites retain their guards. Permit checkpoints only in the main body after calls return.

Keep existing `BindingEvidence` and execute/main capture roles; no active helper needs a manifest. Reject helper activations in capture/terminal frame samples using certified helper name/descriptor tokens. The `jvmti` path lacks those certificates and remains unsupported for methods. Preserve loaded-byte provenance, terminal status and missing-root inconclusiveness. Method-free source, canonical formatting, generated output and old bundles retain their contracts.

Acceptance requires independent nested/diamond/repeated-call and alias/null/release models; suspended-caller retention; rejected-call state snapshots; empty-call/branch corruption; bounds and failure injection; warning-free Java 17 lowering with actual `invokestatic`; JDK 17/21 run/verify/exact-class replay with GC certificates; provider/terminal adversaries and old-output/bundle regressions. A standalone release-17 probe passed strict compilation and interpreted Serial GC on JDK 17/21, preserving caller-only, parameter and helper-local values through one reported collection; `javap` confirmed direct calls. It does not establish Gorrect integration or optimized frame coverage.

Author: GPT 6.1 Sol <codex@openai.com>.
