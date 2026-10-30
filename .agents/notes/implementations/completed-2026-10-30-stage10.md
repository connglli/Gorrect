# Stage 10 quality review and refactoring

Status: complete; approved by the user with the contract/layout follow-up addressed. The user authorized the planned project-wide quality stage. Preserve GIR/artifact version 1, CLI/profile behavior, oracle semantics, generated workload behavior, and target evidence requirements. Stage all changes before committing the approved stage. DONT-DELETE-experiment stays untracked and untouched. Latest OpenJ9 trunk testing remains deferred.

## References and selected conventions

* [Google Java style](https://google.github.io/styleguide/javaguide.html) supplies formatting/import conventions: explicit imports, consistent grouping, readable names, focused members. Retain Gorrect's existing compact guard clauses rather than mechanically adding braces or changing every declaration.
* [JUnit contribution guidance](https://github.com/junit-team/junit-framework/blob/main/CONTRIBUTING.md) supplies concise contract summaries and explicit assertion imports. Inspected OpenJDK java.util.Objects and [Guava ImmutableList](https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/ImmutableList.java) provide contract/immutability examples; no Guava dependency or code is introduced. Preserve the existing Test suffix and JUnit dependency; do not import their release tags, annotations, dependencies, or build plugins.
* [Oracle Javadoc guidance](https://www.oracle.com/java/technologies/javase/writing-doc-comments.html) and inspected OpenJDK Objects methods supply precise bounds, failure, and mutability contracts. Document public/cross-module behavior when names do not establish it; avoid boilerplate accessor tags and repeated prose. Existing docs/AGENTS.md remains authoritative.

## Work slices

1. Audit every tracked Java/C++ source, tests, build, and project document. Record findings and deliberate limits by component. Baseline: 88 engine Java files (13,782 lines), 15 runtime files (1,587), nine protocol files (932), three native headers/sources (821), plus native tests/build support.
2. Separate compilation, target process/control, and offline verification from RunEngine. Share structural GIR traversal for queries only; retain independent execution/oracle logic and preserve exact emission order. Simplify emitter control/operation dispatch where it improves comprehension.
3. Review protocol parsing/resources, runtime control configuration, and native wire metadata. Share concrete repeated facilities where useful; preserve format bytes, bounds, errors, callback restrictions, and no-workload-reference recording. Keep cohesive exhaustive semantic dispatches together when splitting would obscure them.
4. Split broad execution tests and consolidate mandatory profile setup without reusing production oracle/codecs as independent expectations. Select integration suites by JUnit tags. Clean imports across all modules and rewrite necessary contracts/comments.
5. Enforce strict Javadoc through normal Gradle checks, preserve both hook stages, and use existing formatter facilities for unused imports. Add focused checks only for recurring concrete problems; introduce no speculative framework or dependency. Run unit/native/17+21 integration, offline/replay and selected-target regressions, including the known OpenJ9 Metronome failure without a driver workaround.

## Audit record

* Engine run: RunEngine mixes four execution phases and repeats artifact/VM plumbing. Split concrete phase responsibilities; do not invent a generic plugin framework.
* GIR: ReferenceProgram/HistoryProgram and verifier declaration scans repeat traversal. Structural traversal can be shared; execution, scoping, and search must remain separate.
* Model/oracle: retained immutable graph/value types and cohesive exhaustive semantics. Search continuations, fork-local mutable state, shared work budgets, frozen bitset keys, complete join keys, and vector clocks stay together; splitting them into a general executor or sharing oracle decisions with emission would weaken reviewability. Extracted only the pure operation-to-event classifier from a nested conditional and documented live Context ownership.
* Emitter: separated lexical cleanup, structured control, and leaf operations. Named host protocol ordinals replace scalar magic numbers without changing generated Java bytes. The exhaustive operation dispatch remains cohesive; a template/visitor framework would add indirection without a concrete requirement.
* Observation: separated layout validation, record indexing, object/wrapper identity resolution, and graph construction. Preserve all field/slot/identity coverage checks and their ordering; keep format-specific helpers in the private decoder.
* Runtime: parse JIT metadata once into a typed internal record; keep primitive single-writer recorders and reference-free witnesses. Rejected numeric settings remain inside the existing control-unavailable guard. No new controller API or property format is needed.
* Protocol: retain separate format-specific decoders and their strict headers/counts/footer checks rather than inventing a generic codec framework. Buffer file streams and cache trace enum values; wire bytes remain version 1. Control stream ownership stays with the caller.
* Native: replace class/record/status/capability magic numbers with named wire constants/types, retain explicit ordinal values, and prohibit copying uniquely owned JVMTI allocations. Keep heap callbacks in the cohesive single agent; callback storage and no-JNI/no-allocation constraints are unchanged.
* Tests: split execution coverage into strong, failure, replay, history, and concurrent/campaign classes; share mandatory profile setup only. Select suites by JUnit tags, with explicit assertion imports. Independent exhaustive models and raw-frame fixtures remain independent.
* Configuration: split profile field groups into focused parsers. Strict JSON object/array access fixes malformed fuzz profiles escaping as unchecked type errors; seven CLI regressions assert input-error status before output creation. This restores documented invalid-input handling without changing accepted profiles.
* Build/docs: external target suites run fresh rather than caching results against an unchanged executable path while VM libraries/environment change. Strict private Javadoc is part of normal check; missing-tag lint remains disabled to avoid boilerplate. Existing formatter removes unused imports; hook stages/versions are preserved. Important interface contracts specify lifecycle, caller obligations, identities/sentinels, failure behavior, evidence limits, and ownership. Logical steps within long methods are separated by blank lines; related statements stay together. Documentation is rewritten in its owning passages.

## Verification

The normal committed build passes 275 Java tests: 218 unit, 55 HotSpot integration, and two observer checks. Native wire compatibility, real capture/loading on JDK 17/21, warning checks, and strict Javadoc pass without the former local init script. Tagged suite discovery preserves all original tests. Two structural traversal cases and seven malformed-profile CLI cases are added.

Eleven pre-refactor examples/generated cases preserve generated Java bytes and JSON layout values (JSON object key order was already unspecified across JVM processes). Selected HotSpot fastdebug C1/C2 contended runs pass. Old OpenJ9 run bundles verify and replay with the refactored engine; a new contended OpenJ9 JIT case also passes. The selected OpenJ9 matrix remains 15/16 with only the known Metronome target assertion. No driver workaround or latest-trunk test is introduced.

Final critical review found no outstanding implementation issue. Removed redundant self imports, reused artifact-property decoding, and confirmed source/type cleanup leaves the search algorithms unchanged. Changed-file hooks, documentation links, portable paths, and archive exclusion checks pass. The ignored local experiment archive was neither edited nor staged.

The documentation/layout follow-up preserves the complete code-only Java AST, including literal values, against the approved staged implementation. Compiler, 218 unit tests, and strict Javadoc checks pass; the earlier full integration results remain applicable because executable code is unchanged.

The pre-stage baseline had 266 Java tests. All original cases remain, with nine added regression cases. The known OpenJ9 Metronome assertion stays visible as an external target failure; latest-trunk reproduction remains deferred.
