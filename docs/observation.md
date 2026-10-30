# Heap observation

The observer captures physical heap evidence for projection into [GIR](./gir.md) objects. Layout descriptions supply class/field names, shapes, and identity roles, with no expected targets or payloads.

## Capture contract

Register classes before constructing the workload. Supported layouts are direct `Object` subclasses without interfaces, `Object[]`, `long[]`, static root holders, and generated direct `SoftReference`/`WeakReference` subclasses. Names must be ASCII. JVMTI metadata supplies field indices, including inheritance, modifiers, and signatures; unsupported layouts fail explicitly.

Capture walks VM roots without requesting GC. Callbacks record relevant objects, incoming/outgoing edges, primitives, and root origins in preallocated storage, without JNI, locks, or allocation. Native metadata holds no workload references. Tags remain stable across captures; raw records preserve duplicates and unknown origins.

Projection combines only identical repeated primitive/element records. Conflicts, missing primitives, unknown fields or non-null targets, duplicate identities, and unvisited root classes make it unavailable. Absent edges establish null only after a complete walk with valid metadata. Record limits and JVMTI errors produce partial captures that cannot pass.

## Identity

Logical [object identity](./gir.md#identities-and-access) identifies one modeled allocation throughout its lifetime. Ordinary objects carry immutable `int` worker/site and `long` occurrence fields read during capture. Arrays use diagnostic bindings through weak JVMTI tags. History identity reads retrieve the published worker/site/occurrence cells through JNI without a Java registry or result allocation. A physical tag identifies an observed allocation within one target VM; it is neither an address nor a portable identity. Movement or materialization does not create a new logical allocation, and distinct observed allocations cannot share a logical identity. Copied diagnostic fields cannot establish the identity of a clone; clone is unsupported.

Captured class IDs describe registered classes; signatures select their layout mappings. The current registry permits one definition per signature, so same-named classes from distinct loaders are unsupported. Class identity, object identity, and identity hashes are separate; hash collisions would not establish aliases.

Wrappers carry primitive [incarnation fields](../engine/src/main/java/io/connglli/gorrect/engine/model/WrapperId.java). JVMTI reads inherited `Reference.referent` without `get()` or a strong root. Projection maps wrapper edges to strength, incarnation, and current referent. Queue links stay raw evidence; there is no separate wrapper registry.

## Coverage and observer effects

All profiles capture named static roots at quiescent checkpoints. Additional binding obligations depend on the profile:

| Profile | Main-frame binding obligations | Lifetime fences |
| --- | --- | --- |
| `named-roots-diagnostic` | Unsupported; release handles before capture. | Yes. |
| `roots-diagnostic` | Every live reference binding. | Yes. |
| `roots-natural` | Active bindings with future uses before release or scope exit. | No. |

Lifetime fences enforce [GIR minimum lifetimes](./gir.md#instructions). Natural analysis follows bounded literal control flow and distinguishes successive loop activations; unused lexical locals carry no heap-presence obligation. Natural mode excludes reference/contended histories, conditional control, and arrays requiring diagnostic identity binding. Diagnostic array binding and identity reads pass arrays through JNI and can affect escape, materialization, and timing. The observer keeps no hidden strong registry of workload objects.

Controlled binding capture supports reference/contended histories and conditional main-thread checkpoints. Coverage uses the lexical declaration IDs for the actual checkpoint operation, including acquired bindings. Skipped bodies do not shift a capture onto another site's declarations. History-plan graph values are placeholders; target evidence supplies binding presence and targets.

Binding capture records primitive presence and matches emitted method/local metadata, including acquired bindings, against actual JVMTI method, slot, bytecode scope, thread, and activation evidence. Every non-null binding needs a unique physical root mapping; null needs an explicit presence witness without a contradictory root. Missing declarations, root origins, or debug metadata make projection inconclusive. Worker data/alias uses have independent scalar traces; heap checkpoints remain subject to the [worker restrictions](./gir.md#parallel-workers).

Heap walking freezes the mutator heap view and can affect optimization. HotSpot may materialize eliminated objects through an escape barrier. Binding profiles also enable JVMTI local-variable metadata access. Natural mode describes semantic-use lifetimes, without promising transparent allocation or unchanged compiled frames. Calibration compares no-agent, tagging, primitive-witness, and capture modes; retention fixtures check released nodes and arrays with and without local-variable access.

Compilation events measure installation; GC counters measure pauses. Compiled invocation needs frame evidence; OpenJ9 witnesses inspect the current stack through a JVMTI extension, copying method metadata without workload references. Stack inspection and recording affect timing and may affect optimization. [Cycle completion](./usage.md#profiles) needs VM GC events matched to requests. The [JVMTI specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#FollowReferences) defines traversal and callback rules.

## Snapshot format

`SnapshotCodec` and the native writer share a versioned big-endian format. Integers are 32 bits and record cells are 64 bits. Strings have a 32-bit byte length followed by UTF-8 bytes. Reader limits bound bytes, record counts, metadata counts, and string lengths.

The envelope contains, in order:

1. Magic `0x47525331`, version, positive run and checkpoint tokens, status, JVMTI error, capability flags, and GC start/finish counters before and after the walk.
2. Class metadata: class ID, kind, signature, and fields with actual JVMTI index, modifiers, name, and descriptor.
3. Diagnostic array identities: physical tag, logical shape name, worker, site, and occurrence.
4. Method descriptions keyed by opaque method tokens.
5. Raw records, each containing a kind followed by seven cells `a` through `g`.
6. Footer `0x454e44534e415031`. Truncation, trailing data, incompatible versions, and mismatched run/checkpoint tokens are extraction errors.

Capability `LOCAL_VARIABLES` (32) adds a 32-bit local count after each method description, followed by entries containing 64-bit start location, 32-bit scope length and slot, and name/descriptor strings. Locations must use JVM bytecode indices. Captures without the bit retain the original version 1 method entries.

Class kinds are object, reference array, data array, root holder, soft wrapper, and weak wrapper, numbered from zero. Ordinary physical object tags are positive; a registered class object uses the negative of its class ID. Zero is reserved. Method tokens and tags belong to their target JVM, not another run.

| Record kind | Cells |
| --- | --- |
| `OBJECT` (0) | `a` tag, `b` class ID (zero if unregistered), `c` size in bytes, `d` length or -1. |
| `REFERENCE` (1) | `a` referrer tag, `b` target tag, `c` JVMTI reference kind, `d` field/element index or -1, `e` referrer class tag. |
| `PRIMITIVE_FIELD` (2) | `a` owner tag, `b` field index, `c` JVMTI primitive type, `d` value bits, `e` owner class tag. |
| `ROOT` (3) | `a` target tag, `b` root kind, `c` thread ID, `d` frame depth, `e` slot, `f` method token, `g` location. Unavailable frame values use zero or -1. |
| `ARRAY_VALUES` (4) | `a` tag, `b` count, `c` primitive element type. Records callback delivery even for an empty array. |
| `ARRAY_ELEMENT` (5) | `a` tag, `b` index, `c` primitive type, `d` value, `e` class tag. The prototype records long elements. |
| `CLASS_VISIT` (6) | `a` registered class ID. Establishes that the class was visited before null static roots can be inferred. |

Unlisted cells are zero. Reference and root kind values and primitive type codes are JVMTI values. Capability bits are tagging (1), GC events (2), compilation events (4), compilation-evidence overflow (8), non-strengthening reference-field capture (16), and local-variable metadata (32). Status values are complete (0), record limit (1), and JVMTI error (2); complete describes traversal, not full GIR binding coverage.

The shared [golden fixture](../protocol/src/test/resources/snapshot-v1.hex) checks Java/C++ byte compatibility. Integration checks preserve native bytes and compare decoded graphs against independently specified fixtures, including deliberately damaged evidence.

## Binding evidence

Binding profiles write `snapshots/<sequence>.gbind` before each capture. Its big-endian envelope contains 32-bit magic `0x47424e31` and version 1; 64-bit run, checkpoint, and thread IDs; 32-bit worker; 64-bit activation; 32-bit binding count; pairs of 32-bit declaration ID and presence (0/1); and footer `0x454e4442494e4431`. Run, checkpoint, thread, activation, and declaration IDs are positive; declarations are unique, and the count is bounded by [BindingEvidence](../protocol/src/main/java/io/connglli/gorrect/protocol/BindingEvidence.java). Capture context must match the snapshot and control transcript. Current main-frame capture uses worker 0 and activation 1; recursive frames and worker checkpoints are unsupported.
