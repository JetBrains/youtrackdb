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

The point is to let the user combine concerns in one round, e.g.
"why was X done this way, also rename the variable to Y, and add
a clarification that the implementer must preserve Z", instead of
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

Use `AskUserQuestion` with one labelled option (`Submit`) so the
user's input arrives via the tool's free-text override field —
the same idiom every multi-option panel exposes for off-list
answers. (Alternatively, skip the tool call entirely and accept
the user's next chat reply as the free-form input — see §Off-panel
responses for how routine panels consume chat replies.) The user
describes: observations, requested edits, missing context, or open
questions. No template, no fields.

### 2. Translate to a typed action set

Parse the input into an ordered list of items, preserving the
order the user mentioned them. Each item has a type and a payload:

| Type | Payload | Side effect on Apply | Available in |
|---|---|---|---|
| `QUESTION` | Question text + orchestrator's answer (resolved during translation by reading conversation context, git log, step / track episodes, plan file, and source code as needed) | None — already answered at render time | Both gates |
| `EDIT_PLAN` | Path + anchor + new text. Light edits to a remaining track's plan-file entry: title, intro paragraph, scope indicators, or reorder of remaining `[ ]` tracks | Apply via `Edit` for single-site text changes (title, intro, scope) or via `steroid_apply_patch` for >2 sites **and for any reorder** (a move is a remove + insert pair and must land atomically — two chained `Edit` calls are not atomic). See `track-review.md` § Track Pre-Flight step 4 | Pre-Flight only |
| `SKIP_TRACK` | `{track_index, reason}`. `reason` is required and must be non-empty — Panel 1 reads it as the next session's just-skipped signal. If the user did not supply a reason, the translator prompts for one inline before the item enters the proposal panel | Run the full [`track-skip.md`](track-skip.md) § Process for `track_index`: mark `[~]`, write `**Skipped:** <reason>` line in the plan entry, delete `tracks/track-<index>.md` (terminal per `track-skip.md` step 3). Re-render rules in step 6 below | Pre-Flight only |
| `EDIT_STEP_DESC` | Path + anchor + new text. Light edits to the upcoming track's step file `## Description` (`**What/How/Constraints/Interactions**` blocks, `mermaid` diagram) | Apply via `Edit` / `steroid_apply_patch` as above | Pre-Flight only |
| `CLARIFY` | Note text targeting the upcoming track | Appended to the in-conversation clarifications buffer; persisted to the step file's `### Clarifications` subsection on the gate's final Approve per `track-review.md` § Track Pre-Flight step 6 | Pre-Flight only |
| `FIX_FINDING` | `{location, issue, proposed fix}` triple | Collected into a synthesised findings list; on Apply completion, a fresh implementer is spawned with `level=track`, `mode=FIX_REVIEW_FINDINGS` per `track-code-review.md` § Track Completion step 3 | Completion only |
| `ESCALATE` | Deep-change description | Routes to `inline-replanning.md`; see §Mixed-set policy below | Both gates |

Each item also carries a one-line summary of the user's intent,
paraphrased from the input, so the proposal panel can show what
the orchestrator believes the user meant.

### 3. Validate action-set semantics

Two checks run before the proposal renders. Both attach warnings
to items that fail; the warnings flow through step 4's
`⚠`-headers so the user sees them before clicking Apply.

#### 3a. PSI-verify named classes

Find-class every production-class name appearing in any
`EDIT_PLAN`, `EDIT_STEP_DESC`, or `FIX_FINDING` payload via
mcp-steroid. Use `steroid_execute_code` with
`JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))`;
construct the FQN from package context when the user supplied only
a short name — `findClass` returns null on bare short names.

The verification mechanism is the orchestrator-side complement to
the pre-write rule in [`track-review.md`](track-review.md)
§ Pre-write rule. Review mode is the **interactive** counterpart —
warnings render in the action-set confirmation panel (step 4)
where the user accepts or refines, instead of an autonomous
hard-stop. The two paths share the verify mechanism and the
one-retry rule below, and diverge only on the final consent step.

**mcp-steroid state handling** (matches `track-review.md`
§ Pre-write rule):

- **Reachable + cwd matches** → run PSI find-class as above.
- **Reachable + cwd mismatch** (`steroid_list_projects` reports a
  different project from the working tree) → pause and ask the
  user via `AskUserQuestion` to switch the open project before
  proceeding. Do NOT silently fall back to `find` — a PSI query
  against the wrong project produces false negatives identical to
  hallucinations. The pause fires at most once per session
  (mcp-steroid state is session-wide); after the user switches,
  re-run translation step 2 to retry verification before reaching
  step 4.
- **Unreachable** → fall back to `find . -name '<ClassName>.java'`
  and tag the item with a `(grep-fallback)` caveat in step 4's
  render.

**Failure path** (matches `track-review.md` § Pre-write rule with
one interactive divergence at the end):

If PSI-verify reports a name does not resolve and the proposed
payload does not explicitly mark it as a class the action creates:
**try once** — read the production code or existing tests for the
named target (e.g., grep the cited package, read the surrounding
classes), derive the canonical name, and re-verify. If the retry
resolves, replace the name in the payload silently and proceed to
step 4.

If after one retry the name still does not resolve, attach a
`⚠ Unverified production class name: <name>` warning to the item.
The user's recourse in step 4's confirmation panel covers every
option the non-interactive Pre-write hard-stop offers:

| Pre-write hard-stop option | Review-mode equivalent in step 4 |
|---|---|
| Use the verified alternative | **Refine** with the corrected name |
| Drop the mention | **Refine** with the name removed |
| Escalate to inline replanning | **Cancel** back to the gate's approval panel, then pick **ESCALATE** |
| *(none — Pre-write is non-interactive)* | **Apply** — user explicitly accepts the unverified name |

The Apply-with-warnings option exists only because the user is
already in the loop; the Pre-write rule has no equivalent because
it fires inside autonomous orchestrator steps. The translator does
NOT silently drop unresolved names; warnings must reach step 4's
render so the user makes the call.

#### 3b. Dependency validity for `EDIT_PLAN` reorders

For any `EDIT_PLAN` item whose payload reorders the plan
checklist (changes the position of one or more remaining `[ ]`
tracks relative to each other), parse the `**Depends on:**` lines
on every remaining `[ ]` track in the proposed order and verify
each dependency target appears earlier in the new sequence.

If a violation exists (Track N's `**Depends on:**` lists Track M
but the proposed order places Track M at or after Track N), attach
a `⚠ Reorder breaks dependencies: Track <N> requires Track <M>
earlier` warning to the item. Multiple violations in one reorder
produce multiple lines under the same header.

User recourse in step 4 matches §3a: Refine to adjust the order,
Cancel back to the approval panel, or Apply to accept the new
dependency shape (e.g., the user knows the `**Depends on:**` is
stale and plans to amend it in a follow-up round). Apply with a
dependency warning is the explicit consent that this reorder
intentionally restructures dependencies.

Non-reorder `EDIT_PLAN` items (title edit, intro paragraph edit,
scope indicator edit) skip this check — they do not change the
order of remaining tracks.

#### 3c. Anchor-section gate for `EDIT_PLAN` / `EDIT_STEP_DESC`

The translator's keyword-based ESCALATE detection (§ESCALATE
detection) catches deep amendments by what the user said. The
anchor-section gate catches deep amendments by where the edit
lands. The two are complementary; either is sufficient to promote
an item to `ESCALATE`.

For each `EDIT_PLAN` / `EDIT_STEP_DESC` item, find the enclosing
section heading (H2 or H3) of the anchor in the target file.
Protected sections per file:

- **Plan file (`implementation-plan.md`)**:
  - `## Goals`
  - `## Constraints`
  - `## Architecture Notes` (entire section, including the
    `### Component Map` / `### Decision Records` /
    `### Invariants` / `### Integration Points` subsections)
- **Step file (`tracks/track-<N>.md`)**:
  - Anything outside `## Description` — `## Progress`,
    `## Reviews completed`, `## Steps`, `### Clarifications`.
    `## Description` is the only light-amendment zone for review
    mode; `## Steps` in particular is Phase A decomposition's
    territory.

If the anchor falls inside a protected section, **promote the
item to `ESCALATE`** at translation time. The promotion replaces
the original item; do not keep both. The replacement payload
carries:

- The user's original input as the deep-change description
- A note: `auto-promoted from anchor-section gate (anchor in
  <section name>)`

The user sees the promotion in step 4's confirmation panel with
the same render rules as keyword-detected ESCALATE items. From
there: **Apply** routes to inline replanning, **Refine** lets
the user rephrase if they meant to land outside the protected
zone, **Cancel** returns to the gate's approval panel.

Mixed-set policy (§Mixed-set policy) then applies normally — if
the action set already had light items alongside the promoted
ESCALATE, the user gets Strip-and-apply / Escalate-now in place
of Apply.

### 4. Confirm the action set

Render the typed list. Each item is shown with:

- **Validation-warning header** (when present from step 3) —
  rendered first, above the type label, so the user cannot miss
  it. One line per warning kind:
  - `⚠ Unverified production class name(s): <name1>, <name2>, …`
    from step 3a (PSI-verify), including any `(grep-fallback)`
    caveat appended when the Unreachable state forced a `find`
    fallback.
  - `⚠ Reorder breaks dependencies: Track <N> requires Track <M>
    earlier` from step 3b (dependency validity), one line per
    violation.
- Type label (`QUESTION` / `EDIT_PLAN` / `SKIP_TRACK` /
  `EDIT_STEP_DESC` / `CLARIFY` / `FIX_FINDING` / `ESCALATE`)
- One-line intent summary
- Full payload — the proposed text for `EDIT_*`, the
  `{track_index, reason}` pair for `SKIP_TRACK`, the question +
  orchestrator's answer for `QUESTION`, the finding triple for
  `FIX_FINDING`, the deep-change description for `ESCALATE`

Present `AskUserQuestion` with three one-step options:

- **Apply** — execute every side-effecting item in order (see
  §Execution below). `QUESTION` items are no-ops (already answered).
  When the proposal contains only `QUESTION` items, the option
  label is **Done** instead. There is nothing to execute, but the
  user has read the answers.
- **Refine** — discard the proposed set, return to step 1 with the
  user's prior input quoted in the new step-1 prompt body (above
  the `Submit` option), introduced as `Your prior input was: …`,
  so the user can edit or replace it rather than re-typing from
  scratch. Each round is otherwise atomic; the translator does not
  carry state across rounds.
- **Cancel** — discard the proposed set, return to the gate's
  approval panel as if review mode had never been entered.

Whole-set only. There is no per-item accept/reject — the user
either approves the proposal as a unit, refines it, or cancels.

Off-panel chat replies on this panel route to implicit Refine —
see §Off-panel responses below.

### 5. Execute (only on Apply)

**Apply preflight (dry-run every item before any side effect).**
Before running the first side-effecting item, validate each item
against the current working tree. Catch failures here so the user
sees them in the next confirmation panel rather than as a mid-Apply
interruption:

- `EDIT_PLAN` / `EDIT_STEP_DESC` (`Edit` mode): resolve the target
  file; locate `old_string` exactly once in the file (zero or
  multiple matches both fail). For multi-site `steroid_apply_patch`,
  parse and validate the patch against the current file contents.
- `SKIP_TRACK`: resolve `tracks/track-<index>.md` on disk and
  confirm the plan-file entry for that track exists in
  `implementation-plan.md` with status `[ ]`.
- `FIX_FINDING`: re-resolve any production-class FQNs in the
  finding payload via PSI (the verification from step 3 may have
  gone stale if a long Refine→Apply cycle let HEAD drift). Skip if
  no class names are named.
- `CLARIFY` / `QUESTION` / `ESCALATE`: no preflight (no real side
  effect, or the side effect is buffer-only / routing-only).

If any preflight check fails, abort Apply with zero side effects.
Return to step 1 (Refine), prepending one
`⚠ Apply preflight: <item> — <reason>` line per failed item to the
free-form prompt so the user can correct via the next round.

If all preflights pass, capture
`pre_apply_sha = git rev-parse HEAD` in conversation context — used
by `FIX_FINDING`'s `RESULT_MISSING` discard branch
(`track-code-review.md` § Track Completion step 3) and as a safety
net for rare real-write failures (disk full, file lock).

**Execute side-effecting items in declaration order:**

- `EDIT_PLAN` / `EDIT_STEP_DESC`: apply via `Edit` (single site)
  or `steroid_apply_patch` (>2 sites).
- `SKIP_TRACK`: run the full [`track-skip.md`](track-skip.md)
  § Process for `track_index` — write the `[~]` marker plus
  `**Skipped:** <reason>` line in the plan entry, then delete
  `tracks/track-<index>.md`. Step-file deletion is terminal per
  `track-skip.md` step 3.
- `CLARIFY`: append to the in-conversation clarifications buffer.
  The buffer flows to the step file's `### Clarifications`
  subsection on the gate's final Approve, per
  `track-review.md` § Track Pre-Flight step 6.
- `FIX_FINDING`: collect into the synthesised findings list.
  After all other items have run, spawn a fresh implementer with
  `level=track`, `mode=FIX_REVIEW_FINDINGS` per
  `track-code-review.md` § Track Completion step 3, which also
  defines the Completion-specific outcome mapping for the four
  implementer return statuses (`SUCCESS` / `FAILED` /
  `DESIGN_DECISION` / `RESULT_MISSING`). Completion FIX_FINDING
  does **not** reuse the §Phase C Implementer Handlers (those carry
  per-iteration bookkeeping for the pre-Completion review loop,
  which has already exited); the spec lives at the Completion
  callsite.
- `ESCALATE`: see §Mixed-set policy below. A sole `ESCALATE` item
  routes to `inline-replanning.md` immediately; mixed sets are
  refused before they reach this step.

`QUESTION` items have no execution side effect.

### 6. Re-render the gate's approval panel

After Apply completes, the gate rebuilds its presentation from the
now-updated files and re-renders the three-option approval panel
(Approve / Review mode / ESCALATE).

Per-gate re-render rules:

- **Pre-Flight.** Rebuild Panel 2 from disk (plan-file entry + step
  file `## Description`). If any `EDIT_PLAN` item touched a
  remaining track, re-run Panel 1's strategy assessment before
  re-rendering, since the touched track may have changed the
  look-back picture. If an `EDIT_PLAN` reorder changed which track
  is "next", Panel 2 is rebuilt against the new upcoming track per
  `track-skip.md` step 2's panel-rendering contract. If any
  `SKIP_TRACK` item ran, re-run Panel 1's strategy assessment with
  the just-skipped track as the new look-back anchor (its
  `**Skipped:** <reason>` line serves as the just-skipped-track
  signal); if the skipped track was the upcoming track summarised in
  Panel 2, Panel 2 is rebuilt against the new upcoming track per
  `track-skip.md` step 2's panel-rendering contract.
- **Completion.** Rebuild the track-results presentation. If
  `FIX_FINDING` items spawned an implementer that produced a
  `Review fix:` commit, re-read the cumulative track diff
  (`git diff {base_commit}..HEAD`) and re-compile the track
  episode before re-rendering.

**Pending-escalation block** (both gates). If
`pending_escalate_description` is set in conversation context
when re-rendering (captured by a prior Strip-and-apply per
§Mixed-set policy), include a `**Pending escalation:**` block in
the panel surface — below Panel 2's upcoming-track summary for
Pre-Flight, or in the track-results presentation for Completion.
The block leads with one line "From an earlier review-mode round
in this session, deferred by Strip-and-apply." followed by the
stashed description verbatim. The block stays visible across
subsequent re-renders until the slot clears.

## Mixed-set policy

If the proposed action set contains an `ESCALATE` item alongside
any other items (any `QUESTION` / `EDIT_*` / `SKIP_TRACK` /
`CLARIFY` / `FIX_FINDING`), the confirmation panel **refuses Apply**
and offers two one-step options in its place:

- **Strip and apply light items** — drop the `ESCALATE` item,
  apply the rest, return to the gate's approval panel. The user
  can pick ESCALATE there afterwards if the deep change is still
  needed. **Carry the dropped `ESCALATE` description forward** —
  capture it into a `pending_escalate_description` slot in the
  orchestrator's conversation context (single-slot, latest-wins).
  The next gate re-render surfaces it as a `**Pending escalation:**`
  block per step 6 below, and if the user later picks ESCALATE,
  inline-replanning step 2 reads it as the deep-change description
  instead of prompting cold. The slot clears on the gate's Approve
  (user moved on), on consumption by inline-replanning, or on
  session restart (conversation context loss — accepted).
- **Escalate now** — discard the light items, route to
  `inline-replanning.md` immediately. No stash needed; the
  description is fresh in conversation context and inline-replanning
  step 2 reads it directly.

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
- **Adding** a new track (requires authoring a fresh step file
  `## Description`, dependency analysis, and design decisions —
  none of which review mode can do in a single round). **Removing**
  a remaining track is `SKIP_TRACK`, not ESCALATE — see §2's
  action-type table; it is a single user-initiated action with a
  reason, terminal step-file delete, no design work.
- Cross-track interaction surfaces beyond pure reordering of
  remaining `[ ]` tracks
- Explicit "fundamental rework" / "redesign" / "rethink" / "this
  is wrong at the architectural level" language

Detection is best-effort. The action-set confirmation panel in
step 4 is the safety net, so the orchestrator does not need to
classify perfectly. The user can Refine if the translator over-
or under-classified, and the Mixed-set policy above prevents a
mis-classified item from being smuggled into a light Apply.

Two complementary detections feed `ESCALATE` classification —
keyword-based on user input (this section, by *what the user
said*) and the **anchor-section gate** at translation time (see
§ Loop step 3c, by *where the edit would land*). The two run in
parallel; either is sufficient to promote an item. Keyword
detection catches "this is fundamental rework"-shaped phrasing
that doesn't yet have an anchor; the anchor gate catches edits
whose anchor falls inside a protected section regardless of how
the user phrased the request.

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

On Completion, `CLARIFY` is not available: Completion has no
upcoming-track step file to write into. Forward-looking notes on
Completion are typically `FIX_FINDING` items ("change X to handle
the case I just noticed").

## State and resume

Review mode runs entirely in-conversation until **Apply**.
Crashes during steps 1-4 (input, translate, PSI-verify, confirm)
lose the proposed action set but no on-disk state. On resume the
gate re-fires per the caller's resume rules (`track-review.md`
§ Track Pre-Flight step 7 for Pre-Flight; `workflow.md`
§ Startup Protocol State C sub-states row "All steps `[x]`, code
review `[x]`, track still `[ ]` in plan" for Completion). The user
re-enters review mode if needed.

**Crashes during step 5 (Execute).** Apply preflight catches almost
all failures before any side effect runs (zero on-disk state to
recover from). The remaining real-write failures and crashes split
by gate:

- **Pre-Flight.** Successful Apply items land as uncommitted
  working-tree edits — the durable commit happens only at
  `track-review.md` § Track Pre-Flight step 6 after the user picks
  **Approve**. A mid-round crash therefore leaves a partially
  edited working tree with no on-disk marker for which items ran.
  This is intentional: the gate re-reads files from disk on
  resume and surfaces them as the current state. The user reviews
  the partial-round content in Panel 2 of the next re-render, the
  same way they review any other in-loop edit, and can refine,
  approve, or escalate from there. The `### Clarifications`
  in-conversation buffer is lost on crash; the user re-enters any
  still-relevant clarifications via a new review-mode round.

- **Completion.** A `FIX_FINDING` Apply may land `Review fix:`
  commit(s) on HEAD via a spawned implementer that exits or
  crashes before the orchestrator re-compiles the track episode.
  On State C re-entry (the gate's resume row), the Completion
  re-render **always** re-reads `git diff {base_commit}..HEAD`
  and re-compiles the track episode against the current HEAD
  before presenting the three-option panel — see
  [`track-code-review.md`](track-code-review.md) § Track Completion
  step 3 for the rule and its single code path shared between
  initial render, post-Apply re-render, and resume. This subsumes
  the prior-session-orphan-commit case.

Once **Apply** completes cleanly, the existing on-disk artifacts
carry the state forward per the caller's existing persistence
rules. Review mode owns no durable state of its own.

## Off-panel responses

`AskUserQuestion` panels can be dismissed without picking an
option — the user types a chat reply instead. For routine
action-set panels in this protocol, the chat text replaces the
prior round's free-form input and feeds step 1's translator
directly, as if the user had clicked **Refine** and re-submitted.

This rule applies to:

- **Step 4 action-set confirmation panel** (Apply / Refine /
  Cancel, or Done / Refine / Cancel for question-only proposals).
- **Empty/no-op input recovery panel** (Approve / Refine / Cancel
  per §Empty / no-op input).
- **Mixed-set policy panel** (Strip-and-apply / Escalate-now,
  plus Refine and Cancel per §Mixed-set policy).

The fall-through is well-behaved for approval-shaped chat: the
translator extracts zero items from text like "looks fine, ship
it", which lands in the §Empty / no-op input recovery and
prompts the user to pick **Approve** explicitly. One extra round;
no lost state.

Step 1's input panel itself does not need this rule — it already
accepts chat replies directly by design.

**Carve-outs.** Two panels in review mode do not follow the
implicit-Refine rule because chat content on them is most likely
an answer to the specific prompt rather than a refinement of an
action set:

- **cwd-mismatch pause** (step 3a): asks the user to switch the
  IDE's open project. Off-panel chat such as "I switched it"
  triggers a re-run of PSI-verify against the now-correct project,
  not a re-translation. Re-render the panel after the chat reply
  finishes its side effect.
- **`FIX_FINDING RESULT_MISSING` recovery** (per
  `track-code-review.md` § Track Completion step 3): chat reply
  on the commit-as-is / re-spawn / discard sub-panel is not a
  refinement of an action set — one of the options performs
  `git reset --hard`. Re-render the panel and require an explicit
  pick.

The gate's own approval panel (Approve / Review mode / ESCALATE)
is owned by `track-review.md` / `track-code-review.md`, not by
this protocol — off-panel behaviour there is up to those files.
