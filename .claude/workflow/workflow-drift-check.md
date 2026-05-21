# Workflow Drift Check

Runs in turn 1 of every `/execute-tracks` session, immediately after
the Branch Divergence Check (workflow.md § Startup Protocol step 3)
and before the handoff scan (step 4). Undetected drift surfaces later
as confused reviewers in Phase C, missing required sections during
track completion, or auto-resume tripping on a schema field the
branch never gained — exactly the failure mode this gate prevents.
The branch carries per-branch `_workflow/**` artifacts whose required
shape is dictated by current `develop`: section names, mandatory
artifacts, step-file schema. Workflow-format commits land on
`develop` while the branch runs, and the branch's artifacts silently
drift.

Detection is one `git log` against the post-fetch `origin/develop`
tip. The Branch Divergence Check's `git fetch` targets the branch's
upstream (typically `origin/<branch>`), not `develop`, so this gate
fetches `origin develop` independently before the `git log`. The
fetch updates `refs/remotes/origin/develop` rather than the local
`refs/heads/develop`, so every command after the fetch reads
`origin/develop` to ensure the comparison is against the post-fetch
state, not a stale local branch. Migration itself stays in the
`/migrate-workflow` skill — the gate detects and gates, the skill
replays. The skill assumes a fresh session, so the Migrate now
resolution ends this session and asks the user to re-invoke from a
`develop` worktree.

---

## Detection

Run, in order:

```bash
# Duplicate fetch: the Branch Divergence Check fetched the branch's
# upstream (typically origin/<branch>), not develop. Tolerate failure
# silently for offline / no-remote cases, mirroring the divergence
# check's offline-tolerance pattern. The fetch updates origin/develop
# (and FETCH_HEAD), not the local develop branch.
git fetch origin develop 2>/dev/null || true
# Verify the post-fetch tip exists. origin/develop is the canonical
# ref used by every command below; if the user has never fetched
# develop (bare repository, brand-new clone, develop never tracked)
# the gate skips silently.
git rev-parse --verify origin/develop >/dev/null 2>&1 || exit
FORK="$(git merge-base origin/develop HEAD)"
# merge-base returns empty when HEAD and origin/develop share no
# common ancestor — a degenerate state where drift detection is
# meaningless; skip silently.
test -n "$FORK" || exit
git log --reverse --oneline "$FORK..origin/develop" -- .claude/workflow/ .claude/skills/ | head -10
```

The verify command short-circuits when `origin/develop` is missing
(no fetch ever ran, bare repository, detached checkout without a
tracked develop). Using `origin/develop` rather than the local
`develop` ref means the gate works even on contributors who never
created a local `develop` branch and guarantees the comparison uses
the post-fetch state. Pathspecs `.claude/workflow/` and
`.claude/skills/` go to `git log` after `--`; the trailing slashes
make the directory intent explicit and prevent accidental matches
against a same-named file in a sibling location. `--reverse … |
head -10` produces the oldest-first top-ten directly; run a separate
`git log --oneline "$FORK..origin/develop" -- … | wc -l` to count
the full range when the prompt also needs the total.

The branch has drift iff the `git log` output is non-empty. Empty
output skips the gate silently and startup continues to step 4.
Any matched condition in § Skip conditions takes the same silent
path before the detection command runs.

---

## Skip conditions

The gate skips silently in three cases, all derivable from cheap
on-disk reads before the detection command runs. Order matters for
fail-fast — check the cheapest first:

1. **No `_workflow/` subtree.** Nothing to migrate. Check:
   `ls -d docs/adr/*/_workflow/ 2>/dev/null`. Matches the
   `/migrate-workflow` skill's zero-match halt path.

2. **Plan complete plus Phase 4 active.** The `_workflow/` subtree is
   about to be removed by the Phase 4 cleanup commit. Read
   `implementation-plan.md` from each `_workflow/` directory
   returned by #1; the skip fires only when **every** plan matches
   — all track entries `[x]` or `[~]` **and** the `## Final
   Artifacts` entry `[>]` or `[x]`. Any plan that is missing,
   unreadable, or still has open tracks (or Pre-`[>]` Final
   Artifacts) falls through to #3. Pre-`[>]` is still a window for
   a user migration before final artifacts land.

3. **Empty diff.** The detection command above returns nothing.
   Either the branch was forked from the current `origin/develop`
   tip, or `origin/develop` has moved forward only on code paths the
   gate does not watch.

First match returns silent skip; the gate emits no prose and startup
continues to step 4. None matching falls through to the
three-resolution prompt below.

---

## Resolutions

When drift surfaces (non-empty detection output and no skip
condition matched), print the commit count, the short fork SHA, and
the first ten subject lines (oldest first), then force an explicit
pick. Approximate prompt shape:

```
Workflow drift detected: N commits on develop touch .claude/workflow/** or
.claude/skills/** since fork point <short-FORK>.

First commits (oldest first):
  <short-sha-1>  <subject-1>
  <short-sha-2>  <subject-2>
  ...

Resolutions:
  [migrate]   end session; user runs /migrate-workflow <branch> from a develop worktree
  [defer]     continue this session; deferred drift will appear in the session-end summary
  [suppress]  continue this session; no session-end reminder

Pick one (no default).
```

Do **not** pick a default. The user must choose one of the three
resolutions before startup proceeds. Malformed answers (`yes`, `ok`)
trigger a re-prompt using the same shape — same contract as the
Branch Divergence Check.

### Migrate now

End the current session and instruct the user to re-invoke the
migration skill from a `develop` worktree:

> Switch to a `develop` worktree (e.g., `cd ../develop`) and run
> `/migrate-workflow <branch>` there. The `../develop` path is a
> convention; users with a different layout substitute their own
> develop-worktree path.

The Migrate now branch deliberately does not run the skill inline.
The skill assumes a fresh session and runs its own context-check
loop with per-commit handoff semantics; mixing two long-running
protocols in one session risks a mid-migration context warning that
triggers the wrong handoff path. Ending the session and asking the
user to re-invoke is the cleaner boundary.

End the session before reaching `workflow.md § What to do before
ending a session` — this is the only Startup-Protocol-side early
exit. No phase work has run, so there are no episodes to commit, no
unpushed-commit residue beyond what the Branch Divergence Check may
have produced, and self-improvement reflection has nothing to record.
The session-end output is the single instruction line above.

### Defer

Continue this session. Record the deferred-drift count so the
end-of-turn protocol can recite it at session end. Per-phase work
proceeds normally.

State shape: a TaskCreate todo titled `Deferred workflow drift:
<count> commits since <short-fork-SHA>`, where `<count>` is the full
commit range total and `<short-fork-SHA>` is the seven-character
abbreviation of `$FORK`. Subject lines are omitted — the user re-runs
the detection bash for full context. The session-end summary reads
the todo title verbatim. If TaskCreate is unavailable, hold the same
two fields in in-context memory and recite the same line shape; the
todo is preferred because in-context memory is unreliable across
long sessions.

The session-end summary appends the title line plus the same
`cd ../develop` + `/migrate-workflow <branch>` instruction shown in
the prompt — see `workflow.md` § What to do before ending a session
for the residue contract. An on-disk sentinel would survive across
`/execute-tracks` invocations and double-report against the next
session's gate re-prompt, so the marker stays in-conversation only.

### Suppress

Continue this session. Do **not** record the deferred-drift count.
The session-end summary does not mention drift at all. Use this
when the user has already evaluated the commit range and wants to
silence the residue for the rest of the session without ending it.

The functional difference from Defer is one line of session-end
prose; the semantic difference is "I have evaluated and chosen to
ignore for now" versus "remind me at session end".

---

## After the choice

The user's choice applies for the remainder of the session; no
re-check is required in the normal startup flow. After Migrate now,
the session ends. After Defer or Suppress, startup continues to
step 4 (handoff scan) and the auto-resume decision proceeds against
the unchanged on-disk shape of `_workflow/**`.

**Remote-authoritative re-entry — forward-looking note.** A
`git reset --hard origin/<branch>` from the divergence gate's
Remote-authoritative resolution shifts the fork point and therefore
the drift detection range. The divergence gate currently routes
post-reset only to workflow.md § Startup Protocol step 3, not back
into this gate (step 3a); the re-entry contract is one-sided. Until
that gap closes, an orchestrator resolving Remote-authoritative
within a session should treat the post-reset drift state as
unverified and re-invoke `/execute-tracks` in a fresh session. Once
symmetric, this gate will re-fire on the post-reset HEAD with any
prior Defer state discarded first. Local-authoritative and Defer in
the divergence gate do not move HEAD and are unaffected.

An in-session non-fast-forward push that re-routes to the Branch
Divergence Check (per `commit-conventions.md` § Push failure handling)
does not re-fire this gate. The drift gate is startup-only;
mid-session re-entry only happens via the Remote-authoritative reset
path above.
