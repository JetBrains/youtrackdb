# Review Mode

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §What review mode does | orchestrator,reviewer-plan,reviewer-dim-track | 2,3A,3C | Refines a gate decision through a conversational accept/edit loop. |
| §Conversational tone (load-bearing) | orchestrator | 2,3A,3C | The tone the orchestrator must adopt while accumulating observations. |
| §Approval-panel contract | orchestrator | 2,3A,3C | What the approval panel must show before any change is applied. |
| §Flow | orchestrator | 2,3A,3C | The end-to-end accumulate-detect-render-execute sequence. |
| §1. Accumulate observations across turns | orchestrator | 2,3A,3C | Collecting user observations into a buffer over multiple turns. |
| §Buffer mutation grammar | orchestrator | 2,3A,3C | Grammar for adding, editing, and removing buffered observations. |
| §2. Detect the completion signal | orchestrator | 2,3A,3C | Recognizing when the user is ready to apply the buffer. |
| §3. Render the approval panel | orchestrator | 2,3A,3C | Showing the consolidated change set for approval. |
| §4. Empty buffer at completion signal | orchestrator | 2,3A,3C | Handling a completion signal with nothing buffered. |
| §5. Execute (only on Apply) | orchestrator | 2,3A,3C | Applying buffered changes only after explicit approval. |
| §6. Re-render the gate's approval panel | orchestrator | 2,3A,3C | Re-showing the gate panel after changes land. |
| §Action types | orchestrator | 2,3A,3C | The set of buffered actions: edit plan, edit step, escalate, and others. |
| §Validation | orchestrator | 2,3A,3C | Checks run before applying buffered actions. |
| §PSI-verify named classes | orchestrator | 2,3A,3C | Confirming class names in actions resolve via PSI when reachable. |
| §mcp-steroid state handling | orchestrator | 2,3A,3C | Behavior when the IDE is or is not reachable during validation. |
| §PSI-verify failure path | orchestrator | 2,3A,3C | What to do when a PSI name check fails. |
| §Dependency validity for `EDIT_PLAN` reorders | orchestrator | 2,3A,3C | Keeping track dependencies consistent when reordering the plan. |
| §Anchor-section gate for `EDIT_PLAN` / `EDIT_STEP_DESC` | orchestrator | 2,3A,3C | Requiring a stable anchor section before editing plan or step text. |
| §Mixed-set policy | orchestrator | 2,3A,3C | Handling a buffer that mixes mechanical and design-decision actions. |
| §ESCALATE detection | orchestrator | 3A,3C | Spotting an observation that requires inline replanning. |
| §Question / clarification distinction (Pre-Flight only) | orchestrator | 3A | Separating a user question from an actionable change during Pre-Flight. |
| §Completion FIX_FINDING outcome mapping | orchestrator | 3C | Mapping a completion-time fix request to a review-finding outcome. |
| §State and resume | orchestrator | 2,3A,3C | Persisting buffer state so review mode survives a session boundary. |
| §Off-panel responses | orchestrator | 2,3A,3C | Handling user replies that fall outside the approval panel options. |

<!--Document index end-->

Shared protocol for refining a gate decision through normal
conversation. Both Track Pre-Flight (see
track-review.md:decomposer,orchestrator,reviewer-adversarial,reviewer-risk,reviewer-technical:3A § Track Pre-Flight) and Track
Completion (see track-code-review.md:orchestrator,reviewer-dim-track:3C
§ Track Completion) load this when the user picks **Review mode**
on the gate's approval panel.

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `.claude/output-styles/house-style.md`: `## Banned sentence patterns`, `## Banned analysis patterns`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:any:any `§1.5` for the workflow-level anchor and tier mapping.

## What review mode does
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-track phases=2,3A,3C summary="Refines a gate decision through a conversational accept/edit loop." -->

Gives the user a conversational refinement channel after a gate
presents its results. The user drops observations, questions, or
requested edits across as many turns as they want; the
orchestrator silently classifies and accumulates them. When the
user signals they're done, **one** approval panel surfaces the
full accumulated set for a single Apply / Refine / Cancel
decision, and only then does any side effect run.

The single end-of-conversation panel (not one after every
observation) is the audit surface that keeps the user able to
see everything that will happen before it happens.

## Conversational tone (load-bearing)
<!-- roles=orchestrator phases=2,3A,3C summary="The tone the orchestrator must adopt while accumulating observations." -->

The orchestrator never narrates the protocol's internal
structure to the user. The user-facing voice is plain chat;
type labels (`FIX_FINDING`, `QUESTION`, `ESCALATE`, etc.) and
`⚠`-warnings appear only inside the final approval panel.

- **No step labels in chat.** The orchestrator never says
  "Step 1 — free-form input", "translating to a typed action set",
  "step 4 confirmation", or any other narration of the protocol's
  internal structure.
- **No type-system vocabulary in chat.** `FIX_FINDING`,
  `QUESTION`, `ESCALATE`, `EDIT_PLAN`, `CLARIFY`, `SKIP_TRACK`,
  `EDIT_STEP_DESC`, "translator", "typed action set", "PSI-verify"
  are valid terms in this protocol and in orchestrator reasoning,
  but they do not appear in messages to the user. The single
  exception is the final approval panel (§ Flow step 3), where
  type labels appear because the panel is the explicit audit
  surface.
- **One-line conversational acks per observation.** "Got it —
  I'll qualify the comment in both spots." beats reading back the
  typed payload at the user.
- **Answer questions inline with the answer, not with
  scaffolding.** No "Here's the answer to your question:" prefix.
  PSI lookups, file reads, and other research happen silently;
  the user sees the answer.
- **Surface validation problems as ordinary chat.** "I couldn't
  find a class named `Foo.bar` — did you mean `Bar.foo`?" beats
  `⚠ Unverified production class name: Foo.bar`. The
  `⚠`-prefixed warnings exist only inside the final approval
  panel render, never in mid-conversation messages.
- **Don't read the buffer back after every turn.** A brief ack is
  enough. The user sees the full accumulated set once, in the
  approval panel.

## Approval-panel contract
<!-- roles=orchestrator phases=2,3A,3C summary="What the approval panel must show before any change is applied." -->

Both gates render their initial approval with the same three
one-step options. This is the only `AskUserQuestion` call the
gate itself makes for routine acceptance — review mode runs its
own approval panel (§ Flow step 3 below) when entered and the
user signals completion.

- **Approve**: terminal accept. The gate runs its own post-approve
  writes (Pre-Flight: strategy-refresh line + `### Clarifications`
  subsection + amendments commit per `track-review.md` § Track
  Pre-Flight step 6; Completion: track episode + collapse + `[x]`
  per `track-code-review.md` § Track Completion step 4).
- **Review mode**: enter the conversational refinement loop
  below.
- **ESCALATE**: route to
  inline-replanning.md:orchestrator:3A,3C.

Pre-Flight's Panel 1 ESCALATE sub-panel (Accept escalation /
Override, see `track-review.md` § Track Pre-Flight step 1) is
independent of this contract and unchanged — it is already a
single one-step decision.

## Flow
<!-- roles=orchestrator phases=2,3A,3C summary="The end-to-end accumulate-detect-render-execute sequence." -->

### 1. Accumulate observations across turns
<!-- roles=orchestrator phases=2,3A,3C summary="Collecting user observations into a buffer over multiple turns." -->

After the user picks **Review mode**, open the conversation: one
brief sentence inviting input, no template, no fields, no panel.
Example wording: *"Review mode — what did you notice?"* or *"Open
floor — drop observations, questions, or requested changes."*

Then loop. For each user chat message:

1. **Classify into one or more typed items** per § Action types.
   Multiple items per message are normal — preserve the order the
   user mentioned them. The classification is internal; do not
   echo type labels at the user.
2. **Verify named production classes silently** per § Validation.
   If a class name doesn't resolve, ask the user inline in chat
   for clarification *before* the item enters the buffer — do not
   accept observations that name non-existent symbols and wait
   to surface the warning at the panel. Example: *"I couldn't
   find `FreeSpaceMap.updatePageFreeSpace` in scope — could you
   confirm the package, or did you mean `…collection.v2.FreeSpaceMap`?"*
   On the user's clarification, re-verify silently and proceed.
3. **Answer questions inline.** For items the orchestrator
   classifies as questions (read-only, retrospective — see
   § Question / clarification distinction), read whatever is
   needed (conversation context, git log, plan file, source code)
   and reply with the answer in plain chat. The question and the
   answer are both retained in the accumulation buffer so the
   final approval panel can show the audit trail of what was
   asked and what was said.
4. **Acknowledge non-question items briefly.** One short line per
   observation, naming the target and the intent in normal
   language. Examples:
   - *"Got it — I'll qualify the `loadOrAddPageForWrite`
     reference in both the FSM and CDPB comments."*
   - *"Noted — skipping Track 5 with the reason you gave."*
   - *"Noted — clarification will land in the track file when you
     approve."*
   The ack names what the orchestrator will do, not what type
   the orchestrator classified the item as.
5. **Pause on deep-change shapes.** If a message looks like an
   escalation candidate (keyword detection per § ESCALATE
   detection, or anchor inside a protected section per
   § Validation — Anchor-section gate), pause inline and ask conversationally:
   *"That looks like a deeper change to Decision Records — should
   I treat it as an escalation to inline replanning?"* Do not
   silently add an escalation to a non-empty buffer; the
   Mixed-set policy depends on the user making the call
   deliberately.

The buffer is in-conversation context only; no on-disk state.
A crash drops it (see § State and resume). For how to modify
items already in the buffer (during accumulation or after
Refine), see § Buffer mutation grammar below.

### Buffer mutation grammar
<!-- roles=orchestrator phases=2,3A,3C summary="Grammar for adding, editing, and removing buffered observations." -->

A new observation can supersede or remove a prior buffered item
without going through the panel. The rules apply uniformly
during § 1's accumulation loop and after § 3's **Refine** drops
back into it. Two paths:

- **Implicit supersession by target.** If the new observation
  names the same target as a buffered item, the new one
  replaces the old. "Same target" means:
  - `FIX_FINDING` — same finding triple (file path, line range,
    root issue).
  - `EDIT_PLAN` / `EDIT_STEP_DESC` — same `old_string` anchor in
    the same target file.
  - `SKIP_TRACK` — same `track_index`.
  - `QUESTION`, `CLARIFY`, and `ESCALATE` — never supersede by
    target (they stack; escalations are deliberate). `QUESTION`
    has an explicit reshape carve-out below.

  Ack inline: *"Got it — replacing the earlier `Foo.bar` fix
  with the `putLong` version."*
- **Explicit delete patterns.** Phrases that match an enumerated
  set map to buffer-delete operations rather than new items:
  - *"drop the `<target>` fix"*, *"remove the `<target>` one"*
  - *"drop item N"*, *"remove item N"* (1-indexed against the
    ordered buffer)
  - *"forget the reorder"*, *"never mind the skip"* (resolves
    by item type when exactly one item of that type is
    buffered)
  - **Question reshape**: *"actually let me rephrase that"*,
    *"never mind, I meant <new question>"*, *"scratch that —
    <new question>"* → replace the most recent `QUESTION` item
    with the new one. The orchestrator answers the new
    question inline; the old question + answer pair leave the
    buffer. This is the swap-not-delete carve-out for
    `QUESTION`, which can't be reached by implicit
    supersession because questions have no fixed target.

  Ambiguous match (multiple buffered items fit the referent) →
  ask one inline clarifying line naming the candidates. No match
  → fall through to standard classification (the phrase becomes
  a new observation). Ack: *"Dropped — buffer is at N items."*

Buffer mutations bypass PSI verify, the anchor-section gate, and
ESCALATE keyword detection — those fire on what's added, not on
what's removed.

### 2. Detect the completion signal
<!-- roles=orchestrator phases=2,3A,3C summary="Recognizing when the user is ready to apply the buffer." -->

After every user message, check whether it carries a signal that
the user is done observing. The check runs after classification
and accumulation, so observations packaged together with a
completion signal ("fix X and apply") land in the buffer first
and then trigger the approval panel.

**Pending-inline-ask interaction.** If processing the current
message yielded any pending inline ask — PSI failure
(§ Validation PSI failure path), missing `SKIP_TRACK` reason
(§ Action types), anchor-section pause
(§ Validation — Anchor-section gate), or ESCALATE-keyword pause
(§ ESCALATE detection) — buffer the completion signal and run
the inline ask first. Recheck for a completion signal only
after every pending ask has resolved (item accepted, dropped,
or escalated). One side effect: a message like *"fix
`FooBarMissing.baz` and apply"* where `FooBarMissing` fails
PSI verify surfaces the class-name question first, and the
panel renders only after the user clarifies or drops that item.

**Clear completion signal** → render the approval panel (§ 3):

- "apply", "apply those", "apply it"
- "go ahead", "do it", "ship it", "submit"
- "that's all", "no more", "ready to apply", "ready"
- "looks good apply", "ok apply"
- "approve those changes", "approve" (when the conversation has
  observations in the buffer — see § 4 for the empty-buffer
  variant)

**Clear non-signal** → keep accumulating:

- Any input that contains a new observation, question, edit
  request, or named target
- Explicit "anything else", "wait", "actually also", "and another
  thing", "one more"

**Conversational follow-up after a just-answered question** →
keep accumulating, no clarifying line:

- Short context-bound replies: "and Y?", "why?", "really?",
  "wait what?", "ok and what about X?", "hmm".
- These have no named target or action verb on their own, but
  the prior orchestrator turn was an inline answer to a
  `QUESTION` item, so the orchestrator resolves the referent
  from conversation context and classifies the reply as a new
  `QUESTION`.

The Ambiguous check below still wins on bare approval-shaped
text — "ok" alone, "yeah", "sure" route to Ambiguous regardless
of what preceded them, because they could equally be a "yes
please apply" reaction to the prior answer.

**Ambiguous** ("ok", "yeah", "sure", short approval-shaped text
with no action verb) → ask one inline clarifying line:
*"Anything else, or shall I apply what we've got?"* Do not render
the panel yet.

**Clear discard signal** → cancel the round:

- "forget it", "forget this", "never mind", "cancel that"
- "start over", "discard", "reset", "scrap it"

Ask one inline line: *"Drop back to the approval panel? (this
will discard your {N} buffered item(s))"* On confirmation,
empty the buffer and return to the gate's approval panel
(same effect as Cancel in § 3). On denial, stay in the
accumulation loop with the buffer intact.

### 3. Render the approval panel
<!-- roles=orchestrator phases=2,3A,3C summary="Showing the consolidated change set for approval." -->

This is the **only** approval panel review mode shows. It renders
once per round, when § 2 detects a completion signal. The panel
is the audit surface: it does carry type labels and full
payloads, because the user is about to commit to side effects.

Render rules. Each item is shown with:

- **Validation-warning header** (when present from § Validation),
  rendered first, above the type label:
  - `⚠ Unverified production class name(s): <name1>, <name2>, …`
    — only items the user explicitly accepted past the inline
    clarification in § 1 step 2 reach the panel still tagged.
    Includes any `(grep-fallback)` caveat appended when
    mcp-steroid was unreachable.
  - `⚠ Reorder breaks dependencies: Track <N> requires Track <M>
    earlier` — one line per violation from § Validation.
  - `⚠ Light edit lands in protected section: <section name>
    (user override)` — when the user pushed back against the
    anchor-section gate (see § Validation — Anchor-section gate).
- Type label (`QUESTION` / `EDIT_PLAN` / `SKIP_TRACK` /
  `EDIT_STEP_DESC` / `CLARIFY` / `FIX_FINDING` / `ESCALATE`).
- One-line intent summary.
- Full payload — the proposed text for `EDIT_*`, the
  `{track_index, reason}` pair for `SKIP_TRACK`, the question
  text only for `QUESTION` (the answer was already shown inline
  in chat; see compact-render note below), the finding triple
  for `FIX_FINDING`, the deep-change description for `ESCALATE`.

**QUESTION compact render.** `QUESTION` items render one line
each — the question text only, no answer body. The user saw the
answer at accumulation time in chat; the panel is the
audit-trail commit point, not a read surface. For long Q&A
chains, this collapses what would otherwise be a wall of text
into a scannable list.

Format: a single header line `QUESTION × N (answered inline
above):` followed by one indented bullet per question with
just the question text in quotes. Three questions become four
lines (one header + three bullets), not the equivalent of
three full answers. The chat history above the panel is the
canonical transcript when the user wants to re-read an answer.

This rule applies regardless of buffer composition: pure
QUESTION-only buffers (Done label) and mixed buffers (Apply
label with QUESTIONs alongside side-effecting items) both
render QUESTIONs in compact form. Side-effecting items
(`EDIT_*`, `SKIP_TRACK`, `FIX_FINDING`, `ESCALATE`) continue
to render their full payloads — those are about to commit and
need the audit detail.

Present `AskUserQuestion` with three one-step options:

- **Apply**: execute every side-effecting item in declaration
  order per § Flow step 5. When the buffer contains only `QUESTION`
  items, the option label is **Done** instead — there is nothing
  to execute, but the user has read the answers.
- **Refine**: keep the accumulated buffer intact and drop back
  to § 1's accumulation loop. The user can now add, remove, or
  modify items via further chat ("drop the CDPB fix, keep just
  FSM", "rephrase the first one to …", "actually also …"). See
  § Buffer mutation grammar for the supersession + explicit
  delete rules. The prompt that re-opens the loop names the
  buffer state in plain language ("You've got 3 items so far —
  what to change?").
- **Cancel**: discard the buffer, return to the gate's approval
  panel as if review mode had never been entered.

Whole-set only. There is no per-item accept/reject in the panel
itself — the user either approves the proposal as a unit,
refines via chat, or cancels.

Off-panel chat replies on this panel route to implicit Refine
(buffer-preserving) — see § Off-panel responses.

### 4. Empty buffer at completion signal
<!-- roles=orchestrator phases=2,3A,3C summary="Handling a completion signal with nothing buffered." -->

If § 2's signal fires when the buffer has no items (the user
entered review mode and immediately said "apply" / "ok done" /
similar), ask one inline line: *"Nothing observed yet — drop
back to the approval panel?"* On confirmation, return to the
gate's approval panel as if review mode had not been entered. On
denial ("no, give me a moment"), stay in the accumulation loop
and expand the clarifying line: *"Type any observation and I'll
capture it; or say 'cancel' to drop back to the approval panel."*
If the user immediately re-signals completion from an empty
buffer ("apply" / "ok done" again with no observation in
between), treat as confirmed drop-back without re-asking — one
clarifying ask is the cap.

If the buffer contains only `QUESTION` items at the completion
signal, the panel still renders with the **Done / Refine /
Cancel** label set — the questions were already answered inline,
but the user gets the audit-trail render and explicitly closes
the round.

### 5. Execute (only on Apply)
<!-- roles=orchestrator phases=2,3A,3C summary="Applying buffered changes only after explicit approval." -->

**Apply preflight (dry-run every item before any side effect).**
Before running the first side-effecting item, validate each item
against the current working tree. Catch failures here so the user
sees them in the next conversation message rather than as a
mid-Apply interruption:

- `EDIT_PLAN` / `EDIT_STEP_DESC` (`Edit` mode): resolve the target
  file; locate `old_string` exactly once in the file (zero or
  multiple matches both fail). For multi-site `steroid_apply_patch`,
  parse and validate the patch against the current file contents.
- `SKIP_TRACK`: resolve `plan/track-<index>.md` on disk and
  confirm the plan-file entry for that track exists in
  `implementation-plan.md` with status `[ ]`.
- `FIX_FINDING`: re-resolve any production-class FQNs in the
  finding payload via PSI (the verification from § Validation may
  have gone stale if a long accumulation → refine cycle let HEAD
  drift). Skip if no class names are named.
- `CLARIFY` / `QUESTION` / `ESCALATE`: no preflight (no real side
  effect, or the side effect is buffer-only / routing-only).

If any preflight check fails, abort Apply with zero side effects.
Drop back to § 1's accumulation loop with one conversational line
per failed item: *"`Edit` on `plan/track-3.md` would no longer
apply — the anchor text drifted. Want to rephrase that one?"* The
rest of the buffer stays intact; the user can fix the broken
item or remove it.

If all preflights pass, capture
`pre_apply_sha = git rev-parse HEAD` in conversation context —
used by `FIX_FINDING`'s `RESULT_MISSING` discard branch
(`track-code-review.md` § Track Completion step 3) and as a safety
net for rare real-write failures (disk full, file lock).

**Execute side-effecting items.** Within a type, items run in
declaration order. Across types, ordering is fixed:

1. `EDIT_PLAN` / `EDIT_STEP_DESC` / `CLARIFY` (these can
   interleave freely; their writes do not conflict).
2. `SKIP_TRACK` runs after all light edits — its plan-entry
   rewrite and track-file deletion could invalidate an
   `EDIT_PLAN`'s `old_string` anchor if it ran first.
3. `FIX_FINDING` runs last (spawns a fresh implementer once
   the rest of the buffer has settled).

For each type:

- `EDIT_PLAN` / `EDIT_STEP_DESC`: apply via `Edit` (single site)
  or `steroid_apply_patch` (>2 sites).
- `SKIP_TRACK`: run the full track-skip.md:orchestrator:3A
  § Process for `track_index` — write the `[~]` marker plus
  `**Skipped:** <reason>` line in the plan entry, then delete
  `plan/track-<index>.md`. Track-file deletion is terminal per
  `track-skip.md` step 3.
- `CLARIFY`: append to the in-conversation clarifications buffer.
  The buffer flows to the track file's `### Clarifications`
  subsection on the gate's final Approve, per
  `track-review.md` § Track Pre-Flight step 6.
- `FIX_FINDING`: collect into the synthesised findings list.
  After all other items have run, spawn a fresh implementer with
  `level=track`, `mode=FIX_REVIEW_FINDINGS` per
  `track-code-review.md` § Track Completion step 3. See
  § Completion FIX_FINDING outcome mapping below for the four
  implementer return statuses (`SUCCESS` / `FAILED` /
  `DESIGN_DECISION` / `RESULT_MISSING`) and what each means for
  the re-render.
- `ESCALATE`: see § Mixed-set policy below. A sole `ESCALATE`
  item routes to `inline-replanning.md` immediately; mixed sets
  are refused before they reach this step.

`QUESTION` items have no execution side effect.

**Mid-Apply real-write failure.** Preflight catches most issues,
but rare failures can land after the first item has written —
`steroid_apply_patch` reporting a hunk that no longer applies
because a prior item's edit moved lines, disk full, file lock
contention, a `SKIP_TRACK` IO error after `EDIT_PLAN` already
amended the plan file. On any such failure:

- Stop after the failed item. Do NOT roll back already-applied
  items: their working-tree edits persist for the gate's
  re-render (`pre_apply_sha` captured above lets the user undo
  manually if they want).
- Surface a `**Partial Apply:**` chat message naming what
  landed and what failed, e.g.:

  ```
  Partial Apply:
  - applied: EDIT_PLAN reorder, EDIT_STEP_DESC track-3 What block
  - failed: SKIP_TRACK track-5 (track file lock — retry?)
  - unstarted: FIX_FINDING (3 findings)
  ```

- Drop back to § 1's accumulation loop with the failed item and
  every unstarted item still in the buffer. Already-applied
  items leave the buffer (their effect now lives on disk and
  will surface in § 6's gate re-render). The user can rephrase,
  drop, or retry on the next round.

### 6. Re-render the gate's approval panel
<!-- roles=orchestrator phases=2,3A,3C summary="Re-showing the gate panel after changes land." -->

After Apply completes, the gate rebuilds its presentation from the
now-updated files and re-renders the three-option approval panel
(Approve / Review mode / ESCALATE). The user sees the result of
what was just applied in the panel surface, and can pick Approve
to close the gate, re-enter Review mode to layer on more changes,
or ESCALATE.

Per-gate re-render rules:

- **Pre-Flight.** Rebuild Panel 2 from disk (plan-file entry +
  track file's four Phase 1 track-level sections —
  `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, `## Interfaces and Dependencies`). If any
  `EDIT_PLAN` item touched a
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
§ Mixed-set policy), include a `**Pending escalation:**` block in
the panel surface — below Panel 2's upcoming-track summary for
Pre-Flight, or in the track-results presentation for Completion.
The block leads with one line "From an earlier review-mode round
in this session, deferred by Strip-and-apply." followed by the
stashed description verbatim. The block stays visible across
subsequent re-renders until the slot clears.

## Action types
<!-- roles=orchestrator phases=2,3A,3C summary="The set of buffered actions: edit plan, edit step, escalate, and others." -->

Items the orchestrator classifies user input into. Types are
internal — they appear in the final approval panel render but
never in mid-conversation messages.

| Type | Payload | Side effect on Apply | Available in |
|---|---|---|---|
| `QUESTION` | Question text + orchestrator's answer (resolved at accumulation time by reading conversation context, git log, step / track episodes, plan file, and source code as needed; surfaced inline as plain chat) | None — already answered inline | Both gates |
| `EDIT_PLAN` | Path + anchor + new text. Light edits to a remaining track's plan-file entry: title, intro paragraph, scope indicators, or reorder of remaining `[ ]` tracks | Apply via `Edit` for single-site text changes (title, intro, scope) or via `steroid_apply_patch` for >2 sites **and for any reorder** (a move is a remove + insert pair and must land atomically — two chained `Edit` calls are not atomic). See `track-review.md` § Track Pre-Flight step 4 | Pre-Flight only |
| `SKIP_TRACK` | `{track_index, reason}`. `reason` is required and must be non-empty — Panel 1 reads it as the next session's just-skipped signal. If the user did not supply a reason inline, the orchestrator asks for one conversationally before the item enters the buffer | Run the full track-skip.md:orchestrator:3A § Process for `track_index`: mark `[~]`, write `**Skipped:** <reason>` line in the plan entry, delete `plan/track-<index>.md` (terminal per `track-skip.md` step 3). Re-render rules in § 6 above | Pre-Flight only |
| `EDIT_STEP_DESC` | Path + anchor + new text. Light edits to the upcoming track's track file's four Phase 1 track-level sections (`## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`) including any embedded `mermaid` diagram | Apply via `Edit` / `steroid_apply_patch` as above | Pre-Flight only |
| `CLARIFY` | Note text targeting the upcoming track | Appended to the in-conversation clarifications buffer; persisted to the track file's `### Clarifications` subsection on the gate's final Approve per `track-review.md` § Track Pre-Flight step 6 | Pre-Flight only |
| `FIX_FINDING` | `{location, issue, proposed fix}` triple | Collected into a synthesised findings list; on Apply completion, a fresh implementer is spawned with `level=track`, `mode=FIX_REVIEW_FINDINGS` per `track-code-review.md` § Track Completion step 3 | Completion only |
| `ESCALATE` | Deep-change description | Routes to `inline-replanning.md`; see § Mixed-set policy | Both gates |

Each item also carries a one-line intent summary, paraphrased
from what the user said, so the approval panel can show what the
orchestrator believes the user meant.

**`FIX_FINDING` is exempt from the review-file coverage rule (S5).**
The bulk-producer coverage rule in
conventions-execution.md:orchestrator,decomposer,implementer,reviewer-dim-step,reviewer-dim-track,reviewer-plan,reviewer-technical,reviewer-risk,reviewer-adversarial:2,3A,3B,3C,4
`§2.5` requires every bulk-producing sub-agent class to write a
file-plus-manifest review file or carry an `exempt because…`
annotation. `FIX_FINDING` is **exempt because** its finding triples are
user-sourced and already in the orchestrator's conversation context: the
user states each `{location, issue, proposed fix}` triple in chat during
accumulation, the orchestrator buffers it in-conversation, and it is
consumed by the spawned implementer in the same round (§ 5). It is never
a sub-agent fan-out whose bodies would accumulate in a long-lived
orchestrator context, so the file-plus-manifest persistence the rule
exists to enable does not apply — the same in-session-consumption
rationale as the four pure-standalone review agents and the Phase 1
cold-read.

## Validation
<!-- roles=orchestrator phases=2,3A,3C summary="Checks run before applying buffered actions." -->

Validation runs silently during accumulation. Failures are
surfaced inline in chat (§ 1 steps 2 and 5) where possible — only
items the user explicitly chose to keep despite the failure carry
a `⚠`-warning through to the approval panel.

### PSI-verify named classes
<!-- roles=orchestrator phases=2,3A,3C summary="Confirming class names in actions resolve via PSI when reachable." -->

Find-class every production-class name appearing in any
`EDIT_PLAN`, `EDIT_STEP_DESC`, or `FIX_FINDING` payload via
mcp-steroid. Use `steroid_execute_code` with
`JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))`;
construct the FQN from package context when the user supplied only
a short name — `findClass` returns null on bare short names.

The verification mechanism is the orchestrator-side complement to
the pre-write rule in track-review.md:decomposer,orchestrator,reviewer-adversarial,reviewer-risk,reviewer-technical:3A
§ Pre-write rule. Review mode is the **interactive** counterpart —
when a name fails to resolve, the orchestrator asks the user in
chat for clarification before the item enters the buffer, instead
of an autonomous hard-stop.

### mcp-steroid state handling
<!-- roles=orchestrator phases=2,3A,3C summary="Behavior when the IDE is or is not reachable during validation." -->

Matches `track-review.md` § Pre-write rule.

| State | Action |
|---|---|
| **Reachable + cwd matches** | Run PSI find-class via `steroid_execute_code` as above. |
| **Reachable + cwd mismatch** (`steroid_list_projects` reports a different project from the working tree) | Pause once per session (mcp-steroid state is session-wide) and ask the user via `AskUserQuestion` to switch the open project. Do NOT silently fall back to `find` on the first encounter — a PSI query against the wrong project produces false negatives identical to hallucinations. After the user replies, re-run `steroid_list_projects` to verify the switch happened. **Switch confirmed** → re-run PSI find-class silently and continue. **Switch did not happen** (still a mismatch, or the user dismissed without switching) → degrade to Unreachable for the rest of the session. |
| **Unreachable** | Fall back to `find . -name '<ClassName>.java'` and tag the item with a `(grep-fallback)` caveat that survives to the approval panel render. |

### PSI-verify failure path
<!-- roles=orchestrator phases=2,3A,3C summary="What to do when a PSI name check fails." -->

If PSI-verify reports a name does not resolve and the proposed
payload does not explicitly mark it as a class the action creates:
**try once silently** — read the production code or existing tests
for the named target (e.g., grep the cited package, read the
surrounding classes), derive the canonical name, and re-verify.
If the retry resolves, use the corrected name and proceed.

If after one retry the name still does not resolve, ask the user
inline: *"I couldn't find a class named `<name>` — did you mean
`<alternative>`, or should I add it as-is?"* On the user's reply:

- A correction (the user names the right class) → re-verify
  silently and proceed.
- "Drop it" / "skip that one" → discard the item from the buffer.
- "Add it anyway" → keep the item in the buffer with a
  `⚠ Unverified production class name: <name>` warning that
  surfaces in the approval panel.
- "Escalate" → promote to an `ESCALATE` item. If the buffer is
  non-empty, pause inline first (symmetric with § 1 step 5):
  *"That'll mix an escalation with the {N} item(s) already in
  the buffer. Mixed sets refuse Apply — proceed anyway?"* On
  confirmation, the ESCALATE enters the buffer subject to
  § Mixed-set policy at panel time. On denial, leave the
  original item out of the buffer (the user can re-raise it
  later).
- Anything else (off-topic chat, a new observation, a stack
  trace, a question about something unrelated) → treat as a
  new chat message and re-enter classification (§ 1 step 1).
  The failed item stays out of the buffer (effectively a
  "drop it" outcome).

### Dependency validity for `EDIT_PLAN` reorders
<!-- roles=orchestrator phases=2,3A,3C summary="Keeping track dependencies consistent when reordering the plan." -->

For any `EDIT_PLAN` item whose payload reorders the plan
checklist (changes the position of one or more remaining `[ ]`
tracks relative to each other), parse the `**Depends on:**` lines
on every remaining `[ ]` track in the proposed order and verify
each dependency target **that is itself a remaining `[ ]` track**
appears earlier in the new sequence. Dependency targets pointing
at completed (`[x]`) or skipped (`[~]`) tracks are already
satisfied and impose no ordering constraint on the remaining
sequence — skip them.

If a violation exists (Track N's `**Depends on:**` lists Track M,
Track M is still `[ ]`, but the proposed order places Track M at
or after Track N), surface it conversationally: *"That reorder
would put Track 4 before Track 2, but Track 4's `Depends on:`
lists Track 2 — do you want me to adjust the dependency, or
re-order?"*

The user's recourse mirrors the PSI failure path:

- A corrected order → re-verify silently and proceed.
- "Drop the reorder" → discard the item.
- "Keep it anyway — I'll fix the dependencies later" → keep with
  a `⚠ Reorder breaks dependencies: Track <N> requires Track
  <M> earlier` warning surfacing in the approval panel.

Non-reorder `EDIT_PLAN` items (title edit, intro paragraph edit,
scope indicator edit) skip this check — they do not change the
order of remaining tracks.

### Anchor-section gate for `EDIT_PLAN` / `EDIT_STEP_DESC`
<!-- roles=orchestrator phases=2,3A,3C summary="Requiring a stable anchor section before editing plan or step text." -->

The orchestrator's keyword-based ESCALATE detection
(§ ESCALATE detection) catches deep amendments by what the user
said. The anchor-section gate catches deep amendments by where the
edit lands. The two are complementary; either is sufficient to
promote an item to `ESCALATE`.

For each `EDIT_PLAN` / `EDIT_STEP_DESC` item, find the enclosing
section heading (H2 or H3) of the anchor in the target file.
Protected sections per file:

- **Plan file (`implementation-plan.md`)**:
  - `## Goals`
  - `## Constraints`
  - `## Architecture Notes` (entire section, including the
    `### Component Map` / `### Decision Records` /
    `### Invariants` / `### Integration Points` subsections)
- **Track file (`plan/track-<N>.md`)**:
  - Anything outside the four Phase 1 track-level sections — the
    other ten sections of the 14-section per-track shape, including
    `## Progress`, `## Surprises & Discoveries`, `## Decision Log`,
    `## Outcomes & Retrospective`, `## Concrete Steps`,
    `## Episodes`, `## Validation and Acceptance`,
    `## Idempotence and Recovery`, `## Artifacts and Notes`, and
    `## Base commit`.
  - The `### Clarifications` subsection inside
    `## Context and Orientation` (see `track-review.md` § Track
    Pre-Flight step 6 for the write rule that places it there).
    It is the only sub-section of the four Phase 1 track-level
    sections that is protected; the surrounding free-form prose
    in the four sections (intro / current-state / step-aware-plan
    / inter-track-boundary, plus any embedded `mermaid` diagram)
    remains the light-amendment zone.

  The four Phase 1 track-level sections (`## Purpose / Big Picture`,
  `## Context and Orientation`, `## Plan of Work`,
  `## Interfaces and Dependencies`) minus the `### Clarifications`
  exception above are the only light-amendment zone for review mode;
  `## Concrete Steps` in particular is Phase A decomposition's
  territory.

If the anchor falls inside a protected section, pause the
accumulation per § 1 step 5 and ask the user conversationally
whether to treat the edit as an escalation. On confirmation,
classify the item as `ESCALATE` with the note
`auto-promoted from anchor-section gate (anchor in <section name>)`
in the deep-change description, instead of the original light
edit type.

If the user pushes back ("no, just edit the line as-is"), the
item stays light and the anchor gate attaches a
`⚠ Light edit lands in protected section: <section name>
(user override)` warning that surfaces in the approval panel.
The user gets one chance to keep it light, but the panel renders
the override explicitly so it's visible at Apply time.

## Mixed-set policy
<!-- roles=orchestrator phases=2,3A,3C summary="Handling a buffer that mixes mechanical and design-decision actions." -->

If the buffer contains an `ESCALATE` item alongside any other
items (any `QUESTION` / `EDIT_*` / `SKIP_TRACK` / `CLARIFY` /
`FIX_FINDING`), the approval panel **refuses Apply** and offers
two one-step options in its place:

- **Strip and apply light items**: drop the `ESCALATE` item,
  apply the rest, return to the gate's approval panel. The user
  can pick ESCALATE there afterwards if the deep change is still
  needed. **Carry the dropped `ESCALATE` description forward** —
  capture it into a `pending_escalate_description` slot in the
  orchestrator's conversation context (single-slot, latest-wins).
  The next gate re-render surfaces it as a `**Pending escalation:**`
  block per § 6, and if the user later picks ESCALATE,
  inline-replanning step 2 reads it as the deep-change description
  instead of prompting cold. The slot clears on the gate's Approve
  (user moved on), on consumption by inline-replanning, or on
  session restart (conversation context loss — accepted).
- **Escalate now**: discard the light items, route to
  `inline-replanning.md` immediately. No stash needed; the
  description is fresh in conversation context and inline-replanning
  step 2 reads it directly.

The **Refine** and **Cancel** options remain available.

Rationale: a mixed Apply would commit light edits and then route
mid-flight to inline replanning, leaving a partial state that is
hard to interpret on resume.

## ESCALATE detection
<!-- roles=orchestrator phases=3A,3C summary="Spotting an observation that requires inline replanning." -->

The orchestrator classifies an item as `ESCALATE` when the user's
input names any of the deep-amendment categories from
`track-review.md` § Track Pre-Flight step 4:

- Decision Records, Architecture Notes, Goals, or Constraints in
  the plan file
- **Adding** a new track (requires authoring a fresh track file's
  four Phase 1 track-level sections — `## Purpose / Big Picture`,
  `## Context and Orientation`, `## Plan of Work`,
  `## Interfaces and Dependencies` — plus dependency analysis and
  design decisions, none of which review mode can do in a single
  round). **Removing**
  a remaining track is `SKIP_TRACK`, not ESCALATE — see
  § Action types; it is a single user-initiated action with a
  reason, terminal track-file delete, no design work.
- Cross-track interaction surfaces beyond pure reordering of
  remaining `[ ]` tracks
- Explicit "fundamental rework" / "redesign" / "rethink" / "this
  is wrong at the architectural level" language

Detection is best-effort, and the inline pause in § 1 step 5
gives the user the final say before the item enters the buffer.

Two complementary detections feed `ESCALATE` classification —
keyword-based on user input (this section, by *what the user
said*) and the **anchor-section gate** at classification time
(see § Validation — Anchor-section gate, by *where the edit
would land*). The two run in parallel; either is sufficient to
prompt the conversational pause.

## Question / clarification distinction (Pre-Flight only)
<!-- roles=orchestrator phases=3A summary="Separating a user question from an actionable change during Pre-Flight." -->

`QUESTION` and `CLARIFY` are easy to confuse on Pre-Flight. The
orchestrator splits user input by intent:

- *Read-only / retrospective* — "what does X do?", "why was this
  decided?", "did Y get covered in the prior track?" →
  `QUESTION`. Answered inline at accumulation time. No side
  effect.
- *Forward-looking note* — "make sure the implementer considers
  X", "don't break Y", "the new code must preserve Z" →
  `CLARIFY`. Persists to the track file's `### Clarifications`
  subsection on the gate's final Approve.
- *Mixed in one sentence* — "what does X do, and make sure we
  don't break it" → both items, in declaration order: question
  first (with answer), then clarification.

The split is reflected only in the approval panel's render. In
chat, the orchestrator answers the question inline and
acknowledges the clarification briefly ("Got it — I'll add a
note that X must be preserved").

On Completion, `CLARIFY` is not available: Completion has no
upcoming-track track file to write into. Forward-looking notes on
Completion are typically `FIX_FINDING` items ("change X to handle
the case I just noticed").

## Completion FIX_FINDING outcome mapping
<!-- roles=orchestrator phases=3C summary="Mapping a completion-time fix request to a review-finding outcome." -->

Completion `FIX_FINDING` Apply spawns an implementer with
`level=track`, `mode=FIX_REVIEW_FINDINGS` per
track-code-review.md:orchestrator,reviewer-dim-track:3C § Track Completion
step 3. This section defines what each of the four implementer
return statuses means for review-mode's three-option re-render at
Completion.

Completion FIX_FINDING does **not** reuse the §Phase C Implementer
Handlers from `track-code-review.md` — those handlers carry
per-iteration bookkeeping (3-iteration counter, Progress row,
gate-check fan-out) tied to the pre-Completion review loop, which
has already exited by the time Completion's three-option panel
renders. The `Track-level code review` Progress row is already
`[x]` and stays that way; there is no iteration counter at
Completion (user-initiated, not budget-driven; see
`track-code-review.md` § Track Completion step 5 for the matching
re-open rule).

The four implementer outcomes feed Completion's re-render
directly:

- **`SUCCESS`** (implementer committed a `Review fix:` on top of
  HEAD). Re-read the cumulative track diff (`git diff
  {base_commit}..HEAD`) and re-compile the track episode against
  the new HEAD per `track-code-review.md` § Track Completion
  step 1 (the single re-compile entry point). If the user's fixes
  were substantial enough that a gate-check run alone won't catch
  potential regressions, also re-run track-level code review
  against the new HEAD (this is a separate per-substance decision;
  not automatic). Present updated results and re-render the
  three-option panel.
- **`FAILED`** (implementer returned `level=track, status=FAILED`
  after exhausting its own internal retries). Do not write any
  Progress row. Re-read the diff and re-compile the track episode
  (the working tree may still have partial progress on HEAD).
  Surface the unfixed findings to the user in the re-render with
  a `**Review-mode fix attempt failed:**` line listing the items
  that did not land. The user picks Review mode again to refine,
  Approve to accept the track as-is despite the failure, or
  ESCALATE.
- **`DESIGN_DECISION`** (implementer returned a deferred design
  decision rather than a fix). Invoke
  design-decision-escalation.md:implementer,orchestrator:3A,3B,3C
  to walk the user through the alternatives. Treat the chosen
  alternative as a new `FIX_FINDING` and re-enter the Review-mode
  loop with that item pre-seeded in the accumulation buffer — the
  user can layer additional observations on top, then signal
  completion to render a fresh approval panel.
- **`RESULT_MISSING`** (implementer exited without producing the
  expected output — e.g., context exhaustion or crash). Present
  three sub-options via `AskUserQuestion`: **commit-as-is**
  (the implementer made partial progress on HEAD; treat it as
  `SUCCESS` and re-compile the episode), **re-spawn** (one more
  try with the same findings), **discard** (revert HEAD to
  pre-Apply via `git reset --hard {pre_apply_sha}` and re-render
  the panel as if Apply had not run). `pre_apply_sha` is captured
  by § 5's Apply preflight before any side effect runs. Off-
  panel chat replies on this sub-panel re-render the panel rather
  than routing as implicit Refine — see § Off-panel responses.

## State and resume
<!-- roles=orchestrator phases=2,3A,3C summary="Persisting buffer state so review mode survives a session boundary." -->

Review mode runs entirely in-conversation until **Apply**.
Crashes during accumulation (§ 1), completion signalling (§ 2),
or approval rendering (§ 3) lose the accumulated buffer but no
on-disk state. On resume the gate re-fires per the caller's
resume rules (`track-review.md` § Track Pre-Flight step 7 for
Pre-Flight; `workflow.md` § Startup Protocol State C sub-states
row "All steps `[x]`, code review `[x]`, track still `[ ]` in
plan" for Completion). The user re-enters review mode if needed
and drops fresh observations.

**Crashes during Execute (§ 5).** Apply preflight catches almost
all failures before any side effect runs (zero on-disk state to
recover from). The remaining real-write failures and crashes
split by gate:

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
  track-code-review.md:orchestrator,reviewer-dim-track:3C § Track Completion
  step 3 for the rule and its single code path shared between
  initial render, post-Apply re-render, and resume. This subsumes
  the prior-session-orphan-commit case.

Once **Apply** completes cleanly, the existing on-disk artifacts
carry the state forward per the caller's existing persistence
rules. Review mode owns no durable state of its own.

## Off-panel responses
<!-- roles=orchestrator phases=2,3A,3C summary="Handling user replies that fall outside the approval panel options." -->

`AskUserQuestion` panels can be dismissed without picking an
option — the user types a chat reply instead. The conversational
shape of review mode means most "panels" the user might dismiss
are actually inline questions, not formal `AskUserQuestion`
calls. The formal panels review mode does present, and their
off-panel chat behaviour:

- **Approval panel** (§ 3 — Apply / Refine / Cancel, or Done /
  Refine / Cancel for question-only buffers): off-panel chat
  routes to implicit Refine (buffer-preserving) — the chat
  message is treated as the next accumulation-loop input. The
  user can add, drop, or rephrase items in the same message that
  dismissed the panel.
- **Mixed-set policy panel** (Strip-and-apply / Escalate-now,
  plus Refine and Cancel): off-panel chat routes to implicit
  Refine (buffer-preserving) — same rule.

The gate's own approval panel (Approve / Review mode / ESCALATE)
is owned by `track-review.md` / `track-code-review.md`, not by
this protocol — off-panel behaviour there is up to those files.

**Carve-outs.** Two `AskUserQuestion` calls in review mode do
not follow the implicit-Refine rule because chat content on them
is most likely an answer to the specific prompt rather than a
refinement of the buffer:

- **cwd-mismatch pause** (§ Validation — mcp-steroid state
  handling): asks the user to switch the IDE's open project.
  Off-panel chat such as "I switched it" triggers a re-run of
  PSI-verify against the now-correct project, not a
  re-translation. Re-render the panel after the chat reply
  finishes its side effect.
- **`FIX_FINDING RESULT_MISSING` recovery** (per
  `track-code-review.md` § Track Completion step 3): chat reply
  on the commit-as-is / re-spawn / discard sub-panel is not a
  refinement of the buffer — one of the options performs
  `git reset --hard`. Re-render the panel and require an explicit
  pick.

Inline conversational asks during accumulation (§ 1 steps 2 and
5; § Validation failure paths) are normal chat — there is no
panel to dismiss, and the user simply replies in the next
message.
