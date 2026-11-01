# Automatic queue portable semantics

Status: completed. [ReferenceProgram](../../../engine/src/main/java/io/connglli/gorrect/engine/gir/ReferenceProgram.java) proves the [dedicated-root rule](../designs/completed-2026-10-31-automatic-reference-queues.md) over all syntax, including skipped writes. One qualifying root suffices; other roots and aliases are allowed.

[ReferenceFrontier](../../../engine/src/main/java/io/connglli/gorrect/engine/oracle/ReferenceFrontier.java) owns clearing, notification debt, permanent insertion and membership. Each candidate's full membership supplies roots before strength closure; physical gaps only mask comparison. Snapshot storage charges the accessible union of candidate members with uncleared constructor edges. This conservative accounting bound must never supply semantic roots.

[HistoryOracle](../../../engine/src/main/java/io/connglli/gorrect/engine/oracle/HistoryOracle.java) checks actual constructor targets/queue arguments and scalar-positioned API identities. Explicit clear creates no debt; consumed insertion cannot repeat; delivery may remain pending indefinitely. Literal-null and legacy encodings remain unchanged. The [runtime note](completed-2026-11-01-automatic-queue-runtime-backend.md) owns capture qualification and integration.
