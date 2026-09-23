# Orchestrator Guidelines

Planning, coordination, and delivery rules for the orchestrator role — workflow phases, test
policy, verification scope, git/PR conventions, and documentation sync (may already be injected
automatically by the slate extension). Hands-on command references live in
`docs-internal/agents/thread-guidelines.md`.

## Development Workflow (Track-Based)

All changes follow `track-workflow.md` from the `ytdb-slate` npm package.
YTDB workflow deltas live in `docs-internal/dev-workflow/track-development.md`.

This flow covers **all files in the repository**, including `.pi/` configuration, prompts,
and docs — not only Java/product sources. There is no "harness tooling" exemption: editing a
prompt, config, or doc is a repository change and takes the same gates.

## Test Policy

- A code change carries tests for its new or changed behavior.
- A bug fix carries a regression test unless one already exists.
- New or changed code targets 85 percent line coverage and 70 percent branch coverage.

Test authorship details live in `docs-internal/agents/thread-guidelines.md`.
Coverage details live in `docs-internal/dev-workflow/coverage-verification.md`.

## Verification Gates

Verification rules live in `docs-internal/dev-workflow/track-development.md`.
Integration command syntax lives in `docs-internal/agents/thread-guidelines.md`.
Never run the full integration suite locally.
The pull request pipeline runs integration tests for changed IT modules.
Develop runs the full suite after merge.
`docs-internal/dev-workflow/track-development.md` owns integration scope.
If in doubt, run the full unit test suite.

### Serial Maven execution

Never run two Maven invocations concurrently in one worktree. Database locking and classloading
failures make concurrent runs unsafe. Execution details live in
`docs-internal/agents/thread-guidelines.md`.

## Git Conventions

### Branches
- `main` - Used for delivery of artifacts once all tests on `develop` have passed (auto-merged from develop nightly after integration tests pass)

### Commit Messages
- Commit-message format and rules live in `track-workflow.md` § Track intention block and
  implementer response and `blast-radius.md` § Commit discipline for drift and boundaries.
  Supply the intended message when dispatching a commit task. Never commit over a red result
  actually observed. Running tests mid-track is not required. See
  `docs-internal/dev-workflow/track-development.md` § Verification integration.

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- Before naming a branch, ask the user whether this change has a YTDB issue. Reuse an applicable
  prior answer. An issue number is optional.
- With an issue, start the branch name with the issue ID in the form `YTDB-<number>`, as in
  `YTDB-123-short-name`. CI adds the issue prefix to the PR title from the branch name.
  Without an issue, CI leaves the PR title unchanged.
- **Multiple issues**: when a PR addresses several issues, list them all in the title,
  comma-separated and wrapped in square brackets: `[YTDB-123, YTDB-456] <summary>`.
- Target branch: `develop`
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- Flip and merge rules live in `pr-publishing.md` from the `ytdb-slate` package.
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made.
- Pull request synchronization rules live in `pr-publishing.md` from the `ytdb-slate` package.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.
- **Integration test conditions**: Integration tests do not run for drafts or Markdown-only changes.
  A pull request runs integration tests only for reactor modules that contain both a changed `.java` file and integration test sources.
  It does not run dependent modules. Other changes do not start the integration job.
  If the changed-file list is unknown, the pull request runs the full suite.
  Manual runs stay full. Develop runs the full suite after merge.
  A merge queue orders approved pull requests for merging.
  A merge group temporarily combines changes GitHub tests before a merge queue writes them to the target branch.
  Merge groups do not rerun integration tests because the pull request ran its changed modules. Develop runs the full suite after merge.
- **Integration test marker**: The exact lowercase marker `[no-it-tests]` skips them on same-repository pull requests.
  It must appear in the first line of the head commit message.
  The marker has no effect in the pull request title.
  The marker comparison respects letter case.
  Use the marker only when the change cannot affect integration tests.
- **Fork workflow approval**: GitHub applies required fork approval before any workflow job starts.
  A fork workflow waits when its pull request author or event actor is an external contributor.
  JetBrains organisation members are not external contributors, including for pull requests from personal forks.
  Changing the repository approval setting can remove this control.
  A waiting run shows **Awaiting approval** until a maintainer with write access approves it.
  Every new push starts another workflow run, and GitHub evaluates approval for that run.
- **Fork gate failures**: Read the job log because fork workflows cannot write pull request comments.
- **Planned changes and Tracks sections**: The template rules live in `pr-publishing.md`.
  YTDB template deltas live in `docs-internal/dev-workflow/track-development.md`.
- The project review charter lives in `docs-internal/agents/review-perspectives.md`.
  Slate loads it through `reviewPerspectivesPath`.

### Rebase Conflict Resolution
- When a rebase produces conflicts in prose-heavy files (e.g., `AGENTS.md` or `docs-internal/adr/**`), re-read every resolved file end-to-end before continuing — three-way prose merges can splice text that parses but contradicts itself.
- The recheck covers the whole document, not just the conflict hunks — a clean hunk-level resolution can still leave unchanged paragraphs referencing rules that were renamed or removed on the other side.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
