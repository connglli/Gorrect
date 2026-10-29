# Gorrect

Garbage collector validation through Object Graph Verification.

## Project overview

Gorrect builds an object graph, generates a sequence of mutations, and computes the expected graph independently. It translates the workload into a Java program, runs that program on a selected JVM and collector, extracts the observed graph, and checks whether the observation satisfies the oracle.

HotSpot and its commonly used collectors are the first priority; OpenJ9 is the second. Adding a JVM should require extending the VM integration, without changing the portable graph semantics or weakening validation.

## Principles and best practices

These practices adapt [Ponytail's engineering guidance](https://github.com/DietrichGebert/ponytail/blob/main/AGENTS.md) to Gorrect.

### Make the smallest complete change

Understand the affected code and its callers, tests, fixtures, configuration, and public interfaces before editing. Complete the requested behavior across those boundaries.

Prefer an existing project facility, then a standard library or platform facility, then an installed dependency. Add code only where these do not meet the requirement. Avoid adding a dependency for a small helper.

Keep the core small and explicit. Add abstractions, options, and extension points when a concrete requirement needs them. Favor straightforward code over a shorter expression that takes effort to decode.

Keep the initial GIR and artifact formats at version 1 while their contracts are being developed.

Preserve validation and error handling when moving or simplifying code. Fix a shared cause once, after inspecting its callers. Choose the approach that handles edge cases correctly.

Explain reasoning the code cannot express in concise comments. For a deliberate shortcut, record its limit and the condition that would justify replacing it.

Check nontrivial logic with a focused test or self-check. Trivial changes need no artificial tests. Report relevant checks, omissions, and material limitations at handoff.

### Keep validation trustworthy

Keep the graph model, oracle, workload generation, and JVM integration conceptually separate. Collector policies and object-layout estimates must not silently become semantic assumptions.

Treat a mismatch as evidence to investigate. Distinguish a validation failure from an invalid workload, unsupported VM capability, extraction failure, crash, or timeout. Preserve the information needed to reproduce and diagnose a result.

Make state changes and their failure behavior explicit. A rejected operation must not leave the model or execution bookkeeping partially updated unless that behavior is part of its documented contract.

### Keep the project maintainable

Use Conventional Commits for small, reviewable changes. Keep [README.md](./README.md), the relevant documents, and this file consistent. Keep machine-specific paths in untracked local configuration; committed files use portable paths or explicit placeholders.

Resolve compiler warnings in code you introduce or change. Keep functions focused, control flow shallow, and module boundaries easy to explain. Reuse shared logic where it clarifies the model; avoid speculative generalization.

Follow [docs/AGENTS.md](./docs/AGENTS.md). Keep change history in commits and keep any changelog concise.

Use [agent notes](./.agents/notes/README.md) and follow their [conventions](./docs/AGENTS.md#agent-notes).

Before extending the design, ask whether the mechanism is necessary, whether a simpler one satisfies the contract, and whether its behavior can be analyzed and tested independently.

## Before starting work

1. Read recent history with `git log` and inspect relevant changes with `git show` or `git diff`.
2. Read the applicable guidance and understand the existing decisions before changing behavior.
3. Check the working tree and preserve unrelated user changes.
4. For substantial work, agree on the design and divide implementation into reviewable steps.

## Before handing off changes

1. Run the formatting, compiler, and test checks relevant to the change. Report failures and checks that could not run.
2. Run the existing pre-commit hooks on changed files. Preserve both the `pre-commit` and `commit-msg` hook stages and their configuration.
3. Review the diff and `git status` for unintended changes and generated artifacts.
4. Stage all changes and ask the user to review changes before committing, unless the user has already authorized the commit in the session.
5. Use a Conventional Commit message with a component scope, such as `feat(engine)` or `fix(runtime)`, a title of at most 50 characters, and a body that explains intent and design impact when needed. Follow [commit attribution](#commit-attribution) for agent co-author trailers.

## Commit attribution

Every commit created or amended by an AI agent must include a `Co-Authored-By:` trailer for that agent. Include a separate trailer for each contributing AI agent, using its known model or product name and attribution email. Preserve existing co-author trailers when amending or squashing commits.

Examples of the trailer format:

```text
Co-Authored-By: GPT 6.1 Sol <codex@openai.com>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

List only agents that contributed to the change.

## Hook tooling

```bash
python -m pip install -r requirements.dev.txt
pre-commit install --install-hooks
pre-commit run --all-files
```
