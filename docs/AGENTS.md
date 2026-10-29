# Documentation standard

This file governs the prose that stays: documents, design comments, and commit messages. [AGENTS.md](../AGENTS.md) owns the engineering rules; this file owns the writing.

## Where a fact belongs

Give each fact one authoritative home and link to it elsewhere.

* [design.md](./design.md) holds the conceptual model and validation workflow, including reference handling, concurrency, JIT stress, and the JVM integration boundary.
* [gir.md](./gir.md) defines GIR syntax and shared execution and oracle rules; [Instruction.java](../engine/src/main/java/io/connglli/gorrect/engine/gir/Instruction.java) defines each instruction's contract.
* [implementation.md](./implementation.md) holds component and language boundaries, the build layout, runtime and observer responsibilities, JVM integrations, protocols, and run artifacts.
* [README.md](../README.md) introduces the project and directs readers to its documentation and working entry points.
* [AGENTS.md](../AGENTS.md) holds the project context, engineering practices, and contributor workflow.
* [.pre-commit-config.yaml](../.pre-commit-config.yaml) and [requirements.dev.txt](../requirements.dev.txt) define the hook tooling; [.clang-format](../.clang-format) defines native formatting style. Documentation should point to these files rather than repeat their settings, versions, or hook inventory.
* Source and tests hold implementation details, executable contracts, and invariants. Comments explain decisions that the code cannot express.

As design documents are added, separate the conceptual model and semantic contracts from implementation mechanics such as module layout, VM tooling, process protocols, and artifact formats. Record which document owns each subject. Link only to files that exist.

Keep temporary explorations and implementation plans separate from durable specifications. Change history belongs in commit messages; durable documents describe the agreed contract and the implementation that exists. Label unresolved design proposals explicitly so readers can distinguish them from decisions.

## Agent notes

Keep working notes under [`.agents/notes/`](../.agents/notes/README.md): design explorations and tradeoffs in [designs/](../.agents/notes/designs/), implementation plans in [implementations/](../.agents/notes/implementations/), and change-review records in [commits/](../.agents/notes/commits/).

Name each note `YYYY-MM-DD-<short-title>.md`. Prefix the filename with `completed-` when its work is finished, or `deprecated-` when it has been superseded or abandoned. Keep the original date and title after the prefix.

Notes may contain alternatives, investigation history, and unresolved questions. They support collaboration and do not establish a contract by themselves. Incorporate accepted decisions into the document that owns the subject and link to that document from the note.

## Writing rules

Write for a JVM or garbage collector engineer who has not read the code. Use plain English and precise names. Define project-specific terms before relying on them.

State the contract: what holds, under which conditions, what can vary, and what happens when the conditions do not hold. For graph validation, name the observation point and distinguish guarantees from collector choices and harness assumptions.

Describe the mechanism directly. Avoid narrating edits with phrases such as "previously", "now", or "no longer", and avoid using a branch, commit, or review conversation as the specification.

Give each paragraph one subject. Emphasis should identify a meaningful term or condition. Explain the source and conditions of measurements, thresholds, and numerical claims.

Keep prose concise by removing repetition and moving details to the document that owns them. Preserve the conditions needed to interpret a rule correctly.

## Natural prose

Use direct sentences, concrete nouns, and active verbs. Repeat the same technical name rather than introducing synonyms for variety.

Avoid promotional language, vague attribution, rhetorical contrasts, dangling commentary, and adjectives added for rhythm. Prefer "is" to "serves as" when describing a definition. End on the last useful fact rather than adding a speculative closing paragraph.

## Formatting

Use one physical line per prose paragraph and editor soft-wrap. Code blocks, tables, lists, and source comments keep the formatting their syntax or tooling requires.

Use sentence case headings, plain `*` bullets, straight quotes, and straight apostrophes. Use emphasis and em dashes sparingly. Emoji belong only in the top-level README when they help the reader.

A short bold label followed by a colon may introduce a list item. Use tables for genuinely tabular information.

Code blocks must be accurate, name their language, and state any required setup. Java examples, JVM flags, graph operations, and shell commands must match their documented contracts. Mark pseudocode explicitly and do not present an untested command as verified.

Use relative links that resolve. Prefer linking to the authoritative document or configuration over repeating its contents.

## Review checklist

Search touched prose for temporal narration, repeated rules, excessive emphasis, and numerical claims without provenance. Then read the diff for problems that a search cannot detect.

* Keep a rule in one home and link to it elsewhere.
* Move change stories and abandoned approaches out of durable specifications.
* Distinguish a proposal, a supported behavior, and an experimentally verified result.
* Avoid hand-maintained inventories that merely duplicate the source tree or configuration.
* Keep derivations, alternatives, and investigation transcripts out of contract descriptions unless they are needed to understand a tradeoff.
* Explain shared rationale once at the shared mechanism.
* Split paragraphs that contain independent rules or multiple subjects.
* Remove decorative emphasis and numbers that lack a stated source or measurement.

## Quality and length

For documentation, comments, and other prose, prioritize rewriting existing text over appending new text. Integrate changes into the relevant passage and remove repetition, outdated explanations, and unnecessary detail.

Treat length as part of quality. Keep text focused and short enough that users will read it, while preserving the conditions and detail needed for accuracy.
