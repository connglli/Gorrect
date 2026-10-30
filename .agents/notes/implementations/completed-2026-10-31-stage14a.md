# Stage 14a scalar branching

Status: implementation and independent review complete for the minimal branch-first slice of [stage 14](./2026-10-29-implementation-plan.md#14-add-scalar-control-frames-and-exception-semantics); awaiting user review.

## Scope

[`if-nonzero`](../../../docs/gir.md#instructions) is one structured conditional over existing long-valued bindings/literals. Zero selects the optional else arm; every nonzero value selects the first arm. Child scopes export no bindings and cannot release an outer binding. Both arms are statically validated, including constant and zero-iteration cases. Arithmetic, conversions, extra numeric types, calls and exception syntax remain deferred.

The exact, sequential-reference and concurrent interpreters derive the branch choice independently from modeled values and check the existing primitive BRANCH record. Direct Java conditionals preserve ordinary compiler paths. Existing natural-profile and worker-synchronization restrictions remain intact. Syntax planning bounds both arms; exact obligations still use the executed path, while recorder capacity also reserves unselected arms. No profile, CLI, runtime API or version 1 evidence-format change is introduced.

## Checks and limits

Focused unit checks cover zero/nonzero/extreme values, scopes and keyword labels, conditional visits/declaration coverage, transactional rejection, and reference-state branches beside missing captures. A separate exhaustive CAS/branch/winner model enumerates 48 candidate observations. Its richer two-arm fixture uses the existing supported 10,000,000-transition budget; defaults and proof requirements are unchanged. Every possible invalid/racy schedule remains a preflight rejection.

Java 17/21 integration checks generated execution, offline verification and exact-class replay; actual then/else local coverage; direct conditional bytecode; unselected-arm recorder reservation; corrupted markers with later capture loss; and coordinated worker cuts. The full normal build passes 447 Java tests: 327 unit, 118 HotSpot integration, and two observer checks, plus strict compiler/Javadoc, native wire, Java 17/21 loading/observer and runtime-cut checks. Selected HotSpot and OpenJ9 reference-branch cases with real collection pass run/verify/replay; an older version 1 bundle still verifies from an isolated copy. Existing changed-file hooks pass. Selected-target execution does not establish compiled coverage, which these cases do not request.

History and recorder bounds can conservatively reject a case whose selected path alone would fit; exhaustion remains a checking-limit/inconclusive result.
