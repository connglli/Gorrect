# Bounded direct helper calls

Status: implemented and independently reviewed, ready for coordinator/user admission. The user approved the [first-call design](../designs/2026-11-02-call-method-slice.md). GIR and artifact formats remain version 1.

## Implementation decision

Declaration-keyed frames suffice for this slice: an acyclic sequential call graph cannot activate one definition twice simultaneously. The model keeps suspended caller scopes as roots while isolating callee lookup. The Java emitter uses direct typed static methods and failure cleanup, without a workload-reference frame registry or an activation/wire-format extension. [Instruction.Call](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) owns call effects and cleanup; [MethodProgram](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/MethodProgram.java) owns whole-source admission and conservative capacity proofs.

The native-root implementation is an explicit dependency, excluded from this review unit. Call-owned changes are exported against dependency tree `23f553abb43884908ba5f67770f062b209e0458a`; native dependency source is `32e5983704195159611f5e6e7e6dc6765dfda73b`. No native production source changes were needed.

## Review and validation

Independent review found that source bounds omitted aggregate metadata for unused registered shapes, cumulative array identities, emitted array-name literals and aggregate class constants. Wide argument loads also need a per-call code allowance. The source preflight bounds those provider resources while keeping method-free limits unchanged. Evidence review also found that exact-trace success could mark method calls verified before helper-frame or terminal-provenance failures were known. [RunVerifier](../../../engine/src/main/java/io/connglli/gorrect/engine/run/RunVerifier.java) grants that capability only after final satisfaction; observed entry markers still determine exercise. Regressions cover both capture points and brackets, incomplete traces, and missing terminal frame/code evidence.

Focused frontend/model tests cover nested, diamond and repeated calls, aliases/nulls, parameter release, suspended callers, rejected-entry snapshots, body/exit budgets, saturation and combined nesting. Independent generated-Java failure injection checks completed effects, prefix markers and restored depth. Strict Java 17 lowering and disassembly establish actual static calls. Retained run/verify/exact-class replay passed on HotSpot 17/21, with GC completion required, and selected OpenJ9 21 gencon. Ten method-free fixtures matched baseline canonical GIR, generated Java, layout JSON and rejection text; older JVMTI and retained bundles verified successfully.

The exact frozen-source full check ran 1,136 Java tests: 825 engine units, 272 HotSpot cases, 33 OpenJ9 cases and six observer cases. Three failed: the known OpenJ9 Metronome VM-access assertion and two method-free young-GC certificate gaps. Their JFR evidence shows that the requests returned before a subsequent GC-locker cycle began; the unchanged-source eight-case rerun passed. All call-specific tests, strict compilation/Javadoc, native/runtime checks and both hook stages passed. The full run is not green. Source manifests, raw bundles, VM dumps and review receipts are retained in ignored local coordination state; no certificate was weakened.

Author: GPT 6.1 Sol <codex@openai.com>.
