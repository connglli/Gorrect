# Stage 15a first-class no-queue references

Status: implementation candidate for the first slice of the [accepted reference design](../designs/2026-10-30-stage15-reference-semantics.md). Both independent reviews and coordinator integration pass; awaiting user review. Later queue/actor/concurrency and natural/frame features remain guarded.

## Scope and contract

The [GIR contract](../../../docs/gir.md#reference-histories) owns the minimal syntax: weak/soft shapes, allocate-ref and existing receiver/lifetime/typed graph operations. Canonical wrapper vertices use the existing WrapperId namespace; ordinary objects retain ObjectId. HeapId tags those namespaces, and generic nullness uses the whole target rather than an absent ordinary component. Aliases point to one wrapper state, while clearing and notification remain frontier choices. No target construction referent is retained in a runtime strong field or registry.

Legacy slots stay opaque and retain their old constructors/bytes/verdicts. A conservative lexical type/location proof rejects possible registered exposure over all syntax, independent of the saved history, and legacy wrapped stores cannot target wrapper values. First-class atomic/concurrent programs and natural observation remain unsupported. Public history entries validate static/source support before dispatch or evidence handling; direct concurrent entries also exclude sequential first-class inputs. Distinct actual generated reference subclasses establish nominal wrapper types; ordinary allocate/object declarations do not construct them. Receiver encoding distinguishes omission from an explicitly negative legacy index.

The [observation contract](../../../docs/observation.md#tagged-reference-targets) owns explicit typed-envelope selection. The observer indexes actual ordinary/wrapper identities before referent edges, preserves root/local origins, and never substitutes expected targets. Constructor/get/state/clear/strong-read recording remains primitive-only. Missing tagged evidence cannot fall back to legacy bytes.

## Validation and review

The final integrated source, including both checker-boundary corrections, passes 556 Java tests: 395 unit, 159 target integration and two observer checks, plus native/Javadoc/JDK 17/21 checks. Eighteen added public-entry cases cover unsupported backend selection and namespace collisions. Both independent reviews pass exact final-source tests and separate cohort/alias/transaction, typed-codec, projection, bytecode and legacy-artifact adversaries.

Focused coverage includes parser/formatter/type/scoping/negative-index rejection; transactional object-budget/receiver failures; separate tagged namespaces; exact legacy envelope bytes; malformed/missing/namespace-corrupted typed records; alias-clear graph refutation; wrapper-as-referent acquisition; arrays; real collection; and safe mixed legacy slots/polls. An independent batch-clearing/Floyd-Warshall model enumerates 768 two-wrapper graphs/root sets across both strengths and holder cycles, comparing complete candidate sets; no production cohort helper is used by its expected model.

Selected HotSpot fastdebug and OpenJ9 gencon pass a real-collection alias workload through run/verify/replay. These profiles establish graph/evidence behavior, not compiled coverage. A genuine pre-feature reference bundle verifies from an isolated copy without modifying the original. Compiler warnings and changed-file formatters remain enforced; no target build/download/trunk or local bug archive change occurs.

## Critical self-review

| Issue corrected | Result |
| --- | --- |
| Receiver sentinel could confuse explicit negative indices with omitted syntax. | Separate receiver flag plus encoding/static checks; direct syntax/model rejection tests. |
| Global name-based origin collection could conflate unrelated lexical shapes. | Reuse lexical validator type resolution; guard by nominal owner shape/index across every syntactic store. |
| A wrapper pointer has no ordinary ObjectId target. | Generic heap-target/nullness checks and canonical wrapper maps preserve aliases and shape assertions. |
| No-queue wrappers must never gain GC queue eligibility. | Registered state is explicit in the frontier; a mixed legacy/new atomic cohort test checks pending bits. |
| Legacy wire writers could truncate a wrapper target. | Separate magic/explicit read/write path and exact old-byte test; legacy writer rejects typed-only entries. |
| Public history dispatch or direct backend calls could bypass first-class support limits. | Shared static/source preflight covers all four entries, with a separate concurrent-backend exclusion before evidence exits. Tests retain the supported sequential route and ordinary/opaque legacy histories. |
| Concurrent target decoding could erase an observed wrapper namespace. | The common decoder requires ordinary targets; collision tests through both public routes cover strong reads and legacy acquisition, including valid ordinary controls. |

No unresolved source finding remains; both checker-boundary P2 findings are closed on the exact final source. Independent reviews and final combined validation are recorded before user handoff. A retained-provider merge must preserve the canonical wrapper map in its additional projection method, with a combined compatibility check or an explicit unsupported guard.
