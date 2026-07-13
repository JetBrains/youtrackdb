# Orchestrator Guidelines

Planning, coordination, and delivery rules for the orchestrator role — workflow phases, test
policy, verification scope, git/PR conventions, and documentation sync (may already be injected
automatically by the slate extension). Hands-on command references live in
`docs/agents/thread-guidelines.md`.

## Development Workflow (Track-Based)

All changes follow the track-based flow — full protocol in `docs/dev-workflow/track-development.md`:

This flow covers **all files in the repository**, including `.pi/` tooling and slate extension code — not only Java/product sources. There is no "harness tooling" exemption: editing an extension, prompt, or doc is a repository change and takes the same gates.

1. **Research first** — interactive code exploration before implementation; a research log is
   opened lazily when complexity triggers fire (non-trivial decisions, surprises, risky
   invariants, session boundaries — see the protocol doc).
2. **User design review** — mandatory before adversarial review: the agent presents the design
   to the user and loops on feedback until explicit approval, recorded as a durable verdict
   line — see the protocol doc.
3. **Adversarial review** — mandatory before implementation for every change; a fresh-context
   reviewer attacks the user-approved design — the research log (or the draft Planned-changes
   statement when no log exists); scaled down to a micro-review for trivial changes.
4. **Umbrella draft PR** — created before implementation starts, with a design-level
   description (Planned changes + Tracks sections per the PR template). The user approves the
   proposed track split before coding begins.
5. **Track-based implementation (mandatory)** — multi-file changes are split into tracks
   (independently reviewable units, ~12–25 files); development is linear on one branch; each
   track ends with a mandatory agent code review of the track diff, then a mandatory user
   review — the orchestrator waits for explicit user approval — then an empty marker commit
   `Track NN complete: <short name>` (the durable track-boundary record).
6. **Satellite review PRs (optional, user-gated)** — after each track's user approval and
   marker commit the agent asks whether to open a draft satellite PR for review by separate
   peer reviewers; mechanics in `docs/dev-workflow/satellite-pr.md`. Satellites are
   review-only: always draft, never merged. Once opened, the track blocks the next one until
   the peer review is complete or the user explicitly waives completion. Satellites are
   multi-track only — for single-track and trivial changes the optional peer review runs on
   the umbrella PR after the ready-for-review flip.

Single-track changes skip the split and markers; trivial changes (typos, doc-only, mechanical
renames) collapse the design review into consent to the planned-changes paragraph and may also
skip the full adversarial review — see the protocol doc's scaling table. The mandatory user
review gate applies at every tier: for single-track and trivial changes it gates the
ready-for-review flip.

## Test Policy

- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code (enforced by CI coverage gate).
- **Coverage verification**: Always check coverage with the `.github/scripts/coverage-gate.py` script instead of computing it by hand — the exact invocation and the reason manual arithmetic gives wrong results are in `docs/agents/thread-guidelines.md`.

## Pre-Commit Verification

**Every task MUST be verified before committing.** The decision rules are below; the exact
`./mvnw` command lines are in `docs/agents/thread-guidelines.md`.

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
- Commit-message format and rules live in `docs/agents/thread-guidelines.md` § Committing — workers execute the commits, so supply the intended message (or point at that section) when dispatching a commit task.

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- **Multiple issues**: when a PR addresses several issues, list them all in the title, comma-separated and wrapped in square brackets: `[YTDB-123, YTDB-456] <summary>`.
- Target branch: `develop` (exception: satellite review PRs target their pinned `track-NN-base`
  branch)
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- **Merge is user-performed** — the agent never merges the umbrella PR; it flips the PR to
  ready-for-review after the pre-flip checklist and hands it to the user — see
  `docs/dev-workflow/track-development.md` § Ready-for-review flip, merge & cleanup.
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made. Satellite review PRs are exempt — they carry a track-summary body instead (see the satellite bullet below).
- **Keep the PR title and description in sync with follow-up commits.** The squashed commit message is built from the PR title and description, not from individual commit messages — update them with every push so the merge commit reflects all changes.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.
- **Planned changes & Tracks sections**: The PR template includes "Planned changes" and "Tracks" sections, mandatory for non-trivial changes. The umbrella draft PR's description is kept in sync as work proceeds — see `docs/dev-workflow/track-development.md`.
- **Satellite review PRs** are draft-only review vehicles for individual tracks (multi-track changes only — see workflow step 6 above): they are never merged and never marked ready for review — see `docs/dev-workflow/satellite-pr.md`.

### Rebase Conflict Resolution
- When a rebase produces conflicts in prose-heavy files (e.g., `AGENTS.md` or `docs/adr/**`), re-read every resolved file end-to-end before continuing — three-way prose merges can splice text that parses but contradicts itself.
- The recheck covers the whole document, not just the conflict hunks — a clean hunk-level resolution can still leave unchanged paragraphs referencing rules that were renamed or removed on the other side.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
