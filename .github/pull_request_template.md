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

#### Integration test run conditions:

- Integration tests do not run for a draft pull request or when every changed file is a Markdown file.
- A merge queue orders approved pull requests for merging.
- A merge group temporarily combines changes GitHub tests before a merge queue writes them to the target branch.
- Merge groups do not rerun integration tests because the pull request head already ran the full suite.
- The exact lowercase marker `[no-it-tests]` skips integration tests when it appears in the first line of the head commit message.
- The `[no-it-tests]` marker has no effect in the pull request title.
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
<!-- Required for every change. Write it when creating the draft PR. Update it as the change
progresses. Before the PR becomes ready for review, make it match the delivered result.
Use a high design level and the main domain entities. Do not include file paths or method
signatures. Include only subsections with content: Current state, What changes, How,
Key decisions, Out of scope, Risks & accepted trade-offs, Ignored findings,
Delivery accounting, and Verification approach. See the ytdb-slate package's
pr-publishing.md and docs-internal/dev-workflow/track-development.md. -->

##### Ignored findings:
<!-- List each ignored finding with its identifier, location, and one-line summary.
Remove this subsection if no finding was ignored. -->

##### Delivery accounting:
<!-- Copy the required conclusions from the research log before each package.
See the ytdb-slate package's delivery-packages.md. -->

#### Tracks:
<!-- Multi-track changes only. This table is a display index, not a boundary authority.
Umbrella mode uses marker commits. Per-track mode uses reviewed branch ranges and
user-merged commits. Write "N/A (single-track)" otherwise. Remove this whole section
before the PR becomes ready for review. -->

| # | Track | Scope | Status |
|---|-------|-------|--------|

