# Stage 8 review

Status: complete; changes await user review before committing.

Reviewed collector identity, target-specific mode discovery, replay freezing, startup/campaign deadlines, subprocess cleanup, and GC/JIT control independence. Completion proof checks request provenance/order, VM cause, cycle kind/mode, and whole-span containment; pause counters cannot satisfy it. Fixed Serial availability probing, combined-probe collection, optional recording for GC-only control, request overlap rejection, and mode-specific ZGC cycle checks. No outstanding findings.

Full and focused checks pass: 260 Java tests, native checks, strict Javadoc, JDK 17/21 collector coverage, selected HotSpot C1/C2 matrices, interpreted negatives, independent GC controls, offline verification, and replay. Documentation links resolve and machine paths remain untracked. No physical-reclamation or GC-bug-finding efficacy claim is made.
