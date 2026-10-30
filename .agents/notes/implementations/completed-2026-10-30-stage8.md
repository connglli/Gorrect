# Stage 8 HotSpot coverage

Status: complete for tested HotSpot families/modes and cycle-completion coverage. No commit before user review; stage all changes at handoff. Formats remain at version 1.

## Implemented choices

* Existing commands support Serial, Parallel, G1, ZGC, and Shenandoah. Profiles add collectorMode: default, ZGC generations, and Shenandoah satb/iu/passive. Bounded target-side facts queries resolve available flags and MXBean identities. The workload handshake rechecks selection; the saved profile freezes the actual mode for replay. No engine-JDK inference or silent fallback is used.
* Family-probe failure is compared with the same base VM settings to distinguish an unavailable selector from a startup error. Deadlines and campaign limits include probes; processes and sockets are cleaned up. Failure fixtures forward facts queries without suppressing their intended workload failures.
* Optional gc.control selects system or whitebox-full independently of JIT. Defaults preserve the established policy. WhiteBox native availability is exercised before graph construction; the startup cycle cannot satisfy an in-program request. An unavailable explicit control cannot fall back. GC policy works without JFR when no evidence is required.
* The VM bridge records bounded JFR request spans and VM GC events when evidence is requested. The verifier checks VM PID/start time, run/version, thread, operation, visit/phase, ordinal, control, normal return, and non-overlap in worker order. Every required request must contain a completed qualifying cycle with the requested VM cause. Young/minor-only events, pauses, partial overlaps, wrong causes/modes, duplicate/missing spans, and lost recording data cannot pass. Coalesced requests may share a fully contained cycle.
* Completed-cycle coverage is independent of graph semantics and pause counters. It forces no clearing, queue delivery, or reclamation. Reference-reclamation remains unsupported: a completed cycle does not prove every retained object must disappear.

[Usage](../../../docs/usage.md#profiles) owns profile fields and completion guarantees; [implementation](../../../docs/implementation.md#jvm-integration) owns the bridge/probe mechanics. Necessary comments explain target-specific mode resolution, native API probing, and evidence containment.

## Verification

The full build and final focused checks pass: 260 Java tests, native wire/loading/capture checks, strict Javadoc, and JDK 17/21 compatibility. All 16 original collector matrix tests passed with no skips; final coverage adds combined capability probes, unavailable controls, and invalid-base diagnostics. Cases cover fields, arrays, independent workers, contended ownership, and conditional references. G1 concurrent System.gc and independent WhiteBox GC control pass. A no-recording GC control run confirms JFR is optional.

Fourteen selected HotSpot fastdebug C1/C2 runs pass across all five families, both ZGC generations, and Shenandoah SATB/IU. C1 runs cap the VM at level 1; JDK 17 C2 also passes. Interpreted G1 retains a satisfied semantic verdict with compilation unmet. VM cycles and actual compiled frames remain separate evidence. Only exercised WhiteBox APIs are established compatible; the external client may warn about unrelated registrations.

Default ZGC resolves to a saved explicit mode, and its exact-class replay passes. Offline verification and G1 replay pass. Negative checks cover unavailable modes, ignored explicit GC, missing/wrong-VM evidence, unsupported minor/young events, overlapping requests, wrong causes, and unavailable controls. JVM paths and artifacts remain ignored. These checks do not establish effectiveness at finding real GC bugs.

## Sources and limits

[Oracle JDK 21 collector documentation](https://docs.oracle.com/en/java/javase/21/gctuning/available-collectors.html), inspected OpenJDK collector/WhiteBox sources, and [OpenJDK ZGC release history](https://wiki.openjdk.org/display/zgc/Main) informed the mappings. JDK 17 lacks ZGenerational; JDK 21 provides it. Bean identities distinguish older non-generational and later generational-only VMs without assuming a version-specific toggle. The selected G1 build has an additional concurrent bean.

Only available builds/modes are claimed. New private VM event/bean naming requires an integration update. Existing named-root, history-array, weaker-memory, and reclamation limits remain explicit. Worker pause summaries still retain one successful request per phase; completed-cycle proof uses the full request ledger instead.
