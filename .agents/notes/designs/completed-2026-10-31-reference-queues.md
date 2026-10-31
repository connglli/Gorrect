# Explicit reference queue design decisions

Completed design for the initially-null registered slice. [GIR](../../../docs/gir.md#reference-histories), [instruction contracts](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) and [observation](../../../docs/observation.md#tagged-reference-targets) own implemented behavior; the [implementation record](../implementations/completed-2026-10-31-stage15b.md) records validation.

One nominal wrapper type per queue prevents destructive polling before a type error is detected. Registration belongs to construction because Java provides no later registration API. Existing handles, roots, fields and receiver operations cover wrapper movement without a second object language.

Initially-null registration excludes automatic notification and pending ownership. This matters when weak A targets registered B: after GC processes B's referent and B's handle ends, A's clearing may depend on pending ownership. Neither registration nor a primitive pending bit proves a portable strong root. Legacy wrapper exposure also needs an observed nominal type; matching strength alone cannot supply one.

API histories and physical ownership are independent evidence. HotSpot's linked entries and OpenJ9's backing array require qualified decoding; API success alone cannot establish actual membership. Capture must preserve these paths without retaining a wrapper registry or looking up expected members. The initially-null sequential slice keeps selected queues outside handler mutation at checkpoints. Automatic notification needs a separate consistency proof.

The design's independent finite model checked queue transitions and ownership graphs, including the pending-root counterexample. A Java 17-compatible probe exercised success, duplicate/consumed insertion, unordered delivery and false no-queue enqueue clearing on the selected VMs. OpenJ9's disableClearBeforeEnqueue negative control showed why effective library behavior requires qualification. These probes establish neither automatic processing nor observer completeness; implementation checks are recorded separately.
