# Stage 13a critical review

Status: review complete for the first stage 13 slice; the stage remains open. Reviewed source, callers, native ownership/publication, evidence failure paths, tests, and documentation under the [critical-review skill](../../skills/critical-review/SKILL.md).

| Issue addressed | Check |
| --- | --- |
| Array reference recording cannot read ordinary-object identity fields. Read actual weak-tag metadata instead. | Initial/fresh arrays, nulls, and worker creator identities on JDK 17/21 and selected VMs. |
| Array atomics need receiver/index coordinates and an element handle. | Real volatile/CAS/exchange execution plus independent SC outcomes. |
| Recording must not race registry growth or serialize mutators through a recorder lock. | Fixed immutable published entries and concurrent native publication/read checks. |
| Missing binding declarations could become a history mismatch. Require declaration coverage before matching. | Damaged presence evidence remains inconclusive. |

No outstanding implementation finding in this slice. OpenJ9's compiled/local-access VarHandle limitation is retained explicitly; later conditional/worker/natural work is uncompleted. Full build, target checks with that limitation, compatibility/replay, and changed-file hooks pass. The user approved the staged changes for commit.
