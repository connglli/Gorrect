# Stage 12 critical review

Status: review complete. Reviewed all source, protocol/native changes, tests, documentation, configuration, and working notes against the project guidance and [critical-review skill](../../skills/critical-review/SKILL.md).

| Fixed during review | Verification |
| --- | --- |
| Capability probe operations were appended to ordinary run inputs. Guard augmentation by probe selection. | Saved GIR equality and unexercised-requirement regressions; full build. |
| A false nullness witness could bypass contradictory root evidence. Reject the conflict as inconclusive. | Independent synthetic root/presence tests. |
| Root matching repeatedly scanned metadata and could mix slot/frame origins. Index physical slots once and reject conflicting or overlapping mappings. | Wrong-object, missing/ambiguous-origin, repeated-visit, native and selected-VM checks. |
| Capability availability could be confused with actual checks. Preserve separate exercise/verification facts and independent scalar proofs. | Unexercised requirements and damaged-capture report tests. |

No outstanding findings. The full build, selected targets, compatibility/replay, calibration, and changed-file hooks pass. The user approved the staged changes for commit.
