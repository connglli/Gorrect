# Automatic queue implementation review

Two independent reviews and coordinator checks close accounting, source-lifetime, caller-identity, launcher-provenance and failed-prefix findings in the [implementation](../implementations/completed-2026-11-01-automatic-queue-runtime-backend.md).

## Validation

Of 1,039 Java cases, 1,038 pass: 750 unit, 253 HotSpot integration, six observer and 29/30 OpenJ9 cases. Ten native checks, JDK 17/21 compatibility/runtime, strict Javadoc and hooks pass. Metronome reports `j9vm.227`, `jnicsup.cpp:1691`, `J9_PUBLIC_FLAGS_VM_ACCESS`, matching baseline `33f8dc6`; core, javacore, Snap, stderr and workload are retained. The matching signature establishes neither cause nor a fix.

Independent controlled runs under both metadata providers pass run/verify/exact-class replay with actual handler delivery and verified GC completion. Separate diagnostic breakpoint/JNI interception proves handler exclusion, wrong-lock rejection and failure cleanup; its extra agent is not an admitted launch mode.

The full suite tests v2; v3 and coordinator revisions change only prose/Javadoc and receive focused checks. Evidence checks verify 362 source postimages, 85 physical artifacts and 31 crash artifacts.

## Retained identities

SHA-256 identities bind the ignored local evidence; directories can move without changing these files.

| Artifact | SHA-256 |
| --- | --- |
| Author source manifest | `14f6a0fd07ca98249b4bfef8cca216fe06aa6dba9a7141b20c51d7e0130b2598` |
| Author source patch | `481f1c0ef736776b5f4de6c82705bcd008e971adf20fc186ebd6b443583a5e2b` |
| Physical proof manifest | `58512c201f656daa1925d1dc2a1a27c7c15bc1f8c39cbb95a316bfb5613aa9d8` |
| Metronome artifact manifest | `460fb370eac6543bd9dd0094b282da05994782153c5deba7d50db785a0900b0c` |
