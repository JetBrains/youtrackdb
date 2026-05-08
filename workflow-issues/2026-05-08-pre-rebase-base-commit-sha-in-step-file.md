---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Pre-rebase base-commit SHA in step file desyncs Phase C diff scope

## Symptom

The Track 19 step file's `## Base commit` section recorded
`141f874b6b` — a SHA that existed at the time `Record Phase B base
commit for Track 19` was committed, but a subsequent rebase produced
a duplicate-named commit `ea9e82198f` (same author timestamp, same
message, same content, different commit timestamp) on the actual
branch path. `git diff 141f874b6b..HEAD` returned 305 commits / 39
files / 5,836 insertions — the wrong scope by an order of magnitude.
The actual on-branch parent of the first Track-19 commit is
`ea9e82198f`, giving 28 files / 5,169 insertions (correct Track 19
shape). The orchestrator detected the mismatch only by inspecting the
duplicate-named "Self-improvement reflection from phase-a of
unit-test-coverage" commits via `git log --pretty=fuller` and
recognising the `CommitDate` divergence (Thu May 7 16:44 vs Fri May 8
05:24) — a signal that wouldn't always be available.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § Phase C Startup, step 1
    (`Read the step file's ## Base commit section`)
  - `.claude/workflow/conventions-execution.md` § Step file content
    (`## Base commit` description)
  - `.claude/workflow/step-implementation.md` § Phase B Startup, step 1
    (where the SHA is recorded)
- Tool / sub-agent involved: orchestrator git tooling (Bash `git diff`,
  `git log`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C session for a track whose Phase B was
  followed by a rebase / force-push that rewrote the commit chain. The
  recorded SHA still resolves (rebases preserve unreachable commits in
  the local reflog) but no longer represents an ancestor of HEAD.

## Why it's a problem

A wrong base SHA produces a wrong cumulative diff, which is the
review target of every Phase C sub-agent. In this session the
mismatch was caught by the orchestrator before any sub-agent saw the
inflated 5,836-insertion diff, but only because the diff was visibly
larger than the step episodes implied. A subtler rebase (one that
moves only a few commits) could produce a wrong-by-a-handful-of-files
diff that no human spot-check catches, leading to spurious "missing
file" findings or to real findings being missed because the diff
contains noise from earlier tracks. The cost on this session was ~3
turns of detection-and-recompute.

## Proposed fix

Add a "verify base SHA is HEAD-ancestor" preflight to the Phase C
startup. Concretely, in `track-code-review.md` § Phase C Startup
between current steps 1 and 2:

```
1a. Verify the base commit is reachable from HEAD:

    git merge-base --is-ancestor "$BASE_SHA" HEAD || \
        echo "BASE_SHA stale — recomputing"

    On stale (exit code 1):
    - Find the recording commit:
        RECORDING=$(git log --grep="^Record Phase B base commit for" \
            --format=%H --all | head -1)
    - Recompute parent:
        ACTUAL_BASE=$(git log -1 --format=%P "$RECORDING")
    - Use ACTUAL_BASE for the rest of Phase C.
    - Append a "Note: recorded base $BASE_SHA was stale (likely from
      a post-Phase-B rebase); using actual on-branch parent
      $ACTUAL_BASE" line to the step file under ## Base commit.
```

Apply the same preflight at Phase B Resume (`step-implementation.md`
§ Phase B Startup) so resumes after a rebase don't compute orphan
commits against a stale base.

Alternative: at Phase B startup, instead of `git rev-parse HEAD`,
record `git rev-parse "HEAD~$(git rev-list --count HEAD ^MERGE_BASE)"`
where MERGE_BASE = `git merge-base develop HEAD`. This would record a
SHA that survives a develop-rebase by being relative-to-merge-base.
More invasive but eliminates the failure mode at source.

## Acceptance criteria

- `track-code-review.md` § Phase C Startup adds an explicit
  HEAD-ancestor preflight step between current steps 1 and 2.
- `step-implementation.md` § Phase B Startup adds the same preflight
  at the resume path (after orphan-commit detection).
- The preflight, when triggered, recomputes the actual on-branch
  parent and updates the step file with a short discrepancy note,
  not silently substituting.
- A grep of `.claude/workflow/` for `## Base commit` shows every
  reader site (track-code-review, step-implementation, recovery,
  inline-replanning) referencing the same preflight — no reader can
  consume `## Base commit` without going through ancestor verification.
