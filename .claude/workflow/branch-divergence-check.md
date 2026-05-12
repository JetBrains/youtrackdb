# Branch Divergence Check

Runs in turn 1 of every `/execute-tracks` session, immediately
after the auto-resume decision and before any phase work begins.
A diverged branch left undetected makes every per-commit push fail
non-fast-forward, and the workflow's "push every commit" safety net
silently lapses — the draft PR drifts behind local, and a
mid-session crash destroys work that was supposed to be backed up.

Re-entered mid-session when a per-commit `git push` is rejected
`non-fast-forward` for the first time in the session (routed from
`step-implementation.md` sub-step 8 and the central rule in
`commit-conventions.md` § Push failure handling). Subsequent
rejections in the same session do not re-route; the user's chosen
resolution applies for the remainder of the session.

---

## Detection

Run:

```bash
git fetch origin
git status -sb | grep -q 'diverged' && \
    git rev-list --left-right --count HEAD...@{u}
```

If `git status -sb` does not contain `diverged`, continue normally.
Otherwise surface the ahead/behind counts from `git rev-list` to
the user and ask which of three resolutions to apply. Do **not**
pick a default — force-pushing or resetting without explicit
consent violates the harness git-safety protocol (see user-global
`~/.claude/CLAUDE.md`).

**Skip the check** when `git fetch` fails (offline, no remote
configured) or the branch has no upstream (`@{u}` does not resolve).
The first per-commit push will surface any later issue, and the
session-end unpushed-commit report (see `workflow.md` § What to do
before ending a session) still covers the silent-failure case.

---

## Resolutions

Present all three options to the user, with the ahead/behind counts
already on screen, and wait for an explicit choice.

### Local-authoritative

The local rewrite (rebase, squash, amend) is intended and the
remote is stale. Run once at startup:

```bash
git push --force-with-lease
```

Per-commit push operates normally for the rest of the session.

Never use plain `--force`. If `--force-with-lease` itself rejects
(the remote moved after the local fetch), re-route to this gate
rather than retry blindly — the new remote state may invalidate
the user's earlier choice.

### Remote-authoritative

Discard the local rewrites in favour of `origin`. After explicit
confirmation that local-only commits are unrecoverable:

```bash
git reset --hard origin/<branch>
```

Workflow files (`docs/adr/<dir-name>/_workflow/`) are restored from
the most recent reachable remote commit; any uncommitted local
changes are lost. Warn the user before running, and surface
`git log @{u}..HEAD --oneline` so they can confirm which local
commits will be discarded.

After the reset, the auto-resume decision computed earlier in the
Startup Protocol may no longer match the new HEAD. Re-run the
Startup Protocol's state-determination step (workflow.md
§ Startup Protocol step 3) before proceeding.

### Defer

The user wants to resolve the divergence manually after the
session. Per-commit pushes will continue to fail throughout the
session but do not block phase progress.

Acknowledge the choice, then proceed. The session-end summary
reports the unpushed-commit count per `workflow.md` § What to do
before ending a session, so the residue is visible at session end
even though it accumulated silently across the session.

---

## After the choice

The user's choice applies for the remainder of the session; no
re-check is required. Mid-session non-fast-forward rejections under
the **Defer** resolution are expected and are not re-routed here.

Local-authoritative and Remote-authoritative resolutions both
return to the Startup Protocol; the orchestrator then continues to
the phase-specific work for the auto-resume state.
