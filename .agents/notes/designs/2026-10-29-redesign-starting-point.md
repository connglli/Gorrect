# Redesign starting point

## Purpose

Keep the lessons from the legacy review available while we discuss a replacement. This note records constraints and questions; it does not select an architecture, implementation language, or build system.

## Agreed scope

* Start a fresh design on the `redesign` branch with a minimal scaffold.
* Name the host application and build unit `engine`; use `runner` for the component that launches and manages target JVMs.
* Use [docs/design.md](../../../docs/design.md) as the conceptual reference. Preserve the pre-commit configuration and dependencies and both installed hook stages.
* Generate object graph workloads, compute an independent oracle, instantiate them as Java programs, and compare runtime observations with the oracle.
* Prioritize HotSpot and its commonly used collectors, followed by OpenJ9. Make additional JVM integrations straightforward.
* Generate concurrent programs and programs that trigger JIT compilation as core GC stress workloads.
* Generated programs and target-side support must run on JDK 17 and newer. The engine JDK can be selected independently.
* Discuss the design before implementing the replacement.

## Lessons from the legacy implementation

The baseline reviewed was `e0fb973` on `main`. Its source is available through Git history.

* Reference identity was coupled to the source and target object pair. Target-named generated fields made retargeting unsafe and excluded multiple slots with the same target. Replace and swap generation were disabled.
* The checker mainly compared reachability and selected edge existence. Focused probes showed that it accepted changed reference strengths, unexpected edges, and a missing strong field from a surviving softly reachable object.
* The oracle, translator, runtime ID set, and heap parser maintained overlapping representations. Conditional operations used different notions of existence across those representations.
* The generator interpreted candidates before accepting them. Rejected candidates could leave model or memory-accounting changes behind.
* Collection progress was inferred from a sentinel weak reference. Runtime assertions and the subsequent heap dump could alter reachability or request another collection.
* Logical object sizes represented padding in separate arrays. Layout estimates and region heuristics were mixed into oracle-side memory accounting.
* OpenJ9 was a stub. VM selection and layout queries relied on the host JVM, and collector options were not resolved per target build.
* Translator tests checked its internal model rather than compiling and executing the emitted Java. Execution failures and timeouts were generally discarded by the fuzzer.

These observations motivate explicit contracts and independent checks. They do not establish a GC defect or prescribe a replacement architecture.

## Questions for the design discussion

* What observations must each checkpoint provide, and which assertions follow from its collection and reference-processing guarantees?
* How should the oracle represent permitted soft and weak reference outcomes, including operations that depend on collectible objects?
* How can generated code address objects and reference slots while controlling temporary roots?
* Which responsibilities belong to the portable model and which to each JVM integration?
* Which capability probes establish that the common observer satisfies each target's graph observation contract?
* Which physical layouts, stress patterns, reproduction artifacts, and reduction facilities are needed for the first useful version?
