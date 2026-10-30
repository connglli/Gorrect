# Heap observation

The observer captures physical heap evidence for projection into [GIR](./gir.md) objects. Layout descriptions supply class/field names, shapes, and identity roles, with no expected targets or payloads.

## Capture contract

Register classes before constructing the workload. Supported layouts are direct `Object` subclasses without interfaces, `Object[]`, `long[]`, static root holders, and generated direct `SoftReference`/`WeakReference` subclasses. Names must be ASCII. JVMTI metadata supplies field indices, including inheritance, modifiers, and signatures; unsupported layouts fail explicitly.

Ordinary identities come from immutable `int` worker/site and `long` occurrence fields read during capture. Arrays use diagnostic identity bindings through weak JVMTI tags. JNI binding can force escape or materialization; array tests must permit that effect.

Wrappers carry primitive [incarnation fields](../engine/src/main/java/io/connglli/gorrect/engine/model/WrapperId.java). JVMTI reads inherited `Reference.referent` without `get()` or a strong root. Projection maps wrapper edges to strength, incarnation, and current referent. Queue links stay raw evidence; there is no separate wrapper registry.

Capture walks VM roots without requesting GC. Callbacks record relevant objects, incoming/outgoing edges, primitives, and root origins in preallocated storage, without JNI, locks, or allocation. Native metadata holds no workload references. Tags remain stable across captures; raw records preserve duplicates and unknown origins.

Projection combines only identical repeated primitive/element records. Conflicts, missing primitives, unknown fields or non-null targets, duplicate identities, and unvisited root classes make it unavailable. Absent edges establish null only after a complete walk with valid metadata. Record limits and JVMTI errors produce partial captures that cannot pass.

## Coverage and observer effects

Logical projection covers named static roots. Stack/JNI records retain available thread, method, depth, location, and slot evidence, but lack GIR binding mappings; checks requiring those handles are inconclusive. Missing compiled-local origins cannot establish liveness violations.

Heap walking freezes the mutator heap view and can affect optimization. HotSpot may materialize eliminated objects through an escape barrier. A snapshot cannot establish unchanged compiled frames or physical allocation before the walk. Fixtures compare allocation with no agent, with tagging, and after capture, and retain escaped locals across GC/capture.

Compilation events measure installation; GC counters measure pauses. Compiled invocation needs frame evidence, and [cycle completion](./usage.md#profiles) needs VM GC events matched to requests. The [JVMTI specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#FollowReferences) defines traversal and callback rules.

## Snapshot format

`SnapshotCodec` and the native writer share a versioned big-endian format. Integers are 32 bits and record cells are 64 bits. Strings have a 32-bit byte length followed by UTF-8 bytes. Reader limits bound bytes, record counts, metadata counts, and string lengths.

The envelope contains, in order:

1. Magic `0x47525331`, version, positive run and checkpoint tokens, status, JVMTI error, capability flags, and GC start/finish counters before and after the walk.
2. Class metadata: class ID, kind, signature, and fields with actual JVMTI index, modifiers, name, and descriptor.
3. Diagnostic array identities: physical tag, logical shape name, worker, site, and occurrence.
4. Method descriptions keyed by opaque method tokens.
5. Raw records, each containing a kind followed by seven cells `a` through `g`.
6. Footer `0x454e44534e415031`. Truncation, trailing data, incompatible versions, and mismatched run/checkpoint tokens are extraction errors.

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

Unlisted cells are zero. Reference and root kind values and primitive type codes are JVMTI values. Capability bits are tagging (1), GC events (2), compilation events (4), compilation-evidence overflow (8), and non-strengthening reference-field capture (16). Status values are complete (0), record limit (1), and JVMTI error (2); complete describes traversal, not full GIR binding coverage.

The shared [golden fixture](../protocol/src/test/resources/snapshot-v1.hex) checks Java/C++ byte compatibility. Integration checks preserve native bytes and compare decoded graphs against independently specified fixtures, including deliberately damaged evidence.
