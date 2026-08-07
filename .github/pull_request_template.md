#### PR Title:

If this PR is related to an issue, prefix the title with the issue number (e.g., `YTDB-123: Imperative summary under 50 chars`).

Integration tests run once a pull request from a branch in this repository is ready for review.
Pull requests from forks do not run integration tests.
A maintainer runs the integration tests before merging.
Add `[no-it-tests]` to the title to skip that run.
Use the tag only when the change cannot affect integration tests.
A pull request that changes only Markdown files skips the run automatically, so a documentation-only change needs no tag.

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

