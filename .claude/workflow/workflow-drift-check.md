# Workflow Drift Check

Runs in turn 1 of every `/execute-tracks` session, immediately after
the Branch Divergence Check (workflow.md § Startup Protocol step 3)
and before the handoff scan (step 4). The branch carries per-branch
`_workflow/**` artifacts whose required shape is dictated by current
`develop`: section names, mandatory artifacts, step-file schema.
Workflow-format commits land on `develop` while the branch runs,
and the branch's artifacts silently drift. Undetected drift surfaces
later as confused reviewers in Phase C, missing required sections
during track completion, or auto-resume tripping on a schema field
the branch never gained — exactly the failure mode this gate prevents.

Detection is one `git log` against the post-fetch `develop` tip; the
Branch Divergence Check already ran (or skipped) `git fetch`, so no
fetch repeats here. Migration itself stays in the `/migrate-workflow`
skill — the gate detects and gates, the skill replays. The skill
assumes a fresh session, so the Migrate now resolution ends this
session and asks the user to re-invoke from a `develop` worktree.

---

## Detection

Run, in order:

```bash
git rev-parse --verify refs/heads/develop >/dev/null 2>&1 || exit
FORK="$(git merge-base develop HEAD)"
git log --oneline "$FORK..develop" -- .claude/workflow .claude/skills
```

The first command short-circuits when `develop` does not exist
locally (bare repository, detached checkout, or `develop` missing).
The pathspecs are `.claude/workflow` and `.claude/skills`, both
passed to `git log` after the `--` separator. No additional
pathspecs; no `git fetch`.

The branch has drift iff the `git log` output is non-empty. When
non-empty, count the commits and capture the first ten subject lines
(oldest first) for the user prompt.

When the output is empty, the gate skips silently — startup continues
to step 4 with no prose. Skip the gate via the same silent path when
any condition in § Skip conditions matches before the detection
command runs.

---

## Skip conditions

The gate skips silently in three cases, all derivable from cheap
on-disk reads before the detection command runs. Order matters for
fail-fast — check the cheapest first:

1. **No `_workflow/` subtree.** The branch has nothing to migrate.
   Single-shot check: `ls -d docs/adr/*/_workflow/ 2>/dev/null`.
   Matches the `/migrate-workflow` skill's zero-match halt path;
   running detection would be wasted work even if commits exist on
   `develop`.

2. **Plan complete plus Phase 4 active.** Migrating right before the
   Phase 4 cleanup commit is wasted work — the `_workflow/` subtree
   is about to be removed regardless. The check fires when every
   track checklist entry is `[x]` or `[~]` **and** the `## Final
   Artifacts` checklist entry is `[>]` (in flight) or `[x]` (done).
   Pre-`[>]` (Phase 4 not yet started) does **not** skip; tracks
   complete but Phase 4 unstarted is still a window where the user
   may want to migrate before producing the final artifacts.

3. **Empty diff.** The detection command above returns nothing.
   Either the branch was forked from the current `develop` tip, or
   `develop` has moved forward only on code paths the gate does not
   watch.

The first match returns silent skip; the gate emits no prose and
startup continues to step 4. If none of the three match, the gate
proceeds to the three-resolution prompt below.

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
  [migrate]   end this session; run /migrate-workflow <branch> from a develop worktree
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

> End this session, switch to a `develop` worktree (e.g.,
> `cd ../develop`), and run `/migrate-workflow <branch>` there. The
> `../develop` path is a convention; users with a different layout
> substitute their own develop-worktree path.

The Migrate now branch deliberately does not run the skill inline.
The skill assumes a fresh session and runs its own context-check
loop with per-commit handoff semantics; mixing two long-running
protocols in one session risks a mid-migration context warning that
triggers the wrong handoff path. Ending the session and asking the
user to re-invoke is the cleaner boundary.

### Defer

Continue this session. Record the deferred-drift count in the
agent's in-conversation state so the end-of-turn protocol can recite
it at session end. Per-phase work proceeds normally for the rest of
the session.

The session-end summary appends one line naming the deferred drift
count and the same `cd ../develop` + `/migrate-workflow <branch>`
instruction shown in the prompt — see `workflow.md` § What to do
before ending a session for the residue contract. The in-conversation
state is intentional: an on-disk sentinel would survive across
`/execute-tracks` invocations and double-report against the next
session's gate re-prompt.

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

**Remote-authoritative re-entry contract.** The Branch Divergence
Check's Remote-authoritative resolution runs
`git reset --hard origin/<branch>`, which can shift the branch's
fork point against `develop` and therefore the drift detection
range. When the Startup Protocol re-enters from a Remote-authoritative
reset (see `branch-divergence-check.md` § Remote-authoritative), the
Workflow Drift Check re-fires on the post-reset HEAD before the
handoff scan runs — the prior session's choice does not carry over,
because the prior session's HEAD no longer exists. Local-authoritative
and Defer resolutions in the divergence gate do not move HEAD, so
they do not trigger re-entry into this gate.

A subsequent in-session non-fast-forward push that re-routes to the
Branch Divergence Check (per `commit-conventions.md` § Push failure
handling) does not re-fire the drift check. The drift gate is a
startup-only gate; mid-session re-entry only happens via the
Remote-authoritative reset path described above.
