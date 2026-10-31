# Queue ownership capture investigation

Status: bounded feasibility study for initially-null registered soft/weak wrappers and explicit enqueue/poll. This proposes an implementation boundary; it certifies no production feature. The [reference design](2026-10-30-stage15-reference-semantics.md#queues-and-explicit-enqueue) retains automatic pending processing as a separate problem.

## Actual ownership paths

The [current observer](../../../native/src/agent.cpp) continues traversal through foreign objects but omits records unless a workload object, published array or declared root participates. Reaching a wrapper therefore does not prove the path from its queue. Registration points from wrapper to queue and is not such a path; the inspected OpenJ9 clears that field during insertion.

| Inspected implementation | Entry and ownership evidence |
| --- | --- |
| [HotSpot JDK 21](https://github.com/openjdk/jdk21u-dev/blob/db16554afc2bf284697f4f9f10672b37f0e464a6/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java) | Actual queue `head`, wrapper `next` chain with self-tail termination, count and actual `ENQUEUED` sentinel identity. These are ordinary strong links, distinct from the special referent field. |
| [OpenJ9 JDK 21](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/jcl/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java) | Actual queue-to-`Reference[]` edge, array class/length, `head`/`tail`/`empty` and every active ring slot. Unused slots, helper objects and membership remain distinct. |

Private names and representation invariants belong in a qualified VM decoder. Portable semantics contain queue identity, an unordered entry set and strong ownership, without requiring linked lists, arrays or either delivery order. Unknown classes, ambiguous field coordinates or unsupported layouts prevent certification.

## Small capture boundary

Add one explicit queue-resource/helper capture policy beside workload shapes, then use a small decoder for each qualified representation. Copy actual class/field identity, descriptors and JVMTI indices before capture. A dedicated role must not turn every queue or backing array into an ordinary GIR allocation. Validate target/library identity and layout plus deterministic behavior before enabling an adapter; a VM name or user profile is insufficient.

Main is parked during capture; initially-null registration and no external producer/consumer keep the selected queues stable. Use the same heap walk for graph and queue evidence. Record actual declared static queue roots, their physical tags, queue state fields, relevant static sentinel edges and all outgoing helper-array entries, including unknown target types. Existing wrapper recording supplies ordinary links; an unknown member must be visible and fail certification rather than disappear. The callback policy must retain helper object/class/length metadata and outgoing entries independently of which incoming path first visits them. Capturing every admitted helper-class instance is a simple bounded first approach; restricting it to dynamically discovered instances needs a separate traversal-order proof.

The [JVMTI contract](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#FollowReferences) reports non-null fields and array elements and holds heap state stable during traversal. Inferring null from an absent record additionally needs an observed owner, qualified complete recording policy, successful traversal and no exhausted bound. The existing `COMPLETE` snapshot flag alone is insufficient: its selective policy can omit a non-null entry.

Map queue IDs from actual static-root fields and physical identities, then decode membership without expected member IDs. Runtime constructor/API records can identify their actual queue argument through comparisons with these deliberately retained resources; no wrapper lookup or strong identity registry is needed. Native metadata/tags retain no workload objects; release temporary class references before walking. Keep preallocated callback storage and perform encoding, JNI and metadata queries outside callbacks.

Retain bounded resource metadata/path records in an explicitly selected version 1 sidecar linked to the same run/capture and raw physical tags; existing snapshot enums need not acquire guessed helper kinds. The decoder must distinguish missing evidence from a physically empty queue, retain actual paths and reject incomplete status. Queue ownership enters the full oracle state before strength-specific reachability; partial comparison must not remove that protection. Missing ownership coverage cannot pass, while independent graph refutations remain usable. After poll, lingering physical retention is permitted and must not be mistaken for continued membership.

## Bounded evidence and remaining gates

Two interpreted runs per selected JVM compare the unmodified observer with a diagnostic-only three-policy extension. Both register `ReferenceQueue` as an ordinary object for the probe, which production does not do. The extension admits helper `Reference[]`, records all its outgoing targets and retains registered-class static sentinel edges. This reuses ordinary roles, and helper metadata can still depend on a later retained incoming queue edge; it proves neither production registration nor helper-first metadata coverage.

Each run captures two initially-null wrappers on one queue and one on another, consumes one entry, then inserts an unknown plain `WeakReference`. An independent binary decoder recovers HotSpot entries `[2,1]` and OpenJ9 `[1,2]`, followed by the respective remaining member. In OpenJ9 the baseline omits the unknown occupied slot despite `COMPLETE`; the extension records its actual tag and helper-array class. HotSpot sentinel edges appear only with the extension. Each extended run also returns `RECORD_LIMIT` with one retained record when capacity is one. No wrapper is read through JNI or retained in an observer registry.

The probe makes no explicit GC request. Each OpenJ9 run's first capture brackets one GC event; subsequent captures and all tested HotSpot intervals report no GC during their intervals. This establishes neither observer transparency nor compiled coverage. Java 17 compilation and both native builds use warnings as errors. Exact source revisions, input/probe/library/snapshot hashes, commands, GC counters and decoder output remain in ignored local evidence; manifest SHA-256 is `cbe7aef53f23ff7372abedfef1b4a15da228fbfc6eb5a0d1748a65676ce7770b`.

Production still needs the dedicated policy/envelope, actual queue identity/recording integration, runtime class/field qualification, empty/full/wrapped/grown rings, linked termination/sentinel adversaries, malformed/omitted records and resource-failure tests, plus independent oracle composition and run/verify/replay. No automatic pending model, registered non-null construction, concurrency, natural capture or frame support is established by this study.
