# Ordinary object clone implementation

Status: implementation complete for integration review. The accepted [design](../designs/completed-2026-11-02-object-clone-slice.md) extends existing clone syntax to strong ordinary objects without changing GIR/artifact version 1. Array-only native-root and method compositions retain their contracts; object-clone combinations remain guarded.

The shared lexical clone map selects actual Cloneable classes and optional layout markers. Real Object.clone copies payload; ordinary Java stores replace only diagnostic site/occurrence before return, while worker identity remains final. Source/layout checks grant exactly those mutable roles. Native registration admits only the bootstrap zero-field, zero-superinterface Cloneable marker. No workload-reference registry or final-field rewrite is used.

Startup checks the selected helpers without exporting Java references. Reviewed captures contain no probe IDs or qualifier frames; absence of stale physical temporaries relies on frame return, and an ID collision would be an extraction gap. An aggregate source bound covers skipped selected shapes. Both metadata modes require controlled launch and actual qualification; unknown targets remain unsupported.

Frozen source/test tree `19ba9f8fd2781cbda78fb5ce6ebab831ba00400a` passed 847 unit, six observer and eleven native checks plus runtime/Javadoc smokes. All 38 object-specific cases pass. Run/verify/exact-class replay covers HotSpot 17 named-root JVMTI and retained local bindings, selected HotSpot 21 and OpenJ9 with both local metadata providers. HotSpot clone graph runs require completed-GC certificates; OpenJ9 supplies observed GC event counters and explicitly rejects the unavailable completion-certificate requirement. Thirteen legacy Java/layout pairs, including the actual primitive-timeout source, match the frozen dependency in one JVM. Copies of two old array-clone bundles verify without changing their originals.

The full run remains red: one known OpenJ9 Metronome VM-access assertion, one clone-free primitive startup timeout and four synthetic failure cases whose one-second collector probes expired on fastdebug 21. Original logs, bundles and dumps are retained. The unchanged synthetic class passes all eight cases on normal installed 21; the single permitted primitive rerun repeats its original ten-second startup timeout, as does committed pre-clone baseline `63fa442`; its cause remains unresolved. Neither result changes the full verdict or establishes another JVM bug.

Native registration retains its inherited 1,024-class ceiling; cloning adds no registrations. Qualification and capture can affect allocation, optimization and liveness. No intrinsification or continuous compiled execution is established.

Author: GPT 6.1 Sol <codex@openai.com>.
