# Workflow Drift Check

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Detection | orchestrator,planner | 1,2,3A | Detection moved to the script's `--mode full` drift walk; this section cites the JSON the gate reads. |
| §No-drift normalization | orchestrator,planner | 1,2,3A | Normalization moved to the script; cites the `actions_taken` recital and keeps the path-quoting note. |
| §Skip conditions | orchestrator,planner | 1,2,3A | Three silent-skip conditions (no _workflow dir, Phase 4 active via the ledger phase tail, empty diff) checked cheapest-first. |
| §Resolutions | orchestrator,planner | 1,2,3A | On drift, print the commit count and stamp base, then force a Migrate / Defer / Suppress pick with no default. |
| §Migrate now | orchestrator,planner | 1,2,3A | End the session and ask the user to re-invoke /migrate-workflow; the gate never runs the skill inline. |
| §Defer | orchestrator,planner | 1,2,3A | Continue this session and record a deferred-drift todo recited at session end. |
| §Suppress | orchestrator,planner | 1,2,3A | Continue this session and silence the drift residue with no session-end reminder. |
| §After the choice | orchestrator,planner | 1,2,3A | The chosen resolution holds for the session; Migrate ends it, Defer/Suppress continue startup. |

<!--Document index end-->

Runs early in the startup sequence for two callers: `/execute-tracks`
(turn 1, immediately after the divergence gate and before the handoff
scan in `workflow.md § Startup Protocol`'s dispatch) and `/create-plan`
(between Step 1's workflow-docs read and Step 1a's handoff scan). The
mechanical detection itself runs inside the startup script — see
§ Detection below; this file owns the resolution UX. Undetected drift
surfaces later as confused reviewers
in Phase C, missing required sections during track completion, or
auto-resume tripping on a schema field the branch never gained —
exactly the failure mode this gate prevents. The branch carries
per-branch `_workflow/**` artifacts whose required shape is dictated
by current `develop`: section names, mandatory artifacts, step-file
schema. Workflow-format commits land on `develop` while the branch
runs, and the branch's artifacts silently drift.

Detection (run by the script) is one `git log` over the active plan's
stamp-derived range against HEAD. The branch is a self-contained
capsule (workflow-format commits enter the branch's view only when the
user explicitly rebases or merges `develop`), so the walk ranges
against the branch's own HEAD and never fetches `develop`
independently. The plan dir the
caller resolved at startup (see conventions.md:orchestrator,planner:1,2,3A `§1.2` and `§1.6(g)`)
scopes the walk; cross-plan folding is out of scope (D13). Migration
itself stays in the `/migrate-workflow` skill — the gate detects and
gates, the skill replays. The skill assumes a fresh session, so the
Migrate now resolution ends this session and asks the user to
re-invoke `/migrate-workflow` from this worktree.

---

## Detection
<!-- roles=orchestrator,planner phases=1,2,3A summary="Detection moved to the script's --mode full drift walk; this section cites the JSON the gate reads." -->

Detection no longer lives in this file. The two-phase drift walk runs
inside `.claude/scripts/workflow-startup-precheck.sh` under
`--mode full`. Phase 1 is the conventions.md:orchestrator,planner:1,2,3A `§1.6(h)` artifact
walk that classifies each `_workflow/**` artifact as stamped or
unstamped; Phase 2 is the pairwise `git merge-base` fold to `BASE_SHA`
plus the `git log BASE_SHA..HEAD` over the workflow pathspecs.
The script is the single behavioral home; this file owns only the
resolution UX below. Cite the script's `emit_json` function for the
exact field contract, not any frozen design draft.

The `--mode full` JSON carries a `drift` object the gate reads:

- `drift.detected` — boolean. `false` means no actionable drift (either
  `kind` is null because no stampable artifact is on disk, or
  `kind == "stamped"` and every stamp is already current); `true` means
  the gate runs the resolution prompt below.
- `drift.kind` — `null`, `"stamped"`, `"unstamped"`, or
  `"merge-base-failed"`. The non-null kinds map to the resolution
  rendering: `"stamped"` with `detected == true` carries a real commit
  range; `"unstamped"` and `"merge-base-failed"` route to the
  `/migrate-workflow` bootstrap that gathers a base SHA.
- `drift.base_sha` — the folded `BASE_SHA` (full SHA) when the fold
  ran, else null.
- `drift.commit_count` — the full range total when `kind == "stamped"`
  and drift is detected, else `0` or null.
- `drift.first_commits` — the first ten `{sha, subject}` entries (oldest
  first) when a real range exists, else the empty array.
- `drift.normalization_landed` — boolean; `true` when the script's one
  autonomous no-drift normalization commit landed this run (see
  § No-drift normalization below).

The five outcomes the gate routes on, keyed off `drift.detected` and
`drift.kind`:

- **`detected == true`, `kind == "unstamped"`**: one or more artifacts
  carry no line-1 stamp. The resolution prompt runs regardless of
  whether a `git log` range exists; the unstamped state is itself the
  signal that the artifact set predates the stamp scheme and must be
  migrated.
- **`detected == true`, `kind == "stamped"`**: a real commit range sits
  past `base_sha`. The resolution prompt runs with `commit_count` and
  the `first_commits` list reported in place of the legacy fork-point SHA.
- **`detected == false`, `kind == "stamped"`**: no drift in the strict
  sense. The script ran the no-drift normalization sub-step (§ No-drift
  normalization below) before reporting this state when the stamp set
  was non-uniform; otherwise it skipped silently. Startup continues to
  the calling session's next startup step. Any matched condition in
  § Skip conditions takes the same silent path inside the script before
  the walk runs.
- **`detected == false`, `kind == null`**: no stampable artifact under
  the active plan's `_workflow/` (both the stamped and unstamped sets
  are empty). Silent skip per conventions.md:orchestrator,planner:1,2,3A `§1.6(h)`; the gate
  reports no drift and startup continues.
- **`detected == true`, `kind == "merge-base-failed"`**: a stamp sits
  on a pruned or unreachable commit. The script routes the failing pair
  to the unstamped short-circuit per conventions.md:orchestrator,planner:1,2,3A `§1.6(c)`; the
  gate signals drift and the bootstrap prompt in `§1.6(d)` covers the
  failing set alongside any other unstamped artifacts in one batched
  user prompt.

**Byte-source note.** The `§1.6(h)` artifact walk the script implements
is the byte-source shared with `/migrate-workflow`'s Step 2 range
computation. The range definition (`BASE_SHA..HEAD`, pairwise fold via
`git merge-base`, merge-base-failure recovery), the canonical parser
regex (`workflow-sha:` anchor plus `[0-9a-f]{40}` extraction), and the
active-plan scope rule live in conventions.md:orchestrator,planner:1,2,3A `§1.6(c)`, `§1.6(h)`,
and `§1.6(a1)` respectively. The script conforms to that spec, checked
by the byte-source conformance fixture in `.claude/scripts/tests/`.

---

## No-drift normalization
<!-- roles=orchestrator,planner phases=1,2,3A summary="Normalization moved to the script; cites the actions_taken recital and keeps the path-quoting note." -->

The normalization commit no longer lives in this file. The script's
`no_drift_normalization` function (called from `--mode full` when the
fold reports the empty `git log` but the active plan's stamps are
non-uniform) recomputes the stamped-artifact path list, rewrites each
line-1 stamp to the folded `BASE_SHA`, verifies two diff-shape guards,
and lands one all-or-nothing commit with the subject `Normalize
workflow-sha stamps to <short-BASE_SHA>`. This is the script's only
autonomous mutation. It reports the commit in the `--mode full`
`actions_taken` array as a one-element entry
`{action, commit, subject}` with `action == "normalize-workflow-sha-stamps"`,
and flips `drift.normalization_landed` to `true`. The gate cites those
JSON fields when it recites the housekeeping commit to the user; it
does not re-derive the normalization itself.

The all-or-nothing contract holds exactly as before: either every
stamp in the active plan moves to `BASE_SHA` in one commit, or the
on-disk state is unchanged. The "stamps rewritten without a
normalization commit" in-between state is unreachable under correct
invocation. The mechanism is the two diff-shape guards: the script
rewrites the stamps **in place** (a `printf` + `tail -n +2` pattern,
never `git add`-ed first), verifies the unstaged diff against a strict
line-1-only shape, and only then stages and commits — on any guard
mismatch the working tree is restored from `HEAD` via `git checkout --`
before any commit is created. The earlier framing that called the
rewrite "staged" was inaccurate: nothing reaches the git index until
both guards pass.

**Path-quoting assumption.** All paths under `_workflow/**` are
constrained by convention to fixed-template names
(`implementation-plan.md`, `design.md`, `design-mechanics.md`,
`track-<digits>.md`); no path contains shell metacharacters, so the
script's unquoted expansion of its stamped-file list is safe. A future
change that introduced spaces or shell metacharacters in artifact
names would require switching to a NUL-delimited path list before the
unquoted expansions become hazardous. The same fixed-template
assumption has one known-debt edge in the script's guard 2: its
`git status --porcelain ... | awk '{print $2}'` reads the second
whitespace-delimited field, so a porcelain path containing a space is
truncated and the guard would compare a partial path. This is harmless
under the fixed-template names today, but it is the reason any
artifact-name change must reach this guard too.

**Diff-shape guard rationale.** The two guards are complementary.
Guard 1 catches a rewriter that touched more than line 1 (e.g., a
`printf` format that emitted no newline, leaving the new stamp glued
to the artifact's original first line). Guard 2 catches a dirty path
inside the active plan's `_workflow/` that the walk did not record as
stamped (an orphan `.tmp`, a new artifact type the walk does not yet
enumerate, or an in-tree edit inside the plan subtree outside the
stamped set). Either failure mode would land a malformed normalization
commit; the abort-and-restore path keeps the working tree at `HEAD` on
mismatch and surfaces the failure on stderr (never stdout — the JSON
channel stays clean) instead of papering over it. A guard mismatch
halts the calling session with a non-zero script exit; the user
inspects the named hunks or paths manually and re-invokes after
resolving them. There is no automatic fallthrough to Resolutions.

**Commit shape.** One commit, subject `Normalize workflow-sha stamps
to <short-BASE_SHA>`, body optional. The commit is independent of any
phase work — the script runs at session start before any phase work,
so no episode commit can interleave with the normalization commit. The
next gate run reads the now-uniform stamps and the fold exits with a
single-element stamp set. The user sees no prompt at normalization
time; the recital of the landed commit (from `actions_taken`) is a
silent housekeeping report, not a drift signal.

---

## Skip conditions
<!-- roles=orchestrator,planner phases=1,2,3A summary="Three silent-skip conditions (no _workflow dir, Phase 4 active via the ledger phase tail, empty diff) checked cheapest-first." -->

Three silent-skip conditions short-circuit before the prompt fires.
The script applies them during its `--mode full` drift walk and folds
the result into `drift.detected == false`; this section documents the
skip semantics the gate relies on. Skip #1 and Skip #2 are cheap
pre-walk on-disk reads (no `git log` needed); Skip #3 is post-fold
(after `drift.base_sha` is derived and the range `git log` returns its
result). All three scope to the active plan dir (the plan dir resolved
at startup per conventions.md:orchestrator,planner:1,2,3A `§1.6(g)` and `§1.2`); cross-plan
folding is out of scope (D13). Order matters for fail-fast: check the
cheapest first:

1. **Active plan's `_workflow/` directory doesn't exist.** Nothing to
   migrate for this plan. Check: `[ -d "$PLAN_DIR/_workflow" ]`.
   Matches the `/migrate-workflow` skill's zero-match halt path when
   the caller's resolved plan dir carries no workflow subtree.

2. **Phase 4 active (Phase-4-pending or complete).** The active plan's
   `_workflow/` subtree is about to be removed by the Phase 4 cleanup
   commit. The plan's `## Final Artifacts` section is gone under the
   derived-mirror model (D5/D7) — and `minimal` has no plan at all — so
   this skip reads the **phase ledger** `phase` tail (last value wins),
   not a plan checkbox: it fires when
   `$PLAN_DIR/_workflow/phase-ledger.md` exists and its resolved
   `phase` is `D` (Phase 4 pending) or `Done` (Phase 4 complete). A
   missing or unreadable ledger, or a ledger whose tail `phase` is
   `0`/`A`/`C`, falls through to #3 — that is still a window for a user
   migration before final artifacts land. The cross-plan AND-fold the
   branch-wide gate used to apply is dropped — each plan migrates
   independently (D13).

3. **Empty diff.** The fold's `git log base_sha..HEAD` returns nothing
   **and** no artifact in the active plan is unstamped
   (`drift.detected == false`, `kind == "stamped"`, `commit_count`
   zero). Every stamped artifact's `base_sha` is already at or past
   every workflow commit reachable from HEAD on the paths the gate
   watches. The no-drift normalization sub-step above handles the
   non-uniform-stamp variant of this case; this skip fires when stamps
   are already uniform (or the no-drift normalization just landed on a
   prior gate run).

First match returns silent skip; the gate emits no prose and startup
continues to the calling session's next startup step. None matching
falls through to the three-resolution prompt below.

---

## Resolutions
<!-- roles=orchestrator,planner phases=1,2,3A summary="On drift, print the commit count and stamp base, then force a Migrate / Defer / Suppress pick with no default." -->

When drift surfaces (`drift.detected == true` and no skip condition
matched), print the commit count (`drift.commit_count`), the short
stamp-base SHA (`drift.base_sha`), and the first ten subject lines from
`drift.first_commits` (oldest first), then force an explicit pick.
Approximate prompt shape:

```
Workflow drift detected: N commits in your branch's range touch .claude/workflow/**,
.claude/skills/**, or .claude/agents/** since stamp base <short-BASE_SHA>.

First commits (oldest first):
  <short-sha-1>  <subject-1>
  <short-sha-2>  <subject-2>
  ...

Resolutions:
  [migrate]   end session; user runs /migrate-workflow from this worktree
  [defer]     continue this session; deferred drift will appear in the session-end summary
  [suppress]  continue this session; no session-end reminder

Pick one (no default).
```

Do **not** pick a default. The user must choose one of the three
resolutions before startup proceeds. Malformed answers (`yes`, `ok`)
trigger a re-prompt using the same shape. The retry is bounded at
three attempts per the policy in conventions.md:orchestrator,planner:1,2,3A `§1.6(d)`; after
three malformed answers the gate halts and the user `/clear`s the
session.

**Unstamped short-circuit rendering.** When `drift.kind` is
`"unstamped"` or `"merge-base-failed"` (the unstamped path or a
`git merge-base` failure routed there per conventions.md:orchestrator,planner:1,2,3A `§1.6(c)`),
the script runs no range `git log` and neither `drift.base_sha` nor
`drift.commit_count` is derived (both null). The Resolutions prompt
substitutes:

- The commit-count line becomes: `One or more workflow artifacts in
  the active plan are unstamped: the /migrate-workflow bootstrap
  prompt will gather a base SHA.`
- The "First commits" block is replaced with `<no commit list:
  artifacts are unstamped>`.
- The Defer todo title becomes `Deferred workflow drift: unstamped
  artifacts in active plan, see /migrate-workflow`.

The three sub-sections below (Migrate now / Defer / Suppress) read
these substitutions verbatim; the Defer state-shape paragraph in
particular points at the same rule.

### Migrate now
<!-- roles=orchestrator,planner phases=1,2,3A summary="End the session and ask the user to re-invoke /migrate-workflow; the gate never runs the skill inline." -->

End the current session and instruct the user to re-invoke the
migration skill from this worktree:

> Run `/migrate-workflow` from this worktree.

The Migrate now branch deliberately does not run the skill inline.
The skill assumes a fresh session and runs its own context-check
loop with per-commit handoff semantics; mixing two long-running
protocols in one session risks a mid-migration context warning that
triggers the wrong handoff path. Ending the session and asking the
user to re-invoke is the cleaner boundary.

End the session before the calling session reaches its session-end
surface: `/execute-tracks` exits before
`workflow.md § What to do before ending a session` (the only Startup-Protocol-side early exit
for that caller); `/create-plan` exits before Step 5's commit and
push (the Migrate-now branch cuts the session short of the recital
added in Step 5 of `/create-plan`). The calling SKILL appends a
caller-specific re-invocation instruction to the single-line message
above — `/execute-tracks` re-invokes `/execute-tracks`, `/create-plan`
re-invokes `/create-plan`. No phase work has run in either caller,
so there are no episodes to commit, no unpushed-commit residue
beyond what the Branch Divergence Check may have produced
(`/execute-tracks` only — `/create-plan` has no such check), and
self-improvement reflection has nothing to record. The session-end
output is the single instruction line above. For `/create-plan`,
Step 1.5 fires before Step 2's aim prompt and before Step 1b's
`mkdir`, so the "no phase work" claim holds unconditionally: no
research, no plan files, no draft PR.

### Defer
<!-- roles=orchestrator,planner phases=1,2,3A summary="Continue this session and record a deferred-drift todo recited at session end." -->

Continue this session. Record the deferred-drift count so the
end-of-turn protocol can recite it at session end. Per-phase work
proceeds normally.

State shape: a TaskCreate todo titled `Deferred workflow drift:
<count> commits since <short-stamp-base-SHA>`, where `<count>` is
`drift.commit_count` and `<short-stamp-base-SHA>` is the seven-
character abbreviation of `drift.base_sha`. Subject lines are omitted;
the user re-runs the startup script (`--mode full`) to recover the
subject lines from `drift.first_commits`. The session-end recital
reads the todo title verbatim from the per-caller recital surface:
`/execute-tracks` reads it at
`workflow.md § What to do before ending a session`; `/create-plan` reads it at the recital in
`/create-plan` `SKILL.md` Step 5 (the commit-push-and-PR step; the
recital fires before Step 5 opens the draft PR so the user sees the
residue in the same session). If TaskCreate is unavailable, hold the
same two fields in in-context memory and recite the same line shape
from whichever recital surface the calling session uses; the todo is
preferred because in-context memory is unreliable across long
sessions.

When the deferred drift came from the unstamped short-circuit (the
§ Resolutions § Unstamped short-circuit rendering paragraph above),
the todo title carries the unstamped variant verbatim (`Deferred
workflow drift: unstamped artifacts in active plan, see
/migrate-workflow`) and neither `<count>` nor `<short-stamp-base-SHA>`
appear. Subject-line recovery does not apply in this variant — the
script ran no range `git log`, so `drift.first_commits` is empty — so
the user runs `/migrate-workflow` directly when they pick up the
deferred work; the migration prompts for the unstamped-base SHA and
enumerates the affected artifacts from there.

The session-end summary appends the title line plus the same
in-branch `/migrate-workflow` instruction shown in the prompt — see
`workflow.md` § What to do before ending a session for the residue
contract. An on-disk sentinel would survive across calling-session
invocations and double-report against the next session's gate
re-prompt, so the marker stays in-conversation only.

### Suppress
<!-- roles=orchestrator,planner phases=1,2,3A summary="Continue this session and silence the drift residue with no session-end reminder." -->

Continue this session. Do **not** record the deferred-drift count.
The session-end summary does not mention drift at all. Use this
when the user has already evaluated the commit range and wants to
silence the residue for the rest of the session without ending it.

The functional difference from Defer is one line of session-end
prose; the semantic difference is "I have evaluated and chosen to
ignore for now" versus "remind me at session end".

---

## After the choice
<!-- roles=orchestrator,planner phases=1,2,3A summary="The chosen resolution holds for the session; Migrate ends it, Defer/Suppress continue startup." -->

The user's choice applies for the remainder of the session; no
re-check is required in the normal startup flow. After Migrate now,
the session ends. After Defer or Suppress, startup continues to the
calling session's next startup step (the handoff scan in both
callers — for `/execute-tracks` this is the handoff-resume step of
`workflow.md § Startup Protocol`'s dispatch; for `/create-plan` this is
Step 1a) and proceeds against the unchanged on-disk shape of
`_workflow/**`.

**Remote-authoritative re-entry — forward-looking note
(`/execute-tracks` only).** The Branch Divergence Check exists only
in `/execute-tracks`'s Startup Protocol, so this re-entry path applies
only when the calling session is `/execute-tracks`. A
`git reset --hard origin/<branch>` from the divergence gate's
Remote-authoritative resolution shifts the fork point that the
divergence gate computes, and the post-reset HEAD also moves the drift
detection range (`base_sha..HEAD`) since both endpoints can change. The
divergence gate currently routes post-reset only back into the
divergence gate of `workflow.md § Startup Protocol`'s dispatch, not
back into this drift gate; the re-entry contract is one-sided. Until
that gap closes, an orchestrator resolving Remote-authoritative within an
`/execute-tracks` session should treat the post-reset drift state as
unverified and re-invoke `/execute-tracks` in a fresh session. Once
symmetric, this gate will re-fire on the post-reset HEAD with any
prior Defer state discarded first. Local-authoritative and Defer in
the divergence gate do not move HEAD and are unaffected.

An in-session non-fast-forward push that re-routes to the Branch
Divergence Check (per `commit-conventions.md` § Push failure handling)
does not re-fire this gate. The drift gate is startup-only;
mid-session re-entry only happens via the Remote-authoritative reset
path above.
