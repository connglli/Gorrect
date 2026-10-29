# Stage 6 reference histories

Status: complete for sequential ordinary-object reference histories. Stage all changes for user review before committing. GIR and artifact formats stay at version 1.

## Implemented contract

Sequential ordinary-object programs support soft/weak initial slots, fresh wrapper stores, single-read acquisition scopes, non-strengthening clearing observations, explicit clear, and queue polls. Acquired results stay strong until scope exit. Generated reachability fences preserve every reference binding's GIR lifetime. [GIR](../../../docs/gir.md#reference-histories) owns these semantics.

The checker validates recorded choices before interpreting their consequences. It expands legal atomic clearing groups, filters a bounded frontier against observations, and distinguishes clearing from queue delivery. Detached wrappers may be retained without creating strong referent roots. Replacement creates another incarnation. Ordinary payload mutations are shared across surviving states; surviving referents retain exact field obligations. Search exhaustion is inconclusive.

The target stores primitive observations, never a wrapper registry. Syntax bounds reserve storage for either branch. Wrapper subclasses carry primitive identities; JVMTI captures inherited referent fields without get. [Observation](../../../docs/observation.md) owns capture coverage and [implementation](../../../docs/implementation.md) owns the mechanism. Invalid preconditions are diagnosed from validated failure prefixes; insufficient evidence retains the runtime failure.

## Verification

The full build, strict Javadoc, 203 Java tests, native wire check, and native loading/capture checks on JDK 17 and 21 pass. Focused checks of the review fixes also pass. An independent exhaustive model enumerates explicit reference objects and retained wrapper roots across 4,608 small graph configurations, plus mixed paths. Focused tests cover replacement, protected referents, clearing monotonicity, delayed and duplicate queue delivery, altered choices and payloads, incomplete evidence, budgets, unsupported profiles, and failure classification.

The selected HotSpot fastdebug target passes the two-acquisition design example and a case with two actual weak-reference queue deliveries. Offline verification and exact-class replay pass. A generated concurrent JIT case passes with its profile and case requirements met. A 10-second campaign retained a campaign-limit partial case; running that saved case under the profile deadlines passed.

## Limits

Reference histories combined with parallel blocks or array shapes remain unsupported. Checkpoints require reference bindings to have ended and cannot occur inside acquisition bodies. No reclamation, deterministic processing completion, finalization, or resurrection assertion is supported. The generator's reference diversity follows in stage 11; contended and reference/concurrency combinations follow in stage 7. Effectiveness at finding real GC bugs remains unestablished.
