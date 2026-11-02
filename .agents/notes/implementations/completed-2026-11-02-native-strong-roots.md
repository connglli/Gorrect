# Strong native roots

Status: the [first design slice](../designs/completed-2026-11-01-native-strong-roots.md) is implemented. [NativeRootProgram](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/NativeRootProgram.java) admits strong sequential controlled programs; native storage remains ineligible for retirement. Java fields do not shadow native slots.

[NativeRoots](../../../native/src/native_roots.hpp) owns bounded run-scoped globals. Replacement acquires before deletion; null acquisition fails even without a pending exception. Invalid run/shape/quota requests preflight before mutation. Reads create independent Java handles. JNI transitions and temporary locals remain observable effects.

[NativeRootEvidence](../../../protocol/src/main/java/io/connglli/gorrect/protocol/NativeRootEvidence.java) brackets the same graph walk with actual handle/kind/tag/generation records. Raw JNI-global multiplicity must match every slot, including aliases. Only qualified native projections advance chronology; rejected evidence cannot hide later refutations. Cleanup follows final capture and requires empty storage plus a separate complete zero-global walk, never reclamation.

[Host admission](../../../engine/src/main/java/io/connglli/gorrect/engine/run/NativeRoots.java) checks controlled provenance, supported VM/build and per-startup kind/tag/multiplicity qualification. JNI lifetime and root enumeration use standard contracts without collector-private layouts. Both providers pass on HotSpot 17/21 and selected OpenJ9 gencon; six selected HotSpot21 collector modes pass with `jvmti`. Broader policy admission still requires live qualification and every-cut evidence. [Review](../commits/completed-2026-11-02-native-strong-roots-review.md) records validation and limits.
