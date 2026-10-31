# Strong sequential array cloning

Status: complete for the first stage 18 slice, with independent semantic/capture review and coordinator integration. [GIR](../../../docs/gir.md#instructions) owns syntax/support and [Instruction.Clone](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) owns operation semantics. Runtime/protocol/native formats remain unchanged.

The model derives the same fixed array shape and shallow contents under a fresh allocation occurrence, with ordinary object/slot budgets checked before effects. Generated Java calls the actual array clone and assigns its result a fresh existing weak-tag binding. Reference targets remain shared; array storage and later writes remain independent. Direct concurrent/history boundaries reject unsupported compositions before evidence-completeness exits; exact scalar-branch recorder planning reserves clone quotas and declarations in both arms.

Selected OpenJ9 exposed an existing empty-long-array gap: physical [J owners of length zero lack a values callback. Projection accepts empty contents from that observed type/length only, without manufacturing a callback. Nonempty missing markers, missing owners, wrong types/lengths, contradictory markers/elements and incomplete walks remain inconclusive. The original failing raw bundle is preserved locally; the correction composes independently with queue ownership.

The final combined build on the generational-ZGC parent passes 667 Java cases, native tests and JDK 17/21 runtime checks. Two independent reviewers confirm semantic/checker guards, real clone bytecode and physical evidence, including 146 literal semantic cases. Focused validation includes 30 clone/projection cases, six HotSpot target cases and one selected OpenJ9 case, with strict compiler/Javadoc checks. Generated Java 17/21 cases verify actual clone bytecode, physical source/clone identities, shallow targets, independent writes, repeated/empty arrays, conditional capacity, live bindings with both metadata providers, invalid/budget transactionality, corruption and retained refutations.

Ten fixed-distribution commands pass: selected HotSpot plain and compile-request runs with offline verification/replay, selected OpenJ9 run/verify/replay, and re-verification of a copy of the original failing empty-array bundle. The original bundle remains unchanged. Compile-request flags establish no intrinsic or continuous-compiled proof. Source/artifact identities and complete raw evidence remain in ignored local verification state for independent review.

| Review boundary | Result |
| --- | --- |
| Scalar-branch recorder planning must include clone allocations and bindings from both arms. | Reused lexical shape inference supplies conservative quotas without predicting execution. |
| OpenJ9 omitted empty-array values callbacks. | Actual owner/type/length establishes emptiness; nonempty, contradictory and missing-owner cases remain inconclusive. |
| A bulk read could otherwise receive an empty scheduler footprint. | Shared source guards and direct backend checks reject unsupported combinations before evidence exits. |

Object cloning, modeled exceptions, reference-copy cohorts, parallel/contended histories and natural array capture remain gated. Local profiles, commands, binaries and raw evidence stay ignored and contain no committed machine paths.
