# Stage 16a young collection requests and certificates

Status: implementation, two independent reviews and coordinator integration complete; awaiting user review. This slice follows the [accepted stage 16 design](../designs/2026-10-30-stage16-gc-controls.md), using the existing collect instruction and version 1 evidence. The [usage contract](../../../docs/usage.md#profiles) owns configuration and supported combinations.

## Scope

Profiles select whitebox-young independently of JIT; young-gc requires actual Serial/Parallel/G1 young events inside every executed request. Pre-workload method exercise establishes availability only. Whole/major completion retains its separate classifier, and known incompatible combinations stop before target startup. The runtime holds no workload references or expected graph values.

## Validation and review

The integrated final source, including committed scalar branching and the startup-cause fix, passes 476 Java tests: 336 unit, 138 HotSpot integration and two observer checks, plus native wire/loading/observer and strict compiler/Javadoc checks on JDK 17/21. Independent final checks also pass 24 focused target cases and ten additional JDK 17/21 client probes, covering missing/unregistered/throwing startup controls, later request failure and minimal JIT-free clients.

Six selected HotSpot fastdebug Serial/Parallel/G1 plain/JIT configurations pass run, offline verification and replay. JIT configurations establish the existing joint compiled-execution and worker-GC requirements. Native bootstrap fixtures declare only exercised methods; unrelated registration warnings remain logged rather than implying whole-client compatibility. Independent certificate review also verifies legacy full/major bundles, offline use without original installations and readable cause/kind/control corruptions.

| Finding corrected | Verification |
| --- | --- |
| Unsupported young-policy combinations could reach startup method exercise. | Shared preflight rejects known combinations before target creation; live availability still comes from the configured method. |
| Missing or structurally malformed worker context could erase graph refutations or collection gaps. | One guarded stress boundary preserves EXTRACTION_ERROR/VIOLATED and unverified certificates; the original duplicate-window reproducer and exact/reference corruption cases pass. |
| Startup reflection wrappers hid native-registration or exercised-method failures. | Saved diagnostics retain the originating class/message, with unsupported/no-fallback/no-workload assertions. |
| The accepted-design link contained a space in its filename. | Corrected relative target and changed-note link scan. |

## Limits

Concurrent coalesced calls may leave the strict per-request interval requirement unmet; positive fixtures use ordered worker phases. Request return never establishes promotion, young-only execution, clearing or reclamation. Generational-ZGC/OpenJ9 certificates, per-site selection, concurrent phases and placement remain later slices. No new GIR syntax, CLI override, evidence version or global budget is introduced.
