#### PR Title:

If this PR is related to an issue, prefix the title with the issue number (e.g., `YTDB-123: Imperative summary under 50 chars`).

Two title tags change which checks run:

- `[no-it-tests]` skips the integration test run. Use it only when the change cannot affect integration tests.
- `[no-test-number-check]` skips the test count gate. Use it only for an intentional test refactoring that does not reduce coverage.

Integration test run conditions:

- Integration tests run unless the pull request is a draft.
- Integration tests do not run when the pull request branch lives in a fork.
- Integration tests do not run when every changed file is a Markdown file.

#### Motivation:

Explain WHY this change was made — the problem, context, and trade-offs.
Not a restatement of the diff. This section is **MANDATORY**.

#### Planned changes:
<!-- MANDATORY for non-trivial changes. Written when the draft PR is created (before
implementation); updated as reality diverges; brought to the final, as-implemented state
before the PR is flipped ready for review. High design level using the main domain
entities from the code — no file paths, no method signatures. Include the subsections that
apply: Current state · What changes (contract/behavior) · How (design level) ·
Key decisions (chosen vs rejected alternatives) · Out of scope · Risks & accepted
trade-offs · Verification approach. For trivial changes a 2–3 sentence paragraph suffices.
Guidance: pr-publishing.md (shipped with the ytdb-slate package) for the writing rules;
docs-internal/dev-workflow/track-development.md for YTDB deltas. -->

#### Tracks:
<!-- Multi-track changes only — display index; the source of truth is the marker commits
(`git log --oneline --grep '^Track [0-9]* complete:'`). Write "N/A (single-track)" otherwise.
Branch-life only: this table is stripped from the description before the PR is flipped ready
for review. -->

| # | Track | Scope | Status |
|---|-------|-------|--------|

