---
severity: medium
phase: phase-b
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Branch divergence from origin blocks the workflow's "push every commit" rule with no documented resolution

## Symptom

At the start of this Phase B session, `git status` reported:

```
Your branch and 'origin/unit-test-coverage' have diverged,
and have 296 and 294 different commits each, respectively.
```

The local branch carries 296 commits unique to local; the remote carries 294
commits unique to remote. Inspecting the recent tip of each side shows
**identical commit messages but different SHAs** — i.e., a prior local
session rebased the branch but the remote still reflects the pre-rebase
history. The exact divergence inception is unknown to this session, but
the state has been in place since at least the prior Phase B (Track 19
Step 1) and the Phase A and Phase C sessions before it.

The orchestrator wrote and committed five episode commits this session
(Steps 1 through 5 episodes plus their implementer commits) — every
`git push` rejected with:

```
! [rejected] unit-test-coverage -> unit-test-coverage (non-fast-forward)
hint: Updates were rejected because the tip of your current branch is
hint: behind its remote counterpart. ...
```

The orchestrator did not retry with `--force-with-lease`: per
`~/.claude/CLAUDE.md` and the harness git-safety protocol, force-push
requires explicit user authorization. The session ended with all 6 new
commits (Steps 2–5 implementer + episode commits) local-only.

## Reproduction context

- Phase: phase-b (recurring; same pattern was observable in the prior
  Phase A and Phase C sessions for Track 18 / Track 19 in this branch)
- Workflow doc(s) involved:
  - `.claude/workflow/step-implementation.md` § sub-step 8 ("Commit and
    push the episode")
  - `.claude/workflow/commit-conventions.md` § Push every commit
  - `.claude/workflow/workflow.md` § Session Boundary Rules → "Run
    `git push` so the branch's draft PR reflects the final state of
    the session"
  - User-global `~/.claude/CLAUDE.md` → Git Safety Protocol
- Tool / sub-agent involved: orchestrator-side `git push` in Bash
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any session running on a branch where local has
  rewritten history that already exists on `origin` (interactive
  rebase, squash, fixup-rebase, force-pull, etc.) — and where the
  user has not explicitly authorized force-push for the session.

## Why it's a problem

The workflow's "push every commit" rule has three load-bearing reasons:

1. The branch's draft PR reflects real-time progress for team visibility.
2. A local-disk loss does not destroy planning / implementation work.
3. The commit-conventions § Push every commit rule explicitly names this
   as a *safety net* for unexpected session-end interruptions.

When push is silently failing every commit, all three guarantees lapse —
but the workflow proceeds as if they hold. The user has no signal that
the draft PR is now ~12 commits behind local, so a session-end-mid-task
crash would silently lose the work that was supposed to be safe-netted.

The harness git-safety protocol is correct to forbid silent force-push —
that protects against "the divergence is real and the user does not yet
know about it" cases. But the workflow doc has no answer for "the
divergence is intentional (rebase) and the user already knows about it
but hasn't told the orchestrator, what now?". So the orchestrator falls
through to "skip the push, note at session end, hope the user resolves
manually" — which is exactly the silent-failure the rules try to prevent.

This session inherited the divergence at start; it accumulated 6 unpushed
commits over the session; and the user only saw the push-failure note in
the end-of-session summary.

## Proposed fix

Add a session-startup check to the Startup Protocol that detects branch
divergence and routes to one of three documented resolutions:

(a) **Add a §"Branch divergence at session start" to `workflow.md`** that
runs after the auto-resume decision (or as part of it). The check is:

```bash
git fetch origin && git status -sb | grep -q 'diverged'
```

If the branch is diverged, the orchestrator presents the user with three
options (modeled on the State 0 design-decision pattern):

  - **Local-authoritative**: confirm a `git push --force-with-lease`
    is intended; orchestrator runs it once per session at startup,
    then push-every-commit operates normally for the rest of the
    session.
  - **Remote-authoritative**: the user wants to discard local
    rewrites; orchestrator runs `git reset --hard origin/<branch>`
    after explicit confirmation. Workflow files are restored from
    the most recent reachable commit.
  - **Defer**: the user wants to resolve manually; orchestrator
    flags push failures throughout the session but does not block
    progress on them.

(b) **Make `step-implementation.md` § sub-step 8 push handling explicit**
about the divergence case. Currently the doc just says "commit and
push" — when push fails for divergence reasons (not network, not
permissions, not pre-receive hook), the orchestrator should either
(i) trigger the §"Branch divergence" gate from option (a) once, or
(ii) downgrade silently and note in the session-end summary, but
**not** silently skip the push without surfacing the issue.

(c) **Surface the unpushed-commit count at session end** as a hard
requirement. The current "End the session" instruction in
`workflow.md` says to "run `git push` so the branch's draft PR
reflects the final state of the session". When push fails, the
session-end output must explicitly count and name unpushed commits
(e.g., "6 commits unpushed: <list>; resolve before next session").
This makes the silent-failure visible.

Options (a) and (c) are independent — both are worth doing. (a) prevents
the issue across the session; (c) makes a single occurrence loud at
session end.

## Acceptance criteria

- `workflow.md` § Startup Protocol (or a new § "Branch divergence at
  session start") names the divergence case explicitly and routes to
  one of three resolutions.
- A repro of the 2026-05-08 case: a session that starts on a diverged
  branch produces a user prompt within the first turn, not at session
  end after multiple unpushed commits accumulate.
- `step-implementation.md` § sub-step 8 push handling distinguishes
  divergence from other push failures.
- The session-end summary in `workflow.md` § "What to do before ending
  a session" requires reporting unpushed-commit count when non-zero.
