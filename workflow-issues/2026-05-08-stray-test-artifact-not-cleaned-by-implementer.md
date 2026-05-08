---
severity: low
phase: phase-b
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Implementer leaves stray test-generated files in worktree before declaring SUCCESS

## Symptom

During Phase B Step 4 of Track 19 (Storage Fundamentals — collections +
ridbag), the implementer ran `PaginatedCollectionV2OptimisticReadTest` (a
DbTestBase subclass that writes to disk during `close()+reopen` exercises)
and the test produced a 1.4 MB binary file at
`core/localPaginatedCollectionTestV2`. The test passed, but its
clean-up did not remove the on-disk artifact (the host class lacked an
`@After` for it). After the implementer committed the test additions and
returned `RESULT: SUCCESS`, `git status` showed:

```
On branch unit-test-coverage
Untracked files:
  core/localPaginatedCollectionTestV2
nothing added to commit but untracked files present
```

The orchestrator caught the artifact (manual `ls` after the implementer's
return) and removed it before spawning Step 5. Step 5's implementer
incidentally added the missing `@After` cleanup to `AsyncFileTest` — but
that fix is specific to one test class; the rulebook does not require
implementers to verify the worktree is clean before returning SUCCESS.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved: `.claude/workflow/implementer-rules.md`
  § Return contract; `.claude/workflow/step-implementation.md` § Phase B
  Startup item 3 (orphan-commit detection)
- Tool / sub-agent involved: `general-purpose` sub-agent with
  `model: "sonnet"` (low-risk step model selection)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase B step that runs an existing test or adds a
  new test that generates on-disk files (paginated collections, B-tree
  storage scratch dirs, AsyncFile temp files, WAL segments). The
  surefire JVM exits before its `@After` cleanup runs to completion in
  some failure paths, or the host test class lacks `@After` cleanup
  entirely (the `core/localPaginatedCollectionTestV2` case).

## Why it's a problem

Three downstream costs:

1. **Confuses the orphan-commit detector at next Phase B Startup.** Step 3 of
   the startup protocol runs `git log --oneline {base_commit}..HEAD` and
   reads the tip's commit message — that's clean. But the working tree
   has been polluted, and a future `git reset --hard HEAD` (used by the
   implementer's revert path on contract failure) preserves the stray
   file, leaving the next implementer with a dirty cwd it didn't author.
2. **Breaks reproducibility of the next test run.** The artifact name
   `localPaginatedCollectionTestV2` collides with what
   `PaginatedCollectionV2OptimisticReadTest` writes the next time it
   runs; depending on the file's state (zero-length stub vs. full
   1.4 MB) the test may pass on a fresh checkout but fail on a
   carry-over checkout, or vice versa. This is exactly the "works on my
   machine" failure mode that test cleanliness rules try to prevent.
3. **Steals turn budget from the orchestrator.** Each Phase B step that
   the implementer didn't clean up costs the orchestrator a `git status`
   inspection plus a manual `rm` (and an investigation into whether the
   stray file is part of the diff). With four steps in Track 19, that's
   a recurring tax.

## Proposed fix

Add a clean-tree check to the implementer's sub-step 3 (commit) closeout:

(a) **In `implementer-rules.md` § sub-step 3 (commit)**, add a step
"verify the worktree is clean after commit" — run `git status` and
require zero untracked files in the diff target's parent directories.
If untracked files exist, the implementer must either (i) include them
in the commit (intentional), (ii) gitignore them and document the rule
(intentional but excluded), or (iii) delete them with a brief reason
("test artifact leaked from `<host class>`; reported as a finding") in
the EPISODE_DRAFT's `what_was_discovered`.

(b) **A complementary structural fix**: extend the `core` module's
`.gitignore` to exclude well-known test-scratch path prefixes
(`core/localPaginatedCollectionTest*`, `core/buildDirectory/*`,
`core/.tmp_test_*`). This stops the artifacts from showing up as
untracked in the first place. Pair with a `surefire`-managed
`buildDirectory` system property so all tests use a single
ignored sub-tree.

(c) **Document at the host-class level** that DbTestBase subclasses
producing on-disk state are responsible for `@After` cleanup. Add a
`@Before` / `@After` pattern reminder to `DbTestBase`'s class-level
Javadoc, since the precedent classes in `core/storage/collection/v2`
and `core/storage/ridbag` don't all follow the convention.

Options (a) and (b) are independent — both are worth doing.

## Acceptance criteria

- `implementer-rules.md` describes the clean-tree expectation explicitly.
- A Phase B step that produces an untracked file at commit time either
  fails the implementer's return contract (option a — orchestrator can
  detect from FILES_TOUCHED diff vs. `git status`) or never appears
  (option b — covered by `.gitignore`).
- The `2026-05-08` repro case (`core/localPaginatedCollectionTest*`) is
  on `.gitignore` with a comment naming the test class.
- A regression check: `git ls-files --others --exclude-standard` in `core/`
  returns empty after a clean test run.
