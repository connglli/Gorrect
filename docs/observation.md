# Heap observation

The observer projects physical evidence into [GIR](./gir.md). Layouts describe roles, never expected values. [Queues](../protocol/src/main/java/io/connglli/gorrect/protocol/QueueCapture.java) and [native slots](../protocol/src/main/java/io/connglli/gorrect/protocol/NativeRootEvidence.java) require same-walk ownership; automatic queues also need [verified mutation locks](../protocol/src/main/java/io/connglli/gorrect/protocol/QueueConsistency.java).

## Capture contract

Register classes before workload construction. Supported layouts are direct `Object` subclasses without interfaces, `Object[]`, `long[]`, static root holders and generated direct soft/weak subclasses. Names are ASCII; JVMTI supplies actual field indices, modifiers and signatures. Unknown layouts fail explicitly.

Heap walks request no GC. Automatic capture holds each declared queue's mutation lock; acquisition may allocate, permit GC or delay delivery. Callbacks use preallocated buffers without JNI, allocation or locks; metadata retains no strong workload references. Conflicting records, missing origins/values, unknown targets, duplicate identities or unvisited root classes prevent complete projection. Missing edges mean null only with valid metadata and complete recording; unknown queue components prove neither null nor emptiness. An observed zero-length `[J` proves empty contents without a values callback. Limits, JVMTI errors or failed lock cleanup cannot pass.

## Identity

Ordinary objects carry primitive logical worker/site/occurrence fields; arrays use diagnostic weak-tag bindings. A physical tag identifies one observed allocation within that JVM, not an address. Movement/materialization preserves logical identity, and distinct allocations cannot share it. Array clones receive fresh bindings; copied metadata cannot create identity.

Class IDs describe registered definitions. Same-named classes from distinct loaders and object cloning are unsupported. Reference wrappers use [incarnation identities](../engine/src/main/java/io/connglli/gorrect/engine/model/WrapperId.java); the observer reads inherited referents without `get()`. First-class wrappers are canonical typed vertices, while legacy slot wrappers remain opaque. No wrapper registry retains referents.

## Coverage and observer effects

All profiles capture declared roots at quiescent checkpoints:

| Profile | Local obligations | Lifetime fences |
| --- | --- | --- |
| `named-roots-diagnostic` | None; release handles before capture. | Yes |
| `roots-diagnostic` | Every live caller/participating-worker reference binding. | Yes |
| `roots-natural` | Main bindings with future uses before release/scope exit. | No |

Natural analysis follows validated exact branches and bounded loops, distinguishing definitions and actual visits. It excludes reference/contended histories, worker cuts and diagnostically bound arrays. Controlled capture supports those histories and matched worker cuts under [GIR restrictions](./gir.md#parallel-workers).

A required non-null local needs a unique actual root match by thread, activation, method, depth, slot and bytecode scope. Null requires primitive presence without contradictory roots. Missing origins/debug scopes make local coverage incomplete; they are never guessed from expected targets. Covered graph/scalar contradictions remain usable beside such gaps.

Heap walking can materialize objects or deoptimize frames. Array binding/identity reads pass arrays through JNI and can affect escape and timing. Local access, stack inspection and recording also perturb execution. Natural lifetimes do not promise transparent allocation or unchanged compilation. Installation events and pause counters alone prove neither compiled invocation nor cycle completion; [stress](./gir.md#stress-requirements) and [collection](./usage.md#profiles) requirements need independent evidence.

## Binding metadata

The profile selects `jvmti` (default) or `retained-class`; plan/layout hashes and evidence must agree. Retained (`roots-diagnostic`) and [queue capture](../engine/src/main/java/io/connglli/gorrect/engine/run/QueueResources.java) require qualified Linux amd64 HotSpot 17/21 or OpenJ9 21 (`6e16eb1a4f`, gencon). Queues check [bootstrap bytes/APIs](../runtime/src/main/java/io/connglli/gorrect/runtime/QueueQualification.java); [automatic delivery](../engine/src/main/java/io/connglli/gorrect/engine/run/AutomaticQueues.java) requires the exact HotSpot 17 Serial interpreted image. Admission verifies retained image/support identities, effective command/environment and disabled attachment; offline checking needs no installation. OpenJ9 shared-cache/AOT reuse is disabled. External WhiteBox clients, natural capture, custom loaders and frame reconstruction are unsupported.

Before construction, a nontransforming hook binds the intended class/loader/methods through weak mirrors; the host compares copied bytes with retained `Case.class`. Controlled launch must exclude later transformation. Retained metadata enables no local-access capability and cannot replace missing physical roots.

Stack samples bracket every participant, even empty ones. Caller sites use `execute`, workers their declared methods, and final capture uses `main` after workload frames/bindings end. Matching requires the same thread/method/depth and valid equal BCI before/after; actual roots still supply method/depth/slot. Only missing BCI may use the certified sample. Final code status must confirm the startup epoch. Gaps or inconsistent OpenJ9 origins/depths stay inconclusive while earlier refutations remain visible. [CodeEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/CodeEvidence.java) and [FrameEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/FrameEvidence.java) define the certificates.

## Snapshot format

Version 1 snapshots preserve raw VM evidence and run/checkpoint identity. `COMPLETE` means the bounded walk finished, not that every GIR binding was observed. The big-endian [SnapshotCodec](../protocol/src/main/java/io/connglli/gorrect/protocol/SnapshotCodec.java), [record contract](../protocol/src/main/java/io/connglli/gorrect/protocol/HeapSnapshot.java) and [native writer](../native/src/snapshot_wire.hpp) define contents/encoding; a [shared fixture](../protocol/src/test/resources/snapshot-v1.hex) checks compatibility. Truncation, trailing data, version/identity mismatch and conflicting records are extraction errors.

## Binding evidence

[BindingEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/BindingEvidence.java) stores primitive presence for main captures. The layout selects required bindings or all lexical candidates; natural capture verifies complete candidate/nullness evidence before selecting physical obligations from the validated path. Unknown selection rules or incomplete correspondence cannot supply inferred nulls or a pass.

Worker cuts use [CaptureEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/CaptureEvidence.java), with the caller first and every worker included even without bindings. Run/capture, site/visit, group/round, phase, thread, activation and declaration identities must agree with layout and executed traces. A missing worker envelope cannot fall back to main-frame evidence; unexpected or contradictory envelopes are extraction errors. The codecs own version 1 framing and bounds.

## Tagged reference targets

First-class programs explicitly select `references.gtyped`; legacy object-only histories use `references.ghistory`. Missing typed evidence has no legacy fallback. [ReferenceHistory](../protocol/src/main/java/io/connglli/gorrect/protocol/ReferenceHistory.java) defines typed ordinary/wrapper identities and scalar-position linkage. Generated wrappers add only primitive identity/registration fields, never strong construction-target fields.
