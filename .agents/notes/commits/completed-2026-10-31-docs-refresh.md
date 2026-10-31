# Public documentation refresh

Status: rewrite, independent contract review and coordinator checks complete. All five public guides remain human-facing; exact operation and wire details use their source homes. Pending named-queue/arraycopy behavior is excluded.

| Public guide | Before words | After words |
| --- | --- | --- |
| `design.md` | 939 | 627 |
| `gir.md` | 2591 | 1831 |
| `implementation.md` | 1585 | 773 |
| `observation.md` | 2088 | 853 |
| `usage.md` | 1410 | 963 |

Total public prose fell from 8,613 to 5,047 words (41.4%). The rewrite removes repeated contracts and encoding dumps, preserves the executable GIR example/anchors and keeps methodology, architecture, syntax, capture and usage separate. Its instruction table explicitly covers all 37 committed parser keywords with short descriptions, including every volatile spelling. The sole record-cell table moved to [HeapSnapshot.Record](../../../protocol/src/main/java/io/connglli/gorrect/protocol/HeapSnapshot.java); its native backlink follows that source home. No executable tokens changed.

Independent review closed precision fixes for limit keys, strong reads/stores, normative reference links and raw VM locations qualified as BCI. Checks pass 139 GIR/CLI tests, 98 local links/anchors, four snapshot-codec tests, eight native tests, strict protocol Javadoc and changed-file/commit-message hooks. Raw measurements and review identities remain in ignored coordination evidence.
