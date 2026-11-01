# Retained metadata provider

Status: complete for the first controlled-capture slice. [Observation](../../../docs/observation.md#binding-metadata) owns provider semantics and qualification limits; [usage](../../../docs/usage.md#profiles) owns profile selection. GIR and artifact versions remain 1. Natural histories and general frame reconstruction remain guarded.

The slice certifies exact loaded code, the intended class and method identities, parked frames and retained scopes without local-variable access or manufactured roots. Weak metadata ownership, terminal main and once-only final status remain mandatory. Legacy bundles, first-class weak/soft wrappers, offline verification and exact-class replay retain their contracts.

Independent reviewers approved semantic comparisons, launch/code/frame evidence, and runtime/resource lifecycles. The reviewed 64-file candidate against 26daa23 has patch SHA-256 `323483a717e888315f60f8a9f05827fbe5a1dfdfd74fe37d2dd91f08e97eb8b8`; coordinator integration changes only plan/review prose afterward. No required finding remains.

| Review finding | Correction |
| --- | --- |
| A missing worker origin hid a covered caller mismatch. | Compare usable partial evidence while preserving the full semantic roots and sticky incompleteness. |
| Recorded effective arguments/environment bypassed launch admission. | Share bounded live/offline command, argument-source and environment checks; malformed facts cannot erase covered refutations. |
| Native allocation failure became unsupported setup. | Preserve resource error 110 through the runtime and FAILED frame as startup failure; explicit LIMIT/0 remains distinct. |
| Interrupted parking could leave false readiness. | Withdraw readiness only in the same generation and preserve failure publication before capture. |

The isolated full check/installDist passes 614 Java tests: 444 unit, 168 target integration and two observer tests, plus eight native tests and JDK 17/21 runtime checks. All frozen postimages match after that build. Independent semantic adversaries preserve hidden strong roots/acquisitions, refute covered mismatches and keep partial agreement inconclusive; 23 launch-evidence adversaries and native-producer-to-runtime-to-host resource/marker chains pass. Formatting and both hook stages pass.

Sixteen fixed HotSpot qualification cases on Linux amd64 produce 15 graph passes and expected unsupported generational ZGC on JDK 17. Selected OpenJ9 admission/replay and compiled VarHandle execution without local access pass; optimized local origins can be missing and caller depth can disagree, leaving those captures inconclusive. The broader default OpenJ9 matrix retains the [known Metronome assertion](../designs/completed-2026-10-30-openj9-metronome-assertion.md); it is not counted as passing. No new bug or trunk result is claimed. Local raw evidence remains ignored and is absent from a fresh checkout.
