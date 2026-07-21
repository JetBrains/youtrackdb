# Orchestrator Guidelines

Planning, coordination, and delivery rules for the orchestrator role — workflow phases, test
policy, verification scope, git/PR conventions, and documentation sync (may already be injected
automatically by the slate extension). Hands-on command references live in
`docs-internal/agents/thread-guidelines.md`.

## Development Workflow (Track-Based)

All changes follow the track-based flow. The generic protocol ships with the `ytdb-slate`
npm package (pinned in `.pi/settings.json`) as two documents, cited by absolute path in the
orchestrator doctrine: track-workflow.md — research, lazy research log, mandatory user
design review, mandatory pre-implementation adversarial review, the per-track loop (agent
code review → mandatory user review → marker commit), and the change-size scaling table —
and pr-publishing.md — umbrella draft PR before implementation, description rules,
ready-for-review flip, user-performed merge (draft-PR publishing is enabled for this repo).
YTDB deltas — the `develop` base branch, issue-prefix/PR-template conventions, the
satellite peer-review layer, and the package pin-bump rule — live in
`docs-internal/dev-workflow/track-development.md`.

This flow covers **all files in the repository**, including `.pi/` configuration, prompts,
and docs — not only Java/product sources. There is no "harness tooling" exemption: editing a
prompt, config, or doc is a repository change and takes the same gates.

## Test Policy

- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code (enforced by CI coverage gate).
- **Coverage verification**: Always check coverage with the `.github/scripts/coverage-gate.py` script instead of computing it by hand — the exact invocation and the reason manual arithmetic gives wrong results are in `docs-internal/agents/thread-guidelines.md`.

## Pre-Commit Verification

**Every task MUST be verified before committing.** The decision rules are below; the exact
`./mvnw` command lines are in `docs-internal/agents/thread-guidelines.md`.

1. **Run unit tests** for the affected module(s) before committing. If unsure which modules are
   affected, run the full unit test suite.
2. **Run related integration tests** (`-P ci-integration-tests`) if the change touches areas
   covered by integration tests.
3. **Check coverage of changed code** by running tests with the `coverage` profile and verifying
   coverage locally with `.github/scripts/coverage-gate.py` against the thresholds in
   [Test Policy](#test-policy) above. If coverage is below the threshold, add or improve tests
   for uncovered lines before committing.
4. **Do not commit if tests fail.** Fix the failures first, then re-run the tests.
5. **Determining which tests to run:**
   - Changes to `core` module: always run the `core` unit tests
   - Changes to `server` module: run the `server` unit tests
   - Changes to storage, WAL, or index code: also run integration tests
   - Changes to Gremlin integration or transaction handling: also run integration tests
   - Changes to `embedded` module: run the `embedded` unit tests (includes Cucumber feature tests)
   - Changes to `tests` module: run the `tests` module unit tests
   - If in doubt, run the full unit test suite

### Serial Test Execution (Scheduling Invariant)

**Never dispatch two test runs concurrently in the same worktree/directory.** Wait for one
worker's `./mvnw test` or `./mvnw verify` invocation to finish before dispatching another in
the same working directory. Parallel runs in the same worktree cause classloading errors,
database file locking conflicts, and false test failures. This applies to all test execution —
unit tests, integration tests, and coverage runs. Runs in separate worktrees/directories do not
conflict. The rule extends to any concurrent Maven invocations in the same worktree — never
dispatch a build there while another build or test run is in progress.

## Git Conventions

### Branches
- **`develop` is the default development branch** for this project, not `main`.
- `main` - Used for delivery of artifacts once all tests on `develop` have passed (auto-merged from develop nightly after integration tests pass)

### Commit Messages
- Commit-message format and rules live in `docs-internal/agents/thread-guidelines.md` § Committing — workers execute the commits, so supply the intended message (or point at that section) when dispatching a commit task.

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- **Multiple issues**: when a PR addresses several issues, list them all in the title, comma-separated and wrapped in square brackets: `[YTDB-123, YTDB-456] <summary>`.
- Target branch: `develop` (exception: satellite review PRs target their pinned `track-NN-base`
  branch)
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- **Merge is user-performed** — the agent never merges the umbrella PR; the pre-flip
  checklist and flip mechanics are owned by pr-publishing.md (ytdb-slate package)
  § Ready-for-review flip.
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made. Satellite review PRs are exempt — they carry a track-summary body instead (see the satellite bullet below).
- **Keep the PR title and description in sync with follow-up commits** — the squash-merge builds the commit message from them, not from individual commit messages, so stale text ships to `develop`'s history; sync rules owned by pr-publishing.md § Keeping the PR in sync.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.
- **Planned changes & Tracks sections**: The PR template includes "Planned changes" and "Tracks" sections, mandatory for non-trivial changes. The umbrella draft PR's description is kept in sync as work proceeds — description rules in pr-publishing.md (ytdb-slate package); YTDB template deltas in `docs-internal/dev-workflow/track-development.md`.
- **Satellite review PRs** are draft-only review vehicles for individual tracks (multi-track changes only): they are never merged and never marked ready for review — see `docs-internal/dev-workflow/satellite-pr.md`.

### Rebase Conflict Resolution
- When a rebase produces conflicts in prose-heavy files (e.g., `AGENTS.md` or `docs-internal/adr/**`), re-read every resolved file end-to-end before continuing — three-way prose merges can splice text that parses but contradicts itself.
- The recheck covers the whole document, not just the conflict hunks — a clean hunk-level resolution can still leave unchanged paragraphs referencing rules that were renamed or removed on the other side.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
3. **When changing code covered by design-decision JavaDoc**: the covering entries (method/class/package-info level, per `docs-internal/dev-workflow/design-decisions.md`) must be updated in the same change.
