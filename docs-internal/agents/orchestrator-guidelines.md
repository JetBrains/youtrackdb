# Orchestrator Guidelines

Planning, coordination, and delivery rules for the orchestrator role — workflow phases, test
policy, verification scope, git/PR conventions, and documentation sync (may already be injected
automatically by the slate extension). Hands-on command references live in
`docs-internal/agents/thread-guidelines.md`.

## Development Workflow (Track-Based)

All changes follow the track-based flow. The generic protocol ships with the `ytdb-slate`
npm package (pinned in `.pi/settings.json`) as two documents, cited by absolute path in the
orchestrator doctrine. track-workflow.md defines change classes, class-scaled design gates,
the research log, and the per-track loop. pr-publishing.md defines the umbrella draft PR,
description rules, the ready-for-review flip, and the user-performed merge.
YTDB deltas — the `develop` base branch, issue-prefix/PR-template conventions, the
umbrella-PR peer-review policy, the action-level model-routing setup, and the package
pin-bump rule — live in `docs-internal/dev-workflow/track-development.md`.

This flow covers **all files in the repository**, including `.pi/` configuration, prompts,
and docs — not only Java/product sources. There is no "harness tooling" exemption: editing a
prompt, config, or doc is a repository change and takes the same gates.

## Test Policy

- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code.

How to run and diagnose coverage verification is defined in
`docs-internal/dev-workflow/coverage-verification.md`.

## Verification Gates

Mid-track commits run `./mvnw -pl <modules with changed files> -am -amd test-compile` over the
compile-gate set. Every change class runs full verification after track implementation. It
precedes agent code review when that review is required. Verification covers the test-gate set,
the integration-gate set, and the 85% line and 70% branch coverage thresholds. The complete
protocol is in `docs-internal/dev-workflow/track-development.md` § Verification integration.

Dispatch the integration-gate set defined in that protocol. The full integration suite is no
longer a local gate. The pull request pipeline runs it instead. Follow
`docs-internal/agents/thread-guidelines.md` for command syntax.
If in doubt, run the full unit test suite.

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
- Commit-message format and rules live in `docs-internal/agents/thread-guidelines.md`
  § Committing — workers execute the commits, so supply the intended message (or point at that
  section) when dispatching a commit task. Never commit over a red result actually observed;
  running tests mid-track is not required. See
  `docs-internal/dev-workflow/track-development.md` § Verification integration.

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- **Multiple issues**: when a PR addresses several issues, list them all in the title, comma-separated and wrapped in square brackets: `[YTDB-123, YTDB-456] <summary>`.
- Target branch: `develop`
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- **Merge is user-performed** — the agent never merges the umbrella PR; the pre-flip
  checklist and flip mechanics are owned by pr-publishing.md (ytdb-slate package)
  § Ready-for-review flip.
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made.
- **Keep the PR title and description in sync with follow-up commits** — the squash-merge builds the commit message from them, not from individual commit messages, so stale text ships to `develop`'s history; sync rules owned by pr-publishing.md § Keeping the PR in sync.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.
- **Integration test bypass**: Add `[no-it-tests]` to the pull request title to skip the pipeline's integration tests. Use this only when the change cannot affect integration tests. Pull requests whose branches live in forks do not run integration tests.
- **Planned changes & Tracks sections**: The PR template includes "Planned changes" and "Tracks" sections, mandatory for non-trivial changes. The umbrella draft PR's description is kept in sync as work proceeds — description rules in pr-publishing.md (ytdb-slate package); YTDB template deltas in `docs-internal/dev-workflow/track-development.md`.
- **Peer review** is optional and, when the user wants it, runs directly on the ready umbrella PR at the ready-for-review flip — no separate review branches or PRs are created; see `docs-internal/agents/slate-doctrine-extra.md`.

### Rebase Conflict Resolution
- When a rebase produces conflicts in prose-heavy files (e.g., `AGENTS.md` or `docs-internal/adr/**`), re-read every resolved file end-to-end before continuing — three-way prose merges can splice text that parses but contradicts itself.
- The recheck covers the whole document, not just the conflict hunks — a clean hunk-level resolution can still leave unchanged paragraphs referencing rules that were renamed or removed on the other side.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
