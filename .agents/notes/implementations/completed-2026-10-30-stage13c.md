# Stage 13c coordinated worker capture

Status: implementation and critical review complete; awaiting user review. The combined build and public example checks pass, with an explicitly diagnosed compiled-coverage limitation. This is a slice of [stage 13](./2026-10-30-stage13.md), not completion of natural-history or retained-metadata integration.

## Scope and contracts

Direct, structurally matched worker checkpoints pause every worker and the caller at one capture. [GIR](../../../docs/gir.md#parallel-workers) owns synchronization and unsupported combinations; [observation](../../../docs/observation.md#binding-evidence) owns participant and physical-root coverage; [runtime/protocol architecture](../../../docs/implementation.md#target-runtime) owns the two-phase rendezvous. All existing formats stay at version 1, with a separate version 1 worker envelope and old main-capture compatibility.

The first declared worker supplies the canonical checkpoint identity. Actual per-site visits continue across group rounds; cut ordinals reset per group visit. Full all-schedules validity/race auditing also applies to plain-only worker-cut programs. Primitive presence and complete participant contexts never replace actual heap roots or turn history-plan placeholders into observed graph values.

## Review findings and fixes

Independent Runtime Safety Review reproduced loss of the initiating interruption cause when cancellation preceded publication. The worker wrapper publishes failure before terminating the Phaser; main capture/control failures keep their cause precedence. A second review corrected the regression to permit arrival-phase progress while still rejecting mutator release before failure publication.

Independent Capture Evidence Review found an inherited diagnostic gap: incomplete worker evidence disappeared beside a definite graph mismatch. The shared bounded envelope/completion check records extraction errors independently of graph matching, retaining a definite VIOLATED result and its graph diagnostic. Full path-dependent stress proof still requires a matching execution. Independent Contract Review corrected declaration-ID uniqueness wording so mandatory empty participants remain valid.

## Validation

The complete isolated candidate passes 382 Java tests: 268 unit, 112 HotSpot integration, and two observer checks. Strict compiler/Javadoc checks, native wire tests, native loading and observer fixtures on JDK 17/21, and runtime cut checks on both JDKs pass. Independent runtime checks additionally cover real-session failure injection, 200 interruption visits per JDK, 30 repeated final suites per JDK, and negative controls against the original early-cancellation order.

The public example passes generated execution on host JDK 21, JDK 17, selected HotSpot C1 and selected OpenJ9, with offline verification on the latter three. Default history bounds remain unchanged. Selected C1 establishes requested compiled entry/exit coverage. Selected C2 validates the graph but leaves compiled-execution coverage unmet: its saved witnesses show compiled entry and interpreted exit; installation alone is not an execution certificate. Compilation logs place both C2 deoptimizations at the first worker checkpoint. The selected build's root-walk EscapeBarrier deoptimizes frames with escape-analysis state; disabling allocation elimination alone preserves the gap, while disabling escape analysis supplies compiled entry/exit coverage with byte-identical generated/support bytes. A one-worker escaping control also satisfies compiled coverage. These are diagnostic controls, not proof of transparent two-worker capture. Two-worker publication controls exceed existing proof budgets and remain checking-limit/inconclusive results. No source workaround or weaker coverage criterion is introduced.

## Remaining limits

Natural reference/conditional histories, recursive frames, conditional capture groups and nested worker cuts remain guarded. Missing optimized roots remain inconclusive. Selected OpenJ9 local-access/full-speed-debug limits and the separate loaded-byte/frame correspondence proof remain explicit; no production metadata fallback is introduced. The local bug archive and deferred OpenJ9-trunk work stay untouched.
