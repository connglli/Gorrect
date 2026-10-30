---
name: critical-review
description: Pre-commit critical code review.
---

# Critical Review

Before staging and committing the code, run this skill to perform a critical review of code changes.

## Requirements

Review all code changes, including that in the working directory and in the index.

Reflect yourself. Think carefully and critically. Think twice before making any decision. Don't overthink.

Fix suttle issues immediately. Don't let them accumulate. Don't stage or commit them. Leave your fix in the working directory.

Speak simple English. Avoid complex technical jargon or coined terms. Follow <project>/docs/AGENTS.md to speak.

### If this is a bugfix

Check for potential regressions.

Test with more edge cases.

Don't overfit the specific issue.

Test for generality.

Follow DRY and KISS principles.

### If this is a feature or refactor

Rethink about the design.

Does the design follow KISS, SINE, and YAGNI principles?

Follow the DRY principle.

### If this a documentation change or cleanup

Does the documentation introduce new, unexplained terms?

Does the documentation follow the style and format of existing documents?

Read <project>/docs/AGENTS.md to check requirements for writing comments and docs.

Don't blow up the documentation by appending text everywhere. Quality and length need control. Keep simple and concise.

## Output

After reviewing the code, output a summary of the review, including:

+ a short overview,
+ a table listing any subtle issues fixed during the review process
+ a table listing any issues found, their severity, and suggested fixes, ranked by severity.

If no issues are found, output a message indicating that the code is ready for commit.

## Checklist

+ [ ] Review the code and documentation for potential bugs and issues.
+ [ ] Check for adherence to AGENTS.md, especially regarding KISS/DRY/... principles.
+ [ ] Check for adherence to docs/AGENTS.md, especially regarding writing better comments and documents.
