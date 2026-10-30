# Retained local-variable metadata proposal

Status: prototype for a later stage 13 slice. This note proposes a metadata source and records its limits; it does not change the [observation contract](../../../docs/observation.md), capability policy, or production projection. Integration Lead owns any decision to integrate it.

## Purpose and available facilities

The integration investigation reports that the selected OpenJ9 build enters full-speed debug when the observer requests `can_access_local_variables`, and its VarHandle transformer refuses a relevant compilation in that mode. The proposed alternative reads compiler debug tables from retained `generated/classes/Case.class` while preserving mandatory raw JVMTI stack-root evidence. This prototype does not test or alter the selected VM.

`RunCompiler` invokes the host compiler with `--release 17 -g`, so the prototype accepts classfile version 61.0. On the inspected Java 21 host, `java.lang.classfile.ClassFile` is absent, `javap` is a text-output tool, and `jdk.jdeps` exports `com.sun.tools.classfile` only to `jdk.jlink`. A small reader using standard I/O avoids an additional dependency, module exports, and a tool-output parser. Reconsider the reader if the engine adopts a JDK with a supported classfile API.

The parser follows the [Java 17 classfile specification](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html), including two-slot constants, length-delimited attributes, and half-open local-variable scopes. It combines multiple `LocalVariableTable` attributes. It checks numeric scope and slot limits, including the second slot of a `long` or `double`.

## Prototype interface

`RetainedLocalMetadata.read(Path, expectedSignature)` and the byte-array overload return immutable records with the owner signature, method names and descriptors, optional `Code` metadata, and compiler local entries. A local entry holds its name, field descriptor, slot, start bytecode index, and scope length. `CodeInfo` retains code length, maximum locals, and explicit table presence; missing and present-empty tables are distinct. Abstract/native methods have no `CodeInfo`.

The reader rejects an unexpected owner, unsupported version, malformed metadata descriptors, invalid metadata constant-pool references, duplicate method identities or local entries, invalid numeric ranges, noncanonical modified UTF-8, damaged framing, and trailing bytes with `IOException`. Unknown attributes and constant payloads outside the metadata path are skipped using checked lengths. The reader does not decode instructions, check instruction boundaries, verify bytecode, or validate references inside skipped constant payloads and attributes. JVM acceptance of the exact bytes is a separate precondition.

Two prototype resource ceilings bound allocations: 16 MiB of classfile bytes and 65,536 local entries across the class. These are implementation limits, independent of the classfile format's limits. The path reader requests at most one byte beyond its byte ceiling to detect an oversized input without relying on a separate size check. The byte-array caller must keep the input unchanged during parsing; no input bytes are retained.

The records contain no workload object, object identity, expected graph value, or inferred runtime root. The prototype has no production consumer and changes no version 1 artifact format.

## Identity and evidence boundary

A matching owner signature, method name, and descriptor identify a member of the retained classfile. They do not establish runtime class-loader identity or prove that the target executed those bytes. Before production use, integration must establish that each relevant raw method token belongs to the intended loaded `Case` class and to the retained method with the same name and descriptor. Its run configuration and retained evidence must support the assumption that no alternate loader, classpath entry, bytecode transformer, redefinition, or replacement artifact changed that class. A digest of retained bytes could name an artifact but would not alone prove what the VM loaded. The mechanism for establishing or explicitly constraining this provenance remains a design question.

A candidate local matches an actual raw method, physical slot, and bytecode index within `[start, start + length)`. Zero-length entries match no point. Overlapping candidate scopes are preserved by the reader and require an ambiguity check during projection. An out-of-range location, absent table, missing declaration, or unresolved method cannot be repaired from emitted source or an expected object identity.

For a non-null binding, actual JVMTI stack-root evidence remains mandatory, including thread, method token, frame depth, slot, bytecode location, and observed target. Explicit primitive presence evidence remains necessary for null bindings. The retained classfile supplies only compiler metadata; it cannot manufacture physical roots or justify marking a binding capability verified.

## Calls, recursion, and inlining

Execution Semantics proposes logical binding identity as worker, monotonic call activation, and declaration. Every recursive invocation shares the same retained method tables. Compiler metadata cannot distinguish those invocations, map a physical depth to a logical activation, or reconstruct an inlined frame. That correspondence needs independent primitive activation evidence plus actual method/frame/location evidence at capture. Missing or ambiguous correspondence remains incomplete evidence, even when compiler metadata is complete. The prototype performs no activation lookup.

Reference returns pending across `finally` need explicit named generated locals and declaration IDs. Local-variable metadata cannot establish the identity or lifetime of an anonymous compiler return spill.

## Validation and remaining decisions

Focused tests compile a standalone `Case` using the host compiler with the production release/debug options. They check wide constants and locals, reference arrays, overloaded methods, reused slots with separate ranges, absent debug tables, and abstract methods. Structural fixtures exercise all constant tags, metadata bounds, checked attribute lengths, truncation, malformed encodings, and resource ceilings. Synthetic fixtures are parser inputs, not claims of JVM-verifiable workloads.

Independent review identified a false duplicate rejection from concatenating a method name and descriptor: `m` with `()Lfoo()Lbar;` aliases `m()Lfoo` with `()Lbar;`. Classfile names permit parentheses, so these are distinct method identities despite having equal concatenations. The reader uses a structural name/descriptor pair; focused regressions cover both the distinct pair and a true duplicate.

Before production integration, decide how runtime class/loader and exact bytecode provenance are established, where host metadata is retained or reported without conflating it with native capability bits, and how malformed or unavailable retained metadata maps to extraction diagnostics. Current main-frame projection and future recursive frame correspondence require separate integration tests against actual raw roots. No driver, verifier, projector, native, or profile policy change belongs to this prototype.
