# Phase B Recovery — Resume, Failure, and Post-Commit Handlers

This document covers the **non-happy-path** logic for Phase B (step
implementation):

- **Phase B Resume** — orphan-commit recovery when a session was
  interrupted mid-step.
- **Non-`SUCCESS` orchestrator handlers** —
  `escalate_to_user_then_respawn`, `apply_upgrade_then_decide`,
  `handle_failure`.
- **Post-Commit Handlers** — `rollback_and_handle_failure`,
  `rollback_and_escalate`, `rollback_and_upgrade`, plus the common
  `git revert` rollback procedure.
- **Step Failure**, **Two-Failure Rule**, **Track-Level Failure**.
- **Resume-side commit-pattern reference** for interpreting `git log
  {base_commit}..HEAD` output.

It is **loaded on demand** by the Phase B orchestrator. The happy path
in [`step-implementation.md`](step-implementation.md) handles all
`SUCCESS` returns, the step completion gate, the per-step orchestration
loop, the dimensional review fan-out, the cross-track impact check,
episode production, and Phase B completion.

## Loading discipline

Read this file when **either** trigger fires:

1. **At Phase B Startup**, after `git log {base_commit}..HEAD`: if the
   log shows orphan implementer commits, orphan `Review fix:` commits,
   or a `Revert step:` commit at the tip, load this file before
   spawning the first implementer — see §Phase B Resume below.
2. **At result dispatch**, in the `match result.RESULT` block: any
   value other than `SUCCESS` (`DESIGN_DECISION_NEEDED`,
   `RISK_UPGRADE_REQUESTED`, `FAILED`) — load this file before entering
   the matching handler.

The happy-path session (clean startup, every spawn returns `SUCCESS`)
never loads this file.

---

## Phase B Resume — Incomplete Step Recovery

When resuming Phase B (session restart), the next `[ ]` step may have
been **partially completed** in the previous session — code committed
but episode not yet written. This happens when a session ends between
the implementer's commit and the orchestrator's episode write, e.g.,
because of a context-level session-end gate or unexpected session
termination.

**Detection.** After identifying the next `[ ]` step, check for orphan
commits — commits that exist but have no corresponding episode in
the step file on disk:

1. Scan `git log --oneline {base_commit}..HEAD` and classify each
   commit by subject prefix and the paths it touches (per
   [`commit-conventions.md`](commit-conventions.md) § Commit type
   prefixes). Each `[x]` step in the step file is expected to
   contribute exactly **three** kinds of commit, in order:

   - **Implementer code commit** — touches code (paths outside
     `_workflow/`); subject is the imperative summary of the
     step's change. Exactly **one** per `[x]` step.
   - **`Review fix:` commits** — code commit prefixed
     `Review fix:`. **Zero or more** per `[x]` step (one per
     dim-review iteration that surfaced fixes).
   - **Episode commit** — Workflow update touching only
     `_workflow/tracks/track-<N>.md`, subject `Record episode for
     <step description>`. Exactly **one** per `[x]` step.

   Two other commit kinds appear in the log but **do not** count
   toward any `[x]` step's expected total:

   - **`Revert step:`** — a revert commit. With the implementer +
     `Review fix:` commits it cancels, it forms a self-contained
     "rolled back" group that is excluded from per-step counting.
   - **Other Workflow update** — touches only `_workflow/` but is
     not an episode commit (Phase 1 init, Phase A decomposition,
     Phase B base-commit recording, plan-corrections application,
     track-completion mark, inline-replanning update). These are
     workflow scaffolding and may appear anywhere in the log
     between or before `[x]` steps.

   For K `[x]` steps, the expected per-step set is K × (1
   implementer + N `Review fix:` + 1 episode), plus any number of
   non-episode Workflow update commits and rolled-back groups.
   Anything beyond this — typically an implementer commit (and
   possibly trailing `Review fix:` commits) without a following
   episode commit — is orphaned and belongs to the next `[ ]`
   step.

   **Crash between sub-step 7 (episode write) and sub-step 8
   (episode commit).** If the step file shows `[x]` for the most
   recent completed step but the corresponding episode commit is
   missing from the log, the previous session wrote the episode
   to disk but died before committing it. The working tree is
   dirty with the unwritten episode. Recovery: stage and commit
   the step file now (using the standard episode commit subject
   `Record episode for <step description>`), push, then proceed
   with the rest of resume. This must happen **before** spawning
   the next implementer — the implementer's `git reset --hard
   HEAD` would otherwise discard the episode write.
2. If a `Revert step:` commit is present at the tip (or near the
   tip with no implementer commits after it), the previous session
   rolled the next `[ ]` step back via §Post-Commit Handlers but may
   not have finished the orchestrator-side bookkeeping (episode,
   retry/split rows, risk-line rewrite, or user escalation).

   **Read the rollback reason from the commit body.** The first
   non-empty body line is `reason: <slug>` per §Post-Commit Handlers
   "Body format". Branch on the slug:

   **`reason: failed-review-fix`** — the review-fix respawn returned
   `FAILED`.
   - If the step file already has an `[!]` entry for this step,
     the rollback was fully recorded; respawn the implementer for
     the next `[ ]` row from `mode=INITIAL` with `step_base_commit
     = HEAD`.
   - If no `[!]` entry exists, write it now (reconstruct the
     `FAILURE` fields from the prose explanation in the revert body
     plus the git log of the reverted commits), insert retry/split
     rows per the implied `recommended_action` (default to `retry`
     when the revert body does not name one), update Progress, then
     respawn from the retry/split row with `mode=INITIAL`.

   **`reason: late-design-decision`** — the review-fix respawn
   returned `DESIGN_DECISION_NEEDED` and the user had not yet chosen
   an alternative when the session ended.
   - The original `DESIGN_DECISION` payload (alternatives,
     recommendation, exploration_notes) is **not recoverable** from
     the revert body — it was held only in the prior session's
     orchestrator state.
   - The step file is still `[ ]` (no `[!]` is written for this
     case). Respawn the implementer with `mode=INITIAL` and
     `step_base_commit = HEAD`. The new implementer either lands on
     the same design decision (and re-derives the alternatives via
     a fresh `DESIGN_DECISION_NEEDED` return) or finds a different
     path. Either is acceptable — re-derivation is the cost of the
     mid-escalation crash.

   **`reason: late-risk-upgrade`** — the review-fix respawn returned
   `RISK_UPGRADE_REQUESTED`.
   - Read the step's `**Risk:**` line. If it already names the
     upgraded level (the prior session wrote it before dying), no
     further bookkeeping is needed.
   - If the line still names the original level, apply the upgrade
     now: rewrite to the new level (auto-applied for `medium → high`,
     paused for user confirmation on `low → high`) and append an
     override note (`override: upgraded mid-Phase-B during dim review
     (<reason from revert body>)`).
   - Respawn the implementer from `mode=INITIAL` with
     `step_base_commit = HEAD`.

   **Unrecognised slug or missing `reason:` line.** Treat as a
   contract violation: present the revert commit and the step state
   to the user and ask how to proceed. Do not invent a slug.
3. If implementer / `Review fix:` code commits are present after
   the last episode commit (or after `{base_commit}` if no episode
   commits exist yet) without a `Revert step:` at the tip:
   - The previous session committed code for the next `[ ]` step
     but didn't write the episode.
   - **Resume from the appropriate orchestrator handler** by checking
     the orphan commits' messages (see §Resume-side commit-pattern
     reference below for the patterns):
     - If any orphan commit message contains `Review fix:` → the
       dimensional review loop already ran. Skip directly to
       `on_success` from the cross-track impact check (sub-step 5)
       onward; reconstruct an `EPISODE_DRAFT` from the diff and the
       commit messages.
     - If no `Review fix:` commits exist → the dimensional review
       loop has not yet run for this step. Re-enter `on_success`
       starting at the dimensional review (the implementer's commit
       is already on disk; treat it as the implementer's `SUCCESS`
       return).
   - Then proceed to the next `[ ]` step normally.
4. If no orphan commits exist, spawn the implementer for the next
   `[ ]` step from `mode=INITIAL` with `step_base_commit = HEAD`.

**Why this matters.** Without this check, the orchestrator would
spawn an implementer that re-derives changes already committed,
potentially creating duplicate or conflicting commits.

### Resume-side commit-pattern reference

When Phase B resumes and detects orphan code commits, it scans
`git log --oneline {base_commit}..HEAD` and uses these patterns
(the authoring side of the same patterns lives in
[`commit-conventions.md`](commit-conventions.md) § Commit type
prefixes):

1. **`Revert step:` commits** — the previous session rolled the step
   back after a non-`SUCCESS` review-fix attempt. The next `[ ]` step
   was already cleanly returned to its pre-implementation state by
   the revert. Read the `reason: <slug>` line in the body and
   dispatch per §Phase B Resume above — the bookkeeping differs by
   slug (write `[!]` for `failed-review-fix`; respawn-and-rederive
   for `late-design-decision`; verify-or-apply-upgrade for
   `late-risk-upgrade`).
2. **Episode commits** (`Record episode for …`, Workflow update
   touching only `_workflow/tracks/track-<N>.md`) — mark the
   boundary between completed and in-progress steps. The most
   recent episode commit is the last fully-finished step.
3. **`Review fix:` commits** — indicate the dim-review loop already
   ran for the step they belong to. When an orphan `Review fix:`
   commit appears after the last episode commit, resume from
   episode production.
4. **Implementer code commits** — touch code (paths outside
   `_workflow/`); subject is the imperative summary of the step's
   change. When an orphan implementer commit appears after the
   last episode commit without any `Review fix:` siblings, resume
   from the dimensional review loop.
5. **Other Workflow update commits** — touch only `_workflow/`
   but are not episode commits (Phase 1 init, Phase A
   decomposition, Phase B base-commit recording, plan-corrections
   application, track-completion mark, inline-replanning update).
   They are scaffolding and **not** orphans regardless of
   position.

The step file on disk is the source of truth for which steps are
complete (have episodes). Any implementer or `Review fix:` code
commits beyond the last episode commit are orphans for the next
`[ ]` step. A `Revert step:` commit cancels the implementer +
`Review fix:` commits it reverts — together they form a
self-contained "attempted and rolled back" group that does not count
toward any `[x]` step's expected commits.

---

## Non-`SUCCESS` orchestrator handlers

Triggered from the `match result.RESULT` dispatch in
[`step-implementation.md`](step-implementation.md) §Per-Step
Orchestration Loop when the implementer returns one of the three
non-`SUCCESS` results. These three handlers fire in **pre-commit**
modes (`mode=INITIAL` or `mode=WITH_GUIDANCE`); the equivalent
post-commit dispatch (from `mode=FIX_REVIEW_FINDINGS`) is in
§Post-Commit Handlers below.

### `escalate_to_user_then_respawn(step, result)`

Triggered when `result.RESULT == DESIGN_DECISION_NEEDED`. The
implementer has run the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at `step_base_commit` (no commit was
produced, and no untracked files were left behind).
`result.DESIGN_DECISION` is populated with `context`, `alternatives`,
`recommendation`, and `exploration_notes`.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. Present `result.DESIGN_DECISION` (alternatives + trade-offs +
   recommendation) to the user via the existing escalation protocol
   in [`design-decision-escalation.md`](design-decision-escalation.md).
2. On user response, respawn the implementer with:
   - `mode=WITH_GUIDANCE`
   - `Guidance:` set to the user's chosen alternative + any
     additional direction
   - `exploration_notes_echo` set to
     `result.DESIGN_DECISION.exploration_notes` so the new
     implementer skips re-derivation
3. The respawn's result re-enters the main loop at the
   `match result.RESULT` dispatch.

### `apply_upgrade_then_decide(step, result)`

Triggered when `result.RESULT == RISK_UPGRADE_REQUESTED`. The
implementer has flagged that the step is more invasive than its
tagged risk and has run the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at `step_base_commit` (no commit was
produced, and no untracked files were left behind).
`result.RISK_UPGRADE` carries `from`, `to`, `category`, and
`evidence`.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. **Apply the upgrade in place** in the step file: rewrite the
   `**Risk:**` line to the new level and append an override note
   in the form `override: upgraded mid-Phase-B (<short reason from
   result.RISK_UPGRADE.evidence>)`. The decomposer-time category
   stays in the line for traceability. Downgrades are not permitted
   — see [`risk-tagging.md`](risk-tagging.md) §Override rules.
2. **Approval flow:**
   - `medium → high`: auto-apply (no user prompt). Note the
     auto-apply in the next step-file write.
   - `low → high`: pause and confirm with the user before
     respawning. The bigger jump is more likely a planning miss.
3. After application/confirmation, respawn the implementer with
   `mode=INITIAL`. The next implementer's run is identical except
   downstream `on_success` will now run the dimensional review.
   No `exploration_notes_echo` is carried across — risk upgrades
   typically surface early, before substantial implementation
   exploration, so re-derivation is cheap and `mode=INITIAL` keeps
   the respawn contract simple. If the prior implementer's
   `RISK_UPGRADE.evidence` already names what was discovered, that
   text plus the rewritten `**Risk:**` line is enough context for
   the next implementer.

### `handle_failure(step, result)`

Triggered when `result.RESULT == FAILED` from `mode=INITIAL` or
`mode=WITH_GUIDANCE` (pre-commit failure). For `FAILED` returns from
`mode=FIX_REVIEW_FINDINGS`, the dim-review loop dispatches to
`rollback_and_handle_failure` instead — see §Post-Commit Handlers.

The implementer has already reverted any uncommitted changes and
removed any untracked artefacts
(the snapshot-and-diff revert sequence) before returning, so the
working tree is clean at `step_base_commit`. `result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`,
and `recommended_action`.

1. **Write the failed episode** to the step file from
   `result.FAILURE` (mark the step `[!]`).
2. **Insert `[ ]` retry/split rows** per the existing protocol — see
   §Step Failure below for the retry/split formats.
3. **Update the Progress section's step count** to reflect any
   inserted rows.
4. **Two-failure rule.** If the new `[!]` makes two consecutive
   `[!]` entries for the same logical step, stop and present both
   failed episodes to the user — see §Two-Failure Rule below.

The implementer never escalates directly; it returns `FAILED` with
`recommended_action: escalate`, and the orchestrator decides whether
to enter ESCALATE per [`inline-replanning.md`](inline-replanning.md).

---

## Post-Commit Handlers

When the dim-review loop's `mode=FIX_REVIEW_FINDINGS` respawn returns
a non-`SUCCESS` result, the prior step commits are still on disk.
The implementer's local revert
(the snapshot-and-diff revert sequence) only undoes its
in-progress fix attempt; rolling back the prior commits is the
orchestrator's responsibility.

The post-commit handlers all share a common rollback step:

```bash
# step_base_commit was captured at spawn time
git revert -n {step_base_commit}..HEAD
git commit -m "$(cat <<'EOF'
Revert step: <step description>

reason: <slug>

<one-sentence prose explanation, drawn from
fix_result.{FAILURE|DESIGN_DECISION|RISK_UPGRADE}>
EOF
)"
git push
```

The HEREDOC form is required for any commit message containing a blank
line — `git commit -m "…"` with literal embedded newlines is fragile
across shells. This matches the project commit-message convention in
the repo's user-global `CLAUDE.md` ("Committing changes with git"). The
`git push` after the commit follows the per-commit push rule in
`commit-conventions.md` § Push every commit (the revert is part of
the visible branch history on the draft PR).

**Body format (load-bearing for Phase B Resume).** The first
non-empty line of the body MUST be `reason: <slug>` where `<slug>`
is exactly one of three values, used by Phase B Resume to dispatch:

| Slug | Source handler | Resume dispatch |
|---|---|---|
| `failed-review-fix` | `rollback_and_handle_failure` | Write `[!]` if missing, insert retry/split rows, respawn `INITIAL` |
| `late-design-decision` | `rollback_and_escalate` | Respawn `INITIAL`; new implementer re-derives — if it returns `DESIGN_DECISION_NEEDED` again, escalate then |
| `late-risk-upgrade` | `rollback_and_upgrade` | Verify Risk line; apply upgrade if not yet rewritten; respawn `INITIAL` |

A blank line separates the slug line from the prose explanation.
Resume parses the body by reading the first body line and matching
against the slug table — do not vary spelling, capitalisation, or
position.

`git revert -n` stages the reversal of every commit in
`{step_base_commit}..HEAD` (the original implementer commit plus any
prior `Review fix:` commits in the same dim-review loop) without
auto-committing each revert. The orchestrator then commits once with
the `Revert step:` prefix per
[`commit-conventions.md`](commit-conventions.md). After the revert
commit, HEAD's tree state matches `step_base_commit` — the new HEAD
becomes the next step's `step_base_commit` for any respawn that
follows.

The rollback preserves history (the original attempt, any review
fixes, and the revert all stay in `git log`), so future investigators
can see what was tried. This is the conservative choice for a
critical-systems project; squash-merge collapses the noise at the PR
boundary.

**Pre-revert assertion.** Before running `git revert -n`, verify
`git status` is clean. The implementer's snapshot-and-diff revert
sequence should have left it clean;
if not, that is a contract violation and the orchestrator surfaces
the discrepancy to the user instead of proceeding (a dirty tree at
this point usually means the implementer exited mid-write or the
user has manual edits in flight).

### `rollback_and_handle_failure(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: FAILED` — the implementer cannot apply the review findings,
typically because the findings reveal a deeper issue than a
mechanical fix can address. `fix_result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`,
and `recommended_action`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The revert lands **before** any step-file
   write so that the only crash-recoverable state is "Revert at tip
   with step still `[ ]`" — Phase B Resume's Detection step 2
   handles that exact state via the `failed-review-fix` slug branch
   (which already covers "if no `[!]` entry exists, write it now").
   Reversing this order — writing `[!]` first — would leave a
   window where prior commits sit unreverted at HEAD with the step
   already marked `[!]`; Detection step 3 would then misattribute
   those orphan commits to the next `[ ]` step.
2. **Write the failed episode** to the step file from
   `fix_result.FAILURE` (mark the step `[!]`). The episode's
   `what_was_attempted` should describe both the original
   implementation and the review-fix attempt that failed; the
   `why_it_failed` field captures the underlying reason.
3. **Insert `[ ]` retry/split rows** per the existing protocol —
   see §Step Failure for the formats.
4. **Update the Progress section's step count** to reflect the
   inserted rows.
5. **Two-failure rule.** If the new `[!]` makes two consecutive
   `[!]` entries for the same logical step, stop and present both
   failed episodes to the user — see §Two-Failure Rule.

After this handler completes, `step_base_commit` for the retry/split
row is the new HEAD (the `Revert step:` commit).

### `rollback_and_escalate(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: DESIGN_DECISION_NEEDED` — applying the review findings
surfaced a design decision that was not visible during the original
implementation. `fix_result.DESIGN_DECISION` carries `context`,
`alternatives`, `recommendation`, and `exploration_notes`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The rollback removes the original
   implementer commit and any prior `Review fix:` commits because
   the surfaced design decision affects the step's premise — the
   user's chosen alternative may invalidate the original approach,
   so we start the next attempt from a clean slate.
2. **Present** `fix_result.DESIGN_DECISION` (alternatives + trade-offs
   + recommendation) to the user via the existing escalation protocol
   in [`design-decision-escalation.md`](design-decision-escalation.md).
   Include in the prose that this decision surfaced **post-commit
   during dim review**, so the user understands the rollback context.
3. On user response, capture the new HEAD as `step_base_commit` and
   respawn the implementer with:
   - `mode=WITH_GUIDANCE`
   - `Guidance:` set to the user's chosen alternative + any
     additional direction
   - `exploration_notes_echo` set to
     `fix_result.DESIGN_DECISION.exploration_notes`
4. The respawn's result re-enters the top-level `match result.RESULT`
   dispatch in [`step-implementation.md`](step-implementation.md)
   §Per-Step Orchestration Loop.

### `rollback_and_upgrade(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: RISK_UPGRADE_REQUESTED` — applying the review findings
revealed the step is more invasive than its tagged risk.
`fix_result.RISK_UPGRADE` carries `from`, `to`, `category`, and
`evidence`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The rollback removes the original commit
   so that the next attempt re-runs the implementation **with full
   dim-review pressure from the start** at the new risk level — not
   stacked on top of an implementation that was reviewed under the
   old risk level.
2. **Apply the upgrade in place** in the step file: rewrite the
   `**Risk:**` line and append an override note in the form
   `override: upgraded mid-Phase-B during dim review (<short reason
   from fix_result.RISK_UPGRADE.evidence>)`. The decomposer-time
   category stays for traceability. Downgrades are not permitted —
   see [`risk-tagging.md`](risk-tagging.md) §Override rules.
3. **Approval flow** (same as `apply_upgrade_then_decide`):
   - `medium → high`: auto-apply (no user prompt). Note the
     auto-apply in the step-file write.
   - `low → high`: pause and confirm with the user before respawning.
4. After application/confirmation, capture the new HEAD as
   `step_base_commit` and respawn the implementer with `mode=INITIAL`.
   The respawn's result re-enters the top-level dispatch.

### Why rollback in all three cases?

The post-commit handlers always rollback rather than try to
patch-on-top because:

- **`FAILED`**: by definition the prior commit cannot be salvaged;
  retry/split needs a clean starting point.
- **`DESIGN_DECISION_NEEDED`**: the user's chosen alternative may
  invalidate the prior implementation. Starting fresh avoids
  carrying assumptions from the old approach into a new one.
- **`RISK_UPGRADE_REQUESTED`**: the prior implementation was reviewed
  under the wrong risk level. Re-implementing with full dim-review
  pressure from the start is the only way to get the review pressure
  the upgraded risk demands.

The cost is one rerun of the implementation. For a database project,
the alternative — leaving an under-reviewed commit on disk and
trying to patch it — is the wrong tradeoff.

---

## Step Failure

If the implementer returns `RESULT: FAILED`, the implementer has
already reverted uncommitted changes and removed any untracked
artefacts (the snapshot-and-diff revert sequence). For
pre-commit failures (`mode=INITIAL` / `mode=WITH_GUIDANCE`) the
working tree is now clean at `step_base_commit` and the orchestrator
proceeds directly with the steps below. For post-commit failures
(`mode=FIX_REVIEW_FINDINGS`) the orchestrator first runs the
rollback per §Post-Commit Handlers, then proceeds with the steps
below; the retry/split rows below apply to both. The orchestrator
handles the rest:

1. **Write a failed episode** to the step file from
   `result.FAILURE` (see
   [`episode-format-reference.md`](episode-format-reference.md) for
   the failed-episode format).
2. **Decide retry vs split** based on
   `result.FAILURE.recommended_action`:
   - `retry` — keep the `[!]` entry and insert one new `[ ]` step
     immediately after it with a modified description indicating
     the different approach.
   - `split` — keep the `[!]` entry and insert multiple new `[ ]`
     steps immediately after it.
   - `escalate` — present the situation to the user and consider
     entering ESCALATE per [`inline-replanning.md`](inline-replanning.md).
3. **Update the Progress section's step count** to reflect inserted
   rows.

### Retry representation in the step file

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ...
  > **Why it failed:** ...
  > **Impact on remaining steps:** ...
  > **Key files:** ...

- [ ] Step: Add histogram header to leaf page (retry: use page extension API)
```

### Split representation in the step file

```markdown
- [!] Step: Add histogram header and serialization
  > **What was attempted:** ...
  > **Why it failed:** ...

- [ ] Step: Add histogram header struct (split from failed step above)
- [ ] Step: Add histogram serialization (split from failed step above)
```

Update the **Progress** section's step count to reflect the new
total (e.g., `(2/6 complete)` if a 5-step track gained one retry
step).

---

## Two-Failure Rule

The two-failure rule triggers when two consecutive `[!]` entries
exist for the same logical step (the retry also failed):

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ... (first attempt)

- [!] Step: Add histogram header to leaf page (retry: use page extension API)
  > **What was attempted:** ... (second attempt)
```

When this happens — whether during a session or detected on resume:

- **Stop.** Do not spawn another implementer.
- Present both failed episodes to the user with:
  - What was tried each time
  - Why it failed
  - Your assessment of whether this is a step-level issue or a
    track-level issue
- The user decides: retry with specific guidance, adjust the
  approach, skip the step, or escalate.

**On resume.** When scanning the step list and encountering a `[!]`
entry followed by another `[!]` for the same step (with `(retry:`
in the description), this is a two-failure situation. Present both
failed episodes to the user before proceeding.

---

## Track-Level Failure

If a failure undermines the track's overall approach (not just one
step — e.g., the track's foundational assumption is wrong, or
repeated step failures trace back to a common root cause the track
cannot address):

- Present the situation to the user with full context (affected
  steps, what was tried, the underlying issue).
- Recommend **ESCALATE** if the approach is fundamentally wrong
  (see [`inline-replanning.md`](inline-replanning.md)).
- The user decides how to proceed.
