# Heap observation

The observer captures physical heap evidence. The engine projects that evidence into the logical objects described by [GIR](./gir.md), then applies the oracle. Layout descriptions supply class and field names, shape dimensions, and identity-field roles; they supply no expected targets or payload values.

## Capture contract

Register classes before constructing the workload. The prototype supports ordinary classes extending `Object` directly with no interfaces, `Object[]`, `long[]`, and static root holders. Metadata names must be ASCII. Unsupported layouts fail explicitly. Instance and static field indices come from JVMTI metadata, including modifiers and signatures; object-header offsets are never inferred.

Ordinary objects carry immutable `int` worker/site and `long` occurrence fields separately from their payload. Heap callbacks read these values at observation time. Arrays use explicit diagnostic identity bindings through weak JVMTI tags. Binding an array through JNI can force escape or materialization, so this mode is appropriate for published arrays and experiments that permit that effect.

Capture walks references from VM roots without requesting a collection. Callbacks retain relevant object records, incoming references, outgoing fields/elements, primitive values, and root origins in preallocated native storage. No JNI calls, locks, or allocation occur in heap callbacks. Native metadata retains no workload object references. Physical tags remain stable across captures; all raw records are preserved, including repeated evidence and unknown origins.

Only identical repeated primitive/element evidence can be combined during projection. Conflicting records, missing primitive values, unknown fields or non-null targets, duplicate logical identities, and unvisited root classes make projection unavailable. Null reference slots are inferred from absent edges only after a complete walk and validated field metadata. A record limit or JVMTI error produces a partial capture that cannot pass verification.

## Coverage and observer effects

Logical projection currently covers named static roots. Stack and JNI root records retain thread, method, depth, location, and slot information where the VM supplies it. They are not yet mapped to GIR binding identities. Checks requiring live GIR handles return inconclusive through the projection API. A VM may omit an origin for a compiled local even while the object remains accessible; that absence cannot establish a liveness violation.

Heap walking freezes the mutator heap view and can affect optimization. HotSpot's heap-walk implementation materializes eliminated objects through an escape barrier. A physical snapshot therefore cannot establish that the walk preserved compiled frames or that every logical allocation needed a physical object beforehand. The fixtures measure temporary-object allocation with no agent, with tagging enabled, and after capture. They also exercise an escaped object held in a local across collection and capture.

Compilation events establish code installation, not execution of a particular invocation. GC event counters describe reported pauses, not completion of every concurrent collection phase. These limits remain separate from a graph verdict. See the [JVMTI specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#FollowReferences) for traversal and callback rules.

## Snapshot format

`SnapshotCodec` and the native writer share a versioned big-endian format. Integers are 32 bits and record cells are 64 bits. Strings have a 32-bit byte length followed by UTF-8 bytes. Reader limits bound bytes, record counts, metadata counts, and string lengths.

The envelope contains, in order:

1. Magic `0x47525331`, version, positive run and checkpoint tokens, status, JVMTI error, capability flags, and GC start/finish counters before and after the walk.
2. Class metadata: class ID, kind, signature, and fields with actual JVMTI index, modifiers, name, and descriptor.
3. Diagnostic array identities: physical tag, logical shape name, worker, site, and occurrence.
4. Method descriptions keyed by opaque method tokens.
5. Raw records, each containing a kind followed by seven cells `a` through `g`.
6. Footer `0x454e44534e415031`. Truncation, trailing data, incompatible versions, and mismatched run/checkpoint tokens are extraction errors.

Class kinds are object, reference array, data array, and root holder, numbered from zero. Ordinary physical object tags are positive; a registered class object uses the negative of its class ID. Zero is reserved. Method tokens and tags belong to their target JVM, not another run.

| Record kind | Cells |
| --- | --- |
| `OBJECT` (0) | `a` tag, `b` class ID (zero if unregistered), `c` size in bytes, `d` length or -1. |
| `REFERENCE` (1) | `a` referrer tag, `b` target tag, `c` JVMTI reference kind, `d` field/element index or -1, `e` referrer class tag. |
| `PRIMITIVE_FIELD` (2) | `a` owner tag, `b` field index, `c` JVMTI primitive type, `d` value bits, `e` owner class tag. |
| `ROOT` (3) | `a` target tag, `b` root kind, `c` thread ID, `d` frame depth, `e` slot, `f` method token, `g` location. Unavailable frame values use zero or -1. |
| `ARRAY_VALUES` (4) | `a` tag, `b` count, `c` primitive element type. Records callback delivery even for an empty array. |
| `ARRAY_ELEMENT` (5) | `a` tag, `b` index, `c` primitive type, `d` value, `e` class tag. The prototype records long elements. |
| `CLASS_VISIT` (6) | `a` registered class ID. Establishes that the class was visited before null static roots can be inferred. |

Unlisted cells are zero. Reference and root kind values and primitive type codes are JVMTI values. Capability bits are tagging (1), GC events (2), compilation events (4), and compilation-evidence overflow (8). Status values are complete (0), record limit (1), and JVMTI error (2); complete describes traversal, not full GIR binding coverage.

The shared [golden fixture](../protocol/src/test/resources/snapshot-v1.hex) checks Java/C++ byte compatibility. Integration checks preserve native bytes and compare decoded graphs against independently specified fixtures, including deliberately damaged evidence.
