# Review Mode

Shared protocol for refining a gate decision in free form. Both
Track Pre-Flight (see [`track-review.md`](track-review.md) § Track
Pre-Flight) and Track Completion (see
[`track-code-review.md`](track-code-review.md) § Track Completion)
load this when the user picks **Review mode** on the gate's
approval panel.

## What review mode does

Replaces the previous multi-option panels (Adjust / Clarify on
Pre-Flight; Fixes needed on Completion) with a free-form input
loop. The user types observations, questions, or requested edits.
The orchestrator translates the input into a typed action set,
PSI-verifies any named production classes, and asks the user to
confirm the set before applying. After Apply, the gate's original
approval panel re-renders.

The point is to let the user combine concerns in one round —
"why was X done this way, also rename the variable to Y, and add
a clarification that the implementer must preserve Z" — instead of
picking categorical buckets across multiple rounds.

## Approval-panel contract

Both gates render their approval with the same three one-step
options. This is the only `AskUserQuestion` call the gate itself
makes for routine acceptance — review mode runs its own confirmation
panel (step 4 below) when entered.

- **Approve** — terminal accept. The gate runs its own post-approve
  writes (Pre-Flight: strategy-refresh line + `### Clarifications`
  subsection + amendments commit per `track-review.md` § Track
  Pre-Flight step 6; Completion: track episode + collapse + `[x]`
  per `track-code-review.md` § Track Completion step 4).
- **Review mode** — enter the loop below.
- **ESCALATE** — route to
  [`inline-replanning.md`](inline-replanning.md).

Pre-Flight's Panel 1 ESCALATE sub-panel (Accept escalation /
Override, see `track-review.md` § Track Pre-Flight step 1) is
independent of this contract and unchanged — it is already a
single one-step decision.

## Loop

### 1. Free-form input

Single open prompt via `AskUserQuestion` with one option labelled
"Submit" and a free-form text capture (or the user's normal reply
to the orchestrator's prompt). User describes: observations,
requested edits, missing context, open questions, or anything else
relevant to the gate's decision. No template, no fields.

### 2. Translate to a typed action set

Parse the input into an ordered list of items, preserving the
order the user mentioned them. Each item has a type and a payload:

| Type | Payload | Side effect on Apply | Available in |
|---|---|---|---|
| `QUESTION` | Question text + orchestrator's answer (resolved during translation by reading conversation context, git log, step / track episodes, plan file, and source code as needed) | None — already answered at render time | Both gates |
| `EDIT_PLAN` | Path + anchor + new text. Light edits to a remaining track's plan-file entry: title, intro paragraph, scope indicators, or reorder of remaining `[ ]` tracks | Apply via `Edit` (single site) or `steroid_apply_patch` (>2 sites) per `track-review.md` § Track Pre-Flight step 4 | Pre-Flight only |
| `EDIT_STEP_DESC` | Path + anchor + new text. Light edits to the upcoming track's step file `## Description` (`**What/How/Constraints/Interactions**` blocks, `mermaid` diagram) | Apply via `Edit` / `steroid_apply_patch` as above | Pre-Flight only |
| `CLARIFY` | Note text targeting the upcoming track | Appended to the in-conversation clarifications buffer; persisted to the step file's `### Clarifications` subsection on the gate's final Approve per `track-review.md` § Track Pre-Flight step 6 | Pre-Flight only |
| `FIX_FINDING` | `{location, issue, proposed fix}` triple | Collected into a synthesised findings list; on Apply completion, a fresh implementer is spawned with `level=track`, `mode=FIX_REVIEW_FINDINGS` per `track-code-review.md` § Track Completion step 3 | Completion only |
| `ESCALATE` | Deep-change description | Routes to `inline-replanning.md`; see §Mixed-set policy below | Both gates |

Each item also carries a one-line summary of the user's intent,
paraphrased from the input, so the proposal panel can show what
the orchestrator believes the user meant.

### 3. PSI-verify named classes

Before rendering the proposal, find-class every production-class
name appearing in any `EDIT_PLAN`, `EDIT_STEP_DESC`, or
`FIX_FINDING` payload via mcp-steroid — same rule as the pre-write
verification in [`track-review.md`](track-review.md) § Pre-write
rule. Use `steroid_execute_code` with
`JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))`;
construct the FQN from package context when the user supplied only
a short name. Names that do not resolve attach a
`⚠ unverified: <name>` warning to the item in step 4's render —
the user can correct via Refine.

When mcp-steroid is unreachable per the SessionStart hook, fall
back to `find . -name '<ClassName>.java'` and mark the item with a
`(grep-fallback)` caveat. The translator does NOT silently drop
unresolved names; the user must see them.

### 4. Confirm the action set

Render the typed list. Each item is shown with:

- Type label (`QUESTION` / `EDIT_PLAN` / `EDIT_STEP_DESC` /
  `CLARIFY` / `FIX_FINDING` / `ESCALATE`)
- One-line intent summary
- Full payload — the proposed text for `EDIT_*`, the question +
  orchestrator's answer for `QUESTION`, the finding triple for
  `FIX_FINDING`, the deep-change description for `ESCALATE`
- Any unverified-class warning from step 3

Present `AskUserQuestion` with three one-step options:

- **Apply** — execute every side-effecting item in order (see
  §Execution below). `QUESTION` items are no-ops (already answered).
  When the proposal contains only `QUESTION` items, the option
  label is **Done** instead — there is nothing to execute, but the
  user has read the answers.
- **Refine** — discard the proposed set, return to step 1 with the
  user's prior input shown as a verbatim quote for context. Each
  round is otherwise atomic — the translator does not carry state
  across rounds.
- **Cancel** — discard the proposed set, return to the gate's
  approval panel as if review mode had never been entered.

Whole-set only. There is no per-item accept/reject — the user
either approves the proposal as a unit, refines it, or cancels.

### 5. Execute (only on Apply)

For each side-effecting item, in declaration order:

- `EDIT_PLAN` / `EDIT_STEP_DESC`: apply via `Edit` (single site)
  or `steroid_apply_patch` (>2 sites).
- `CLARIFY`: append to the in-conversation clarifications buffer.
  The buffer flows to the step file's `### Clarifications`
  subsection on the gate's final Approve, per
  `track-review.md` § Track Pre-Flight step 6.
- `FIX_FINDING`: collect into the synthesised findings list.
  After all other items have run, spawn a fresh implementer with
  `level=track`, `mode=FIX_REVIEW_FINDINGS` per
  `track-code-review.md` § Track Completion step 3 and route its
  return through the same handlers (`handle_iteration_success` /
  `handle_iteration_failure` / `handle_result_missing`).
- `ESCALATE`: see §Mixed-set policy below — a sole `ESCALATE` item
  routes to `inline-replanning.md` immediately; mixed sets are
  refused before they reach this step.

`QUESTION` items have no execution side effect.

### 6. Re-render the gate's approval panel

After Apply completes, the gate rebuilds its presentation from the
now-updated files and re-renders the three-option approval panel
(Approve / Review mode / ESCALATE). The user can approve, re-enter
review mode for more refinement, or escalate.

Per-gate re-render rules:

- **Pre-Flight.** Rebuild Panel 2 from disk (plan-file entry + step
  file `## Description`). If any `EDIT_PLAN` item touched a
  remaining track, re-run Panel 1's strategy assessment before
  re-rendering — the touched track may have changed the look-back
  picture. If an `EDIT_PLAN` reorder changed which track is "next",
  Panel 2 is rebuilt against the new upcoming track per
  `track-skip.md` step 2's panel-rendering contract.
- **Completion.** Rebuild the track-results presentation. If
  `FIX_FINDING` items spawned an implementer that produced a
  `Review fix:` commit, re-read the cumulative track diff
  (`git diff {base_commit}..HEAD`) and re-compile the track
  episode before re-rendering.

## Mixed-set policy

If the proposed action set contains an `ESCALATE` item alongside
any other items (any `QUESTION` / `EDIT_*` / `CLARIFY` /
`FIX_FINDING`), the confirmation panel **refuses Apply** and offers
two one-step options in its place:

- **Strip and apply light items** — drop the `ESCALATE` item,
  apply the rest, return to the gate's approval panel. The user
  can pick ESCALATE there afterwards if the deep change is still
  needed.
- **Escalate now** — discard the light items, route to
  `inline-replanning.md` immediately.

The **Refine** and **Cancel** options remain available.

Rationale: a mixed Apply would commit light edits and then route
mid-flight to inline replanning, leaving a partial state that is
hard to interpret on resume.

## ESCALATE detection

The translator classifies an item as `ESCALATE` when the user's
input names any of the deep-amendment categories from
`track-review.md` § Track Pre-Flight step 4:

- Decision Records, Architecture Notes, Goals, or Constraints in
  the plan file
- Adding or removing tracks
- Cross-track interaction surfaces beyond pure reordering of
  remaining `[ ]` tracks
- Explicit "fundamental rework" / "redesign" / "rethink" / "this
  is wrong at the architectural level" language

Detection is best-effort — the orchestrator does not need to be
perfect at classification because the action-set confirmation
panel (step 4) is the safety net. The user can Refine if the
translator over- or under-classified, and the Mixed-set policy
above prevents a mis-classified item from being smuggled into a
light Apply.

## Empty / no-op input

- **Zero items extracted** (e.g., user typed "looks fine"): prompt
  *"No actions extracted — did you mean to Approve?"* with options
  **Approve / Refine / Cancel**. **Approve** here is a shortcut
  back to the gate's approval panel pre-selected to Approve.
- **Question-only input**: produces a `QUESTION`-only proposal.
  Step 4's confirmation panel uses the **Done / Refine / Cancel**
  label set — no Apply, since there is nothing to execute.

## Question / CLARIFY distinction (Pre-Flight only)

`QUESTION` and `CLARIFY` are easy to confuse on Pre-Flight. The
translator splits user input by intent:

- *Read-only / retrospective* — "what does X do?", "why was this
  decided?", "did Y get covered in the prior track?" → `QUESTION`.
  Answered inline at proposal time. No side effect.
- *Forward-looking note* — "make sure the implementer considers
  X", "don't break Y", "the new code must preserve Z" →
  `CLARIFY`. Persists to the step file's `### Clarifications`
  subsection on the gate's final Approve.
- *Mixed in one sentence* — "what does X do, and make sure we
  don't break it" → both items, in declaration order: `QUESTION`
  first (with answer), then `CLARIFY`.

The proposal panel renders the split so the user can correct via
Refine if the translator misjudged.

On Completion, `CLARIFY` is not available — Completion has no
upcoming-track step file to write into. Forward-looking notes on
Completion are typically expressed as `FIX_FINDING` items
("change X to handle the case I just noticed") rather than
clarifications.

## State and resume

Review mode runs entirely in-conversation until **Apply**.
Crashes mid-loop lose the proposed action set but no on-disk
state. On resume the gate re-fires per the caller's resume rules
(`track-review.md` § Track Pre-Flight step 7 for Pre-Flight;
`track-code-review.md` State C row "All steps `[x]`, code review
`[x]`, track still `[ ]` in plan" for Completion). The user
re-enters review mode if needed.

Once **Apply** executes, the existing on-disk artifacts carry
the state forward per the caller's existing persistence rules.
Review mode itself does not own any durable state — it is a
translator + executor that produces the same kinds of edits and
commits the prior multi-option panels did, only with a different
UI on top.
