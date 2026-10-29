# Implementation architecture rationale

The component contracts, language baselines, and execution flow live in [docs/implementation.md](../../../docs/implementation.md). This note retains the reasoning and prototype questions behind them. Candidate details below do not override that document.

## Keep the target small

The engine needs rich GIR and verification structures, while the target needs predictable helpers that leave generated mutator code visible to the JIT. Keeping those dependencies apart reduces opportunities for a harness interpreter, object registry, or recorder to dominate the workload.

The target compatibility boundary is enforced through compilation and actual launches on the lowest supported JDK. An engine build that happens to succeed does not demonstrate target compatibility.

## Observe without replacing the workload

A native observer gives access to actual heap relationships without requiring an application-level traversal that repeatedly acquires soft or weak referents. The prototype must still establish that capture, tagging, and requested capabilities preserve the behavior being examined.

Prefer associating logical identities with native snapshot identities at observation points. A JNI call on every allocation can force escape and materialization, so birth-time tagging is a candidate diagnostic mode that needs separate measurement. Validate ordinary local roots and optimizer-eliminated objects without requiring every logical allocation to appear as a physical heap object.

The observer receives a shape description rather than expected reference values. Compare that description with emitted fields and retain unexpected records. A decoder that silently drops a field could hide the same translation error as the emitter.

## Protocol and solver candidates

A local socket is a candidate for the engine/runtime control connection. Choose framing and encoding with the runtime prototype. Control messages are small, while graph captures and traces can be large; do not make the control path carry every hot mutation.

The [Z3 process interface](https://microsoft.github.io/z3guide/docs/logic/intro/) is the candidate solver integration. Validate small supported reference and concurrency histories against an independent exhaustive model before relying on the symbolic encoding. The encoding must distinguish an inconsistent execution from a query whose bound or semantics cannot establish the result.

Treat trace loss and incomplete evidence as explicit verification limitations. Logging timestamps do not establish the ordering of unsynchronized operations, and a compilation notification alone does not demonstrate execution of the compiled code.

## Prototype questions

* Does the complete path work for a small GIR case with two mutators, hot methods, local roots, and a checkable graph observation?
* Can the observer distinguish intended roots from references retained by worker launch arguments, telemetry, or JNI helpers?
* Do the common layout and protocol contracts work on OpenJ9 as well as HotSpot?
* Which collector and compiler requirements can each target establish from its controls and evidence?
* Which synchronization and reference-processing histories can the checker support soundly within a bounded run?

## Implementation order

Build the complete execution and verification path before expanding the random generator. Exercise generated Java and corrupted observations, not only the engine's internal model. Bring up OpenJ9 observation early to expose assumptions in the common observer, while keeping HotSpot collector support the primary integration work.
