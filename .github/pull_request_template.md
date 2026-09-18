#### PR Title:

If this PR is related to an issue, prefix the title with the issue number (e.g., `YTDB-123: Imperative summary under 50 chars`).

One title tag changes which checks run:

- `[no-test-number-check]` skips the test count gate. Use it only for an intentional test refactoring that does not reduce coverage.

Manual comparison benchmark support:

A pull request head is its captured commit.
A SHA is a full 40-character commit identifier.
An attestation is a signed statement that identifies the trusted validator.

- Dispatch **LDBC JMH Benchmark Compare** from `develop`.
- Provide exactly one pull request number or one same-repository commit SHA.
- Choose 8 dedicated vCPUs by default or 32 dedicated vCPUs for the larger profile.
- A dedicated vCPU is a reserved processor thread, not proof of a physical CPU core.
- The comparison report records the requested profile and fixed provider label.
- A fork head needs an approving maintainer review for that exact commit before execution.
- SHA comparisons use the first parent and never publish pull request completion.
- Filters accept comma-separated query identifiers without regular expressions.
- Full pull request runs publish signed completion in a pull request comment.
- Performance regressions remain informational for human review.
- Automatic core Java enforcement and `[no-benchmarks]` support remain deferred.

Integration test run conditions:

- Integration tests do not run for a draft pull request or when every changed file is a Markdown file.
- A merge queue orders approved pull requests for merging.
- A merge group temporarily combines changes GitHub tests before a merge queue writes them to the target branch.
- Merge groups do not rerun integration tests because the pull request head already ran the full suite.
- The exact lowercase marker `[no-it-tests]` skips integration tests when it appears in the first line of the head commit message.
- The marker comparison respects letter case and works only for a pull request from the same repository.
- A fork workflow waits when its pull request author or event actor is an external contributor.
- JetBrains organisation members are not external contributors, including when they open pull requests from personal forks.
- Changing the repository approval setting can remove this control.
- The pull request page shows **Awaiting approval** until a maintainer with write access approves a waiting workflow run.
- Every new push starts another workflow run, and GitHub evaluates approval for that run.
- Fork gate failures provide details in the job log because fork workflows cannot write pull request comments.

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
trade-offs · Suggestions · Verification approach.
Guidance: pr-publishing.md (shipped with the ytdb-slate package) for the writing rules;
docs-internal/dev-workflow/track-development.md for YTDB deltas. -->

##### Suggestions:
<!-- MANDATORY. Add one line per suggestion with its identifier, location, and summary.
Write "None." when no suggestions remain. Put the standalone text in the final user report.
When workflow.followUpIssues enables its prompt, a tracker issue may hold that text instead. -->

#### Tracks:
<!-- Multi-track changes only — display index; the source of truth is the marker commits
(`git log --oneline --grep '^Track [0-9]* complete:'`). Write "N/A (single-track)" otherwise.
Branch-life only: this table is stripped from the description before the PR is flipped ready
for review. -->

| # | Track | Scope | Status |
|---|-------|-------|--------|

