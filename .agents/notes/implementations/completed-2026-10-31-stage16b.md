# Generational ZGC young certificates

Status: implementation complete for the generational-ZGC extension of [stage 16](./2026-10-29-implementation-plan.md#16-add-collection-and-phase-controls-with-evidence). [Usage](../../../docs/usage.md#profiles) owns profile admission and certificate requirements. No GIR, CLI, runtime, protocol or evidence-version change is introduced.

Profiles must explicitly select generational mode. Admission confirms a HotSpot JDK 21+ target and the generational bean set; a present generation toggle must agree, while gen-only VMs may omit it. Default/non-generational requests remain unsupported, and JDK 17 rejects the mode before workload startup. A returned control or startup self-probe does not establish execution coverage.

The classifier requires an actual positive ZGC Minor interval with the exact WhiteBox young cause wholly inside each validated returned request. Existing worker/group/round/phase/ordinal/thread correspondence remains mandatory. Major events, non-generational events, wrong causes, unrelated intervals and absent/malformed request evidence cannot substitute. A qualifying minor does not promise young-only execution, successful promotion, clearing or reclamation; whole/major classification is unchanged.

The integration check passes 630 Java cases with native/JDK 17/21 and strict compiler/Javadoc checks. The final requirement-reporting delta also passes 450 unit and three focused target rechecks. Two independent reviewers cover exact mode/cause/interval/context rules, actual JFR linkage, legacy/offline bundles, and malformed target facts with intact or conflicting graph evidence. Generated cases include repeated, conditional, main and ordered-worker requests, C1 execution, startup-only collection, ignored/full-only controls and JDK 17 rejection.

| Review finding | Correction |
| --- | --- |
| Malformed target facts erased a covered graph mismatch and collection gaps. | Guard both metadata-provider readers, continue independent graph checks, retain unverified requirements and clear stale derived capability claims. |
| Skipped stress checks omitted GIR-only requirements. | Preserve the union of GIR/profile requirements without duplicates; genuine C1 controls and refutations pass. |

Selected fastdebug HotSpot JDK 21 plain and C1 run/verify/replay establish young coverage; their local bundles retain the complete request/cycle evidence. The source dispatch sends the WhiteBox young cause synchronously to the minor driver, whose JFR tracer uses ZGC Minor. Exact source revisions, fixed artifact hashes, commands and run results remain in ignored verification evidence. Source admission, event evidence and capability-failure corrections have independent review; no broad collector matrix or new GC-bug claim is made.
