# Loaded class provenance investigation

Status: investigation complete. This note records the experiments and requirements behind the [retained provider](../implementations/completed-2026-10-31-retained-provider.md); [observation](../../../docs/observation.md) defines implemented capture policy.

## Result and evidence boundary

A non-transforming JVMTI ClassFileLoadHook can compare actual load input with retained compiler bytes, pair the defining loader with the prepared class, and identify that class's methods without requesting local-variable access. On the selected OpenJ9 build, the hook capability does not select local-access full-speed debug, and the experimental VarHandle worker compiles. This is a usable building block for a controlled launch. It is insufficient for unrestricted profiles containing other transformers or later class modification.

The [JVMTI 21 load-hook contract](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#ClassFileLoadHook) places the callback before class construction and chains replacement bytes between agents. Retransformation-incapable environments precede capable environments and do not receive retransformation callbacks. A [ClassPrepare event](https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#ClassPrepare) supplies the class after preparation and before execution. These guarantees permit loader/class/method pairing; they do not guarantee that a particular hook observed the last transformation. This inference is confirmed by the negative controls below.

No standard facility examined here returns the complete final classfile, including its local-variable tables, after arbitrary transformations without a relevant observation or configuration constraint. Method bytecodes alone would miss a transformation that changes only compiler local names or scopes. Reading or hashing the retained file alone never proves what the target loaded.

## Selected implementation and observer effects

The inspected OpenJ9 source and existing target identify revision `6e16eb1a4fc681949ccc6b100b984ab217d8cb3f`; no trunk build, download, or execution was performed. The experiment uses the existing JDK21 target configurations and their OpenJ9 `-Xshareclasses:none` setting.

* [jvmtiCapability.c](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/jvmti/jvmtiCapability.c) advertises `can_generate_all_class_hook_events`. Its acquisition does not reserve the breakpoint, frame, field, or stepping hooks that require debug execution.
* [jvmtiHook.c](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/jvmti/jvmtiHook.c) routes load callbacks through the normal or retransformation-capable class-load chain. Local-variable access separately requests `LOCAL_VARIABLE_TABLE | CAN_ACCESS_LOCALS`. Retransformation requests `ALLOW_RETRANSFORM | CAN_REDEFINE_CLASSES`.
* [J9Options.cpp](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/compiler/control/J9Options.cpp) checks local access and debugger hooks in `isFSDNeeded`, excluding the class-load hook. Redefinition alone can enable hot-code replacement even when full-speed debug stays off. Requesting retransformation to move an observer later in the chain would therefore change compiler policy and still require excluding later observers or agents.
* [defineclass.c](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/bcutil/defineclass.c) copies class data and the name when class-load hooks are enabled. Shared-cache inputs may require classfile reconstruction. Callback work, native allocation, class loading time, and compilation scheduling remain observation effects even when no bytes are replaced.

The tested observer requests tagging and compiled-method events in both comparison runs, adding only all-class-hook events in the hook run. Its reported local access, bytecode access, redefine, and retransform capabilities are zero. Source analysis and successful compilation support avoiding the known [VarHandle full-speed-debug restriction](https://github.com/eclipse-openj9/openj9/blob/6e16eb1a4fc681949ccc6b100b984ab217d8cb3f/runtime/compiler/optimizer/VarHandleTransformer.cpp); they do not establish identical GC timing or generated machine code. Production policy must describe the hook as diagnostic observation. Disabling the hook after certification could reduce later loading overhead only under an already established prohibition on subsequent class modification.

## Bounded certificate requirements

The investigation identified the following evidence chain for an explicitly controlled launch. It supplies compiler metadata provenance, not graph values or roots.

1. The host reads one bounded retained `Case.class` byte array, parses metadata from that array, and identifies those exact bytes for the run. The native observer receives an immutable copy or a retained artifact whose exact contents it reads before the target can mutate it. A later host read must agree with that same content identity; a pathname is insufficient.
2. The observer enables the hook at startup, before `Case` can load, and copies or compares its actual input within an explicit byte ceiling. It returns no replacement bytes. Missing events, capacity exhaustion, callback errors, mismatched bytes, or repeated attempts that cannot be paired uniquely prevent certification.
3. A ClassPrepare event pairs the expected binary name and defining loader with the exact class mirror. JNI identity comparison is required; an object address, signature, loader name, or `identityHashCode` is insufficient. Weak references avoid retaining a loader, but a cleared weak reference must fail correspondence rather than becoming the bootstrap-loader identity. Temporary strong references used for comparison must be released outside the heap walk.
4. Class methods receive recorded native method tokens, names, and descriptors. `GetMethodDeclaringClass` must resolve each relevant token to the certified class mirror. The target's intended `Case.class` mirror must be registered explicitly or otherwise proved to be that class; a name lookup alone cannot select between loaders. A raw root token must resolve to this same class and method when consumed.
5. The complete run and capture evidence retains the certificate's content identity, class/loader association, method mapping, completeness, and diagnostic limitations. The metadata's presence must remain distinct from the native local-variable-access capability. Integration must define compatible version 1 evidence fields and extraction diagnostics before activating the provider.

The controlled-launch condition is necessary, independently of those records. It must exclude other modifying Java/native agents, JDWP-driven modification, dynamic attachment, arbitrary native code that creates another JVMTI environment, and later redefinition or retransformation. The trusted launcher, support libraries, classpath, option sources, and generated loading path must support this condition. Inherited VM-option environment variables and user-supplied launch wrappers or extra agents cannot silently count as controlled. Unknown configurations remain unsupported. Multiple loaders can be supported when every actual class and method has an exact association; signature-only selection remains ambiguous.

This is a trust boundary for the harness and selected VM, not protection against malicious code in the same native process. Standard JVMTI does not let one observer prove that no other environment can modify its classes. Merely checking the observer's own capability bits or seeing no redefine callback cannot establish the controlled-launch condition.

## Physical root and frame evidence

The probe uses real FollowReferences STACK_LOCAL records for a tagged object and resolves their method tokens to the prepared class. It never reads a local object value through JVMTI. Tagging the probe value through JNI, stack walking, and heap walking are explicit diagnostic effects.

On selected OpenJ9, the synthetic worker supplies two raw roots at depth 1, slots 0 and 2, both with location `-1`. Standard GetStackTrace immediately before and after the heap walk supplies the same worker method at depth 1 and BCI 30. The before/after traces agree while the calling Java frames remain parked in the same native call. On selected HotSpot, the raw roots themselves report worker BCI 30 and main BCI 41. The extra OpenJ9 root at slot 2 cannot be labeled a source local merely because it points at the expected object.

Worker compilation events establish prior compilation on both VMs; compiled execution of the captured activation remains unverified. The fixture warms only `capture=false` and first takes `capture=true` at capture. An independent selected-HotSpot run of the unchanged fixture and agent, adding compilation logging, records a C2 `unstable_if` trap with `action=reinterpret` at BCI 27 before the capture call at BCI 30. The raw roots and stable frame locations still succeed. Any compiled-frame coverage claim requires an exercised capture path and independent execution/deoptimization evidence.

These results establish a separate frame-correspondence requirement. Retained tables cannot supply a missing execution location. A capture-consistent frame table may provide independent location evidence for a parked caller, but this experiment implements no scope recovery and establishes no correspondence for other running workers, recursion, or inlined activations. A consumer must bound and validate that correspondence and reject disagreement, truncation, missing frames, unsupported locations, and ambiguous mappings. Every non-null binding still needs a unique compatible raw physical root; null presence and logical activation identity require their own evidence.

## Experiments and classification

Ignored sources, complete commands, standard output/error, hook-byte copies, classfiles, capabilities, and compilation logs are retained under `.gradle/verification/loaded-provenance/` in the assigned experiment worktree. `run.py` builds the probe and runs the baseline/hook comparisons; `negative.py` runs the independent load and negative-control cases. They read the existing ignored target configurations. The Java probe uses host JDK21 `javac --release 17 -g -Xlint:all -Werror`; both native probes build with `g++ -std=c++17 -Wall -Wextra -Werror`. Each target invocation has a bounded subprocess timeout.

The selected OpenJ9 reports JDK `21.0.9-internal` and revision `6e16eb1a4f`; selected HotSpot reports fastdebug JDK `21.0.10-internal`. OpenJ9 comparison runs request synchronous compilation with count zero; HotSpot uses batch compilation and prevents worker inlining. These are deliberate probe settings, not a claim that production profiles use equivalent compilation policy. Four baseline/hook runs and eighteen independent load/negative-control runs finish with the expected outcomes.

| Case, exercised on both selected VMs | Observed evidence and classification |
| --- | --- |
| Synthetic VarHandle worker, baseline and hook | Both variants compile the worker and return checksum `1250075001`. Hook input equals the retained 1,830 bytes; the exact prepared class and four method declaring classes agree. Supports the bounded hook/identity facility. |
| Actual retained stage 13a emitted Case | The actual 9,683-byte compiler artifact loads, pairs with its defining loader, and resolves all eleven method tokens to its exact class on both VMs. This test prepares the class without initializing or executing the workload. |
| Same bytes in two defining loaders | Both exact class associations succeed; their method tokens differ. A signature-only query is ambiguous. |
| Null-name definition | The narrow name-filtered hook does not pair the prepared class. Unsupported by this prototype, not successful provenance. |
| Truncated definition | A hook sees mismatched bytes; definition throws ClassFormatError and no prepared class is paired. Malformed load, not valid provenance. |
| Expected bytes differ | Equality fails despite the matching class signature and methods. No certificate. |
| Downstream transformer changes local name only | Changing `b0` to `x0` leaves `javap -c -p` output identical and changes `javap -l -p`. The first observer sees equality, but a final observer sees changed bytes. Uncontrolled final-byte provenance fails. |
| Later redefine | Redefinition succeeds; the observing hook receives the modification and invalidates the initial association. The negative-control agent alone requests redefine capability. |
| Later retransform | Retransformation succeeds; the incapable observer receives no new hook and retains stale equality. The negative-control agent alone requests retransform capability. Uncontrolled final-byte provenance fails. |

The experimental probe does not implement production capture, metadata recovery or launch admission.
