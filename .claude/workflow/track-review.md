# Track Execution — Phase A: Review + Decomposition

## Overview

This document covers Phase A only. A track goes through three sub-phases,
each executed in a **separate session**:

1. **Phase A: Review + Decomposition** — this document (current session)
2. **Phase B: Step Implementation** — see
   [`step-implementation.md`](step-implementation.md) (next session)
3. **Phase C: Code Review + Track Completion** — see
   [`track-code-review.md`](track-code-review.md) (session after Phase B)

Phase C includes both the track-level code review and track completion
(episode compilation, plan corrections, user approval) in a single session.

---

## Phase A: Review + Decomposition

> **In this phase, you are a reviewer and planner, not an implementer. You
> NEVER edit source code, test files, or build files. You explore the
> codebase (read-only) to validate the track's approach and decompose it
> into steps. The only file you write is the step file
> (`tracks/track-N.md`).**

### Tooling — PSI is required for symbol audits in Phase A

Phase A's outputs (review findings, scope-indicator validation, step
decomposition with risk tags) ride on reference-accuracy facts —
"this method's callers", "this interface's implementations", "no
existing consumer for this slot". When mcp-steroid is reachable per
the SessionStart hook, those facts MUST come from PSI find-usages
rather than grep — see [`conventions.md`](conventions.md) §1.4
*Tooling discipline* for the full rule (preflight via
`steroid_list_projects`, cwd-mismatch handling, fallback when
unreachable). Run the preflight once before the first symbol audit;
do not re-probe.

Two recipes in [`conventions.md`](conventions.md) §1.4 *Recipes* are
particularly load-bearing during Phase A:

- **`hierarchy-search`** — when assessing a track that touches an SPI
  or an interface with multiple implementers (storage engines, index
  variants, SQL functions, collation strategies), load this recipe
  to enumerate every implementer / override before approving the
  track's scope. Grep on `extends X` or `implements Y` misses
  indirect chains and generic supertypes.
- **`call-hierarchy`** — when assessing a track that changes a
  low-level signature, load this recipe to walk the upward call tree
  and judge propagation distance. Immediate-callers grep is not
  enough at depths >1.

The Phase A review sub-agents you spawn (technical, risk, adversarial)
all default to grep unless their prompts explicitly route them to
PSI. The canonical prompts under `prompts/` already include this
instruction — keep it intact when customising.

### Pre-write rule — PSI-verify class names

Before any write that names a production class in the step file's
`## Description` (`**What/How/Constraints/Interactions**` blocks,
including light amendments committed via the Track Pre-Flight gate's
step 4) or in decomposed step bodies under `## Steps`, the orchestrator
MUST PSI-verify every named class via `mcp-steroid` find-class. Use
`steroid_execute_code` with
`JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))`.
If the orchestrator only has a short name (e.g., `BTree`), construct
the FQN from package context first — `findClass` returns null on bare
short names.

Pattern-inducing class names from precedent is a known trap: the
V1 → V2/V3 naming pattern often does NOT survive a generic-extraction
refactor. For example, the live v3 single- and multi-value B-tree
lifecycle classes are NOT `CellBTreeSingleValueV3` /
`CellBTreeMultiValueV2` / `SBTreeV2` — they collapsed into a single
generic
`com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`
that is wrapped twice from
`core/.../index/engine/v1/BTreeMultiValueIndexEngine.java:77,81`.
When the orchestrator infers a class name from sibling-version
conventions, generated-code conventions, or package-naming precedent
rather than reading existing tests or production callers, it MUST
verify the name via PSI before committing it to the step file.

**mcp-steroid state handling.** The session-start hook surfaces one
of three states per [`conventions.md`](conventions.md) §1.4:

- **Reachable + cwd matches** → run PSI find-class as above.
- **Reachable + cwd mismatch** (`steroid_list_projects` reports a
  different project from the working tree) → pause and ask the user
  via `AskUserQuestion` to switch the open project before proceeding.
  Do NOT silently fall back to `find` — a PSI query against the wrong
  project produces false negatives that look identical to a true
  hallucination.
- **Unreachable** → fall back to `find . -name '<ClassName>.java'`
  and add a reference-accuracy caveat to the step file's
  `### Clarifications` subsection.

**Failure path.** If PSI-verify reports a name does not resolve and
the track's `## Description` does not explicitly mark it as a class
this track creates: try once — read the production code or existing
tests for the named target, derive the canonical name, and re-verify.
If after one retry the name still does not resolve, do NOT write or
commit the step file. Surface the conflict via `AskUserQuestion` with
three options: **Use the verified alternative** (orchestrator proposes
the closest matching name), **Drop the mention** (remove the reference
from the step body), **Escalate to inline replanning**.

This rule applies to non-interactive orchestrator writes (Phase 1
plan write, Phase A decomposition writes, inline-replanning writes,
autonomous strategy-refresh writes). Interactive writes through
review mode (Track Pre-Flight and Track Completion) follow the same
verify mechanism, the same one-retry rule, and the same mcp-steroid
state handling, but surface failures conversationally inline as
the user accumulates observations, and let the final approval
panel carry any `⚠`-warnings that survived past the inline
clarification — see [`review-mode.md`](review-mode.md)
§ Validation for the mapping. The user's Refine / Cancel paths in
review mode cover Use-alternative / Drop / Escalate; Apply with a
warning still attached is the explicit user consent step that
does not exist (and could not exist) in the non-interactive
path.

This rule is the orchestrator-side complement to the sub-agent-side
PSI rule in §Tooling above.

### Track Pre-Flight — Strategy Assessment + Track Summary

Before Phase A's reviews and decomposition begin, the orchestrator
runs a single Pre-Flight gate that combines a backward-looking
strategy assessment (when an earlier track has just completed or
been skipped) with a forward-looking summary of the upcoming track.
The gate is the user's chance to refine the plan / step file before
sub-agents start reading them — via free-form review mode (see
[`review-mode.md`](review-mode.md)) for light edits, clarifications,
questions, skipping a remaining track, and combinations thereof —
or to escalate to inline replanning when the change is deep.

The gate fires once per fresh Phase A entry. State C resume (the
step file's `## Description` already carries the agreed-upon track
plan, including any prior clarifications) **skips** the gate; the
user saw it in the original session and the description is already
authoritative. The gate is also re-runnable on session resume (no
review has yet been recorded in `## Reviews completed` — see
§Phase A Resume) — the resume idempotency rule at the end of this
section governs that case.

**1. Build Panel 1 — strategy assessment (look-back).**

Skip Panel 1 if there is no completed or skipped track yet (the
very first Phase A entry of the plan) — there is nothing to look
back at.

Otherwise, read the just-completed (or just-skipped) track's
episode plus the accumulated episodes of all earlier completed
tracks, and assess the remaining tracks against discoveries:

- Do any track episodes contradict assumptions in upcoming tracks?
- Has the Component Map changed in ways affecting remaining tracks?
- Are any Decision Records weakened by what was learned?
- Are there new dependencies between tracks not in the original plan?

Produce an assessment — `CONTINUE`, `ADJUST`, or `ESCALATE` —
with a short rationale. The assessment is rendered inline in
Panel 1 of the user-facing summary; nothing is persisted to disk
yet.

If the assessment is `ESCALATE` (accumulated discoveries
fundamentally changed the picture), present Panel 1 alone to the
user using `AskUserQuestion` with two options — **Accept
escalation** and **Override**. On **Accept**, route to
[`inline-replanning.md`](inline-replanning.md) immediately and do
NOT render Panel 2. On **Override** (the user disagrees with the
ESCALATE recommendation), fall through to step 2: build Panel 2
and present the full three-option gate in step 3, treating the
overridden assessment as `CONTINUE` for the purpose of step 6's
on-disk strategy-refresh line.

**2. Build Panel 2 — track summary (look-forward).**

Read the plan-file Track N entry (title, intro paragraph, scope
indicators, any inline notes) and the upcoming track's step file
`## Description` section
(`**What/How/Constraints/Interactions**` plus any `mermaid`
diagram, written by `create-plan` at Phase 1 and possibly amended
by prior Pre-Flight rounds or inline replanning, including any
`### Clarifications` subsection from a prior gate session).
Render the summary inline.

**3. Ask the user.** This step runs only if step 1 did not exit
the gate — i.e., Panel 1 was skipped (no anchor track), Panel 1's
assessment was `CONTINUE` or `ADJUST`, or Panel 1's `ESCALATE` was
overridden. If step 1 routed to inline replanning via `Accept
escalation`, the gate has already exited and this step does not
run.

Render Panel 1 (if active) followed by Panel 2, and use
`AskUserQuestion` with three one-step options per the approval-panel
contract in [`review-mode.md`](review-mode.md) § Approval-panel
contract:

- **Approve** — accept the assessment and proceed to step 6
  (persist) below with whatever clarifications buffer (see step 5)
  and on-disk amendments the review-mode loop has accumulated
  (empty on the first render, populated if earlier rounds applied
  items).
- **Review mode** — enter the conversational refinement loop per
  [`review-mode.md`](review-mode.md) § Flow.

  The user drops observations across as many chat turns as they
  want; the orchestrator silently classifies and accumulates them.
  When the user signals completion, one approval panel surfaces
  the accumulated set. On Apply, the orchestrator executes
  `EDIT_PLAN` / `EDIT_STEP_DESC` items against the plan / step
  files, appends `CLARIFY` items to the in-conversation
  clarifications buffer (step 5 below); `QUESTION` items were
  already answered inline as they came in (no side effect at
  Apply time). `FIX_FINDING` is not available on Pre-Flight (see
  [`review-mode.md`](review-mode.md) § Action types).

  After Apply, return here: re-render Panel 1 (re-running the
  strategy assessment if any `EDIT_PLAN` item touched a remaining
  track, since the touched track may have changed the look-back
  picture) and Panel 2 from the now-updated files; re-ask.
- **ESCALATE** — route to inline replanning per
  [`inline-replanning.md`](inline-replanning.md).

The three-option panel re-renders after every review-mode Apply
until the user picks **Approve** or **ESCALATE**. Step 4 below
defines the light-vs-deep boundary; review mode's classification
and Mixed-set policy enforce it (see
[`review-mode.md`](review-mode.md) § Mixed-set policy).

If an `EDIT_PLAN` reorder during review-mode Apply changes which
track is "next", Panel 2 is rebuilt against the new upcoming track
per [`track-skip.md`](track-skip.md) step 2's panel-rendering
contract.

**4. Apply amendments — light vs deep boundary.**

Light amendments are applied by review mode's Apply step per
[`review-mode.md`](review-mode.md) § Flow step 5, via `Edit` for
single-site changes or `steroid_apply_patch` when more than two
sites are touched. These are markdown edits, so the project
CLAUDE.md "always route file edits through MCP Steroid" rule is
satisfied with native `Edit` for single-site changes here.
Amendments that name production classes in the
`**What/How/Constraints/Interactions**` blocks are bound by the
§Pre-write rule above — PSI-verify every named class via
`mcp-steroid` find-class **silently during accumulation, and
inline-ask the user if a name does not resolve** (per review
mode § Flow step 1 step 2), so the user can correct a
pattern-induced name during the same review-mode round rather
than after commit:

- Track title, intro paragraph
- Scope indicators in the plan-file checklist entries
- `**What/How/Constraints/Interactions**` subsections in the step
  file's `## Description`
- Track-level `mermaid` diagrams in the step file's `## Description`
- Reordering of remaining `[ ]` tracks within the plan checklist
  (only if dependencies still hold; re-render Panel 2 if the
  reorder changes the upcoming track)
- Skipping a remaining track (`SKIP_TRACK` action item — single
  user-initiated drop with a required reason; runs the full
  [`track-skip.md`](track-skip.md) § Process on Apply, including
  the terminal step-file delete; re-render Panel 1 with the
  skipped track as the new look-back anchor)

Deep amendments — route to inline replanning per
[`inline-replanning.md`](inline-replanning.md) (trigger: "user
requests escalation during track pre-flight"):

- Decision Records, Architecture Notes, Goals, or Constraints in
  the plan file
- **Adding** a new track (requires authoring a fresh step file
  `## Description`, dependency analysis, and design decisions).
  **Removing** a remaining track is light — it is the `SKIP_TRACK`
  action item above, not a deep amendment.
- Cross-track interaction surfaces (i.e., the change would affect
  another track's scope beyond pure reordering)
- Anything the user describes as "fundamental rework"
- A Panel 1 strategy assessment producing `ESCALATE` that the
  user accepts (handled in step 1 above)

When the gate ESCALATEs, inform the user, restate any captured
clarifications so the user can fold them into the replan if still
relevant, then load `inline-replanning.md` and proceed.

**5. Capture clarifications.** Keep a clarifications buffer in the
orchestrator's conversation context — a bullet list of the user's
notes plus any orchestrator-stated interpretations the user
confirmed. The buffer is non-empty only if at least one `CLARIFY`
item was applied during a review-mode round (see
[`review-mode.md`](review-mode.md) § Flow step 5). When the user
picks **Approve**, the buffer flows verbatim into the step file's
`## Description` in step 6 below.

**6. Persist amendments + clarifications + strategy-refresh line.**

After the user picks **Approve**, write the on-disk artifacts of
this round:

- **Strategy-refresh line** (Panel 1 was active): append a
  `**Strategy refresh:**` line to the plan file under the
  just-completed (or just-skipped) track's block, recording the
  assessment outcome — `CONTINUE` or `ADJUST` (with a brief
  summary of what was adjusted). Example for CONTINUE:

  ```markdown
  - [x] Track 2: <title>
    > <intro paragraph>
    >
    > **Track episode:**
    > <strategic summary>
    >
    > **Step file:** `tracks/track-2.md` (4 steps, 0 failed)
    >
    > **Strategy refresh:** CONTINUE — no downstream impact detected.
  ```

  Example for ADJUST:

  ```markdown
    > **Strategy refresh:** ADJUST — Track 4 description updated to account
    > for the new `IndexStatistics` API shape discovered during this track.
  ```

  For skipped tracks (`[~]`), the strategy-refresh line follows
  the skip record (see [`track-skip.md`](track-skip.md) step 5).
  The skip record's `**Skipped:**` line serves as the just-skipped
  track's episode for the purpose of the assessment.

  The line is **not written** when Panel 1 was skipped (very-first
  track — there is no anchor block) or when the gate ESCALATEd
  (inline replanning restructures the plan directly).

- **Clarifications subsection** (buffer non-empty): write the
  buffer as a `### Clarifications` subsection at the end of the
  upcoming track's step file's `## Description`. **If a
  `### Clarifications` subsection already exists** (e.g., a prior
  gate session committed clarifications and was interrupted before
  any review ran, then re-fired on resume per §Phase A Resume),
  **delete the existing subsection first and replace it with the
  new buffer** — the gate's output reflects this session's
  decision, not a layered history. Panel 2 already surfaced any
  prior on-disk clarifications in the summary, so anything still
  relevant can be re-stated by the user during the loop. If the
  buffer is empty, skip this edit. An existing
  `### Clarifications` subsection on disk from a prior session is
  preserved as-is in this case (the user neither re-clarified nor
  asked for it to be removed).

- **Plan/step-file amendments** (any `EDIT_PLAN` / `EDIT_STEP_DESC`
  / `SKIP_TRACK` items applied during review-mode rounds): the edits
  already landed in the working tree during review mode's Apply step
  ([`review-mode.md`](review-mode.md) § Flow step 5), including any
  step-file deletions produced by `SKIP_TRACK`. They are committed
  alongside the strategy-refresh line and any clarifications below.

After all artifacts are in place, run a single Workflow update
commit:

```bash
git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
        docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
git commit -m "Apply pre-flight amendments before Track <N>"
git push
```

Stage only the files actually edited — drop the path that wasn't
touched. If the round produced no amendments, no clarifications,
and no strategy-refresh line (the gate was a pure no-op — only
possible when Panel 1 was skipped or its outcome was already on
disk from a prior interrupted session, and the user picked
**Approve** without entering review mode), skip this commit
entirely.

**7. Resume idempotency.** If the merged gate is re-entered on a
session resume (no review has been recorded in `## Reviews
completed` yet — see §Phase A Resume), the gate checks for a
`**Strategy refresh:**` line under the just-completed/skipped
track's block before running Panel 1. If the line exists, Panel 1
is **skipped** on resume — the earlier session's assessment is the
historical record and is preserved. The Pre-Flight loop runs on
Panel 2 only. Plan/step-file edits committed in the previous
session persist; clarifications captured in the previous session
lived only in conversation context and are lost — the user must
re-enter them if still relevant. (An on-disk `### Clarifications`
subsection committed by the prior session does persist; step 6's
replace-then-write rule governs how it interacts with this
session's buffer.)

**Partial-commit asymmetry.** A prior session may have committed
step 4 plan/step-file edits but died before step 6 wrote the
strategy-refresh line. On resume the line is missing, so the gate
re-runs Panel 1; the committed edits are the new baseline (they
do not appear as a diff against the original plan).

To surface any such prior amendments, the orchestrator MUST run
the following before Panel 1 starts on a resume entry (no review
recorded yet):

```bash
git log --oneline -10 -- docs/adr/<dir-name>/_workflow/implementation-plan.md \
                          docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
```

If the output contains a recent `Apply pre-flight amendments
before Track <N>` commit but no corresponding `**Strategy
refresh:**` line is present on disk under the
just-completed/skipped track's block, the prior session's edits
are the new baseline — surface this in Panel 1's user-facing
output so the user does not re-issue them in this round's
review-mode rounds.

**8. Continue.** Move to §What You Do sub-step 1 below.

### What You Do

> The Track Pre-Flight gate above must clear before sub-step 1 starts.
> On State C resume the gate is skipped (see §Phase A Resume).

> The §Pre-write rule above governs every write below that names a
> production class — apply it before the atomic write in sub-step 5.

1. **Read the plan file** for strategic context (Goals, Architecture
   Notes, Decision Records, Component Map) and the **step file**
   (`docs/adr/<dir-name>/_workflow/tracks/track-N.md`) for the track's
   detailed description. The step file already exists from Phase 1
   and may carry pre-flight amendments and a `### Clarifications`
   subsection committed by the gate above; both phases of consumption
   route through the same file.

2. **Assess track complexity** to determine which reviews to run (see
   §Complexity Assessment below).

3. **Run track-scoped reviews** as sub-agents (technical, risk, adversarial
   as warranted). After each review completes:
   - Update the **Reviews completed** section in the step file with a
     one-line summary — review type, iteration count at PASS, and a
     brief tally of findings that drove plan/step edits (e.g.,
     `- [x] Technical: PASS at iteration 2 (3 findings, 2 accepted, 1 rejected)`).
   - The findings themselves are not persisted to a separate file —
     they ride in the orchestrator's conversation context for the
     iteration loop, and the durable trace is the resulting step-file
     edits (decomposition, risk tags, description tweaks). Phase A
     resume gates on the **Reviews completed** checkboxes in the step
     file: a checkbox is `[x]` only after the gate for that review type
     has passed, so an interrupted iteration leaves the entry `[ ]` and
     the next session re-runs that review type from iteration 1.
   - **Context consumption check** (mandatory after each review, except
     after the last action of the phase): run
     `cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
     `warning` (≥30%) or `critical` (≥40%), do NOT start the next review
     or decomposition. Save all work and ask the user for a session
     refresh (see workflow.md §Context Consumption Check). If the pause
     leaves Phase A mid-flight (for example, technical review PASSed
     but risk / adversarial reviews are still unrun, or all reviews
     PASSed but decomposition has not yet been written), write a
     handoff file per
     [`mid-phase-handoff.md`](mid-phase-handoff.md) so the next session
     does not re-spawn reviewers whose results already landed in the
     **Reviews completed** section. If the level is `safe`/`info`,
     continue. If the file does not exist or the command fails, this
     is **not an error** — treat as `safe` and continue.
4. **Decompose scope indicators** into concrete steps. For each step,
   assign a **risk tag** (`low` / `medium` / `high`) per the criteria
   in [`risk-tagging.md`](risk-tagging.md) — load that file at the
   start of decomposition. The tag controls whether Phase B runs
   step-level dimensional review for the step.
5. **Write decomposed steps** to the step file's `## Steps` section as
   `[ ]` items, each with its `**Risk:**` line in the description
   blockquote. Mark `Review + decomposition` as `[x]` in the Progress
   section. Before this atomic write, apply the §Pre-write rule above
   to every production class named in a step body — PSI-verify each
   via `mcp-steroid` find-class so a pattern-induced name (V1 → V2/V3
   trap, generated-code package drift) does not slip into the step
   file and force an iter-2 fix round. On unresolved-name failure,
   follow the **Failure path** in §Pre-write rule (one retry, then
   `AskUserQuestion`) — do NOT write the step file with an unresolved
   reference.

6. **Commit and push the Phase A workflow updates.** Phase A's on-disk
   writes — the populated `## Steps` section and the `[x]` mark in
   `## Progress` — must be committed before Phase B spawns the first
   implementer for this track. The implementer's revert path uses
   `git reset --hard HEAD`, which would otherwise discard the
   uncommitted decomposition.

   ```bash
   git add docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
   git commit -m "Phase A review and decomposition for <track>"
   git push
   ```

   This is a single Workflow update commit per the table in
   `commit-conventions.md` § Commit type prefixes. Stage explicit
   paths only — never `git add -A` — so unrelated files in the
   working tree (e.g., scratch logs from prior debugging) don't get
   pulled in.

### Complexity Assessment and Which Reviews to Run

Complexity determines which pre-execution reviews to run, not user
interaction level — all tracks execute autonomously after review.
All tracks get track-level code review (Phase C) regardless of
complexity. Step-level dimensional review (Phase B sub-step 4) runs
only for steps tagged `risk: high` per
[`risk-tagging.md`](risk-tagging.md); `medium` and `low` steps rely on
tests plus track-level review.

| Track complexity | Review pipeline |
|---|---|
| Simple (1-2 steps) | Technical review only — even if track characteristics suggest Risk or Adversarial, skip them for 1-2 step tracks. |
| Moderate (3-5 steps) | Technical review as baseline. Risk and/or Adversarial reviews are added when track characteristics warrant them (see table below). |
| Complex (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

Specific characteristics that upgrade Moderate tracks:

| Track characteristics | Reviews to run |
|---|---|
| Simple (1-2 steps) — any characteristics | Technical only (skip Risk/Adversarial) |
| Moderate (3-5 steps) | Technical (always) |
| Moderate + critical paths or performance constraints | Technical + Risk |
| Moderate + major architectural decisions or non-obvious scope | Technical + Adversarial |
| Complex (6-7 steps, or critical path / high-risk) | Technical + Risk + Adversarial |

### Inputs passed to Phase A review sub-agents

All four Phase A review sub-agents — the track-scoped technical, risk,
and adversarial reviews, plus the review gate verification — receive
the same shared set of inputs. The mini-sections below describe only
what each review *does* with those inputs; the inputs themselves are
enumerated here once so the mini-sections can point here by reference
instead of restating them.

| Input | Value |
|---|---|
| `plan_path` | Absolute path to `docs/adr/<dir-name>/_workflow/implementation-plan.md` — the strategic context (Goals, Constraints, Architecture Notes, Decision Records, Component Map). |
| `step_file_path` | Absolute path to `docs/adr/<dir-name>/_workflow/tracks/track-N.md` — once Phase A has written the step file, its `## Description` section is the authoritative source for the track's `**What/How/Constraints/Interactions**` subsections and any track-level diagram (per the lifecycle table in `conventions-execution.md` §2.1). |
| `track_name` | The track heading as it appears in the plan file's checklist (e.g., `"Track 2: Execution workflow edits"`). |
| `codebase_path` | Absolute path to the repository root — the sub-agent may Read any file under this path to validate code references. |
| `prior_episodes` | Summary of track episodes from already-completed tracks. The episodes themselves also appear in the slim plan snapshot pointed at by `plan_path`, but they are passed as a **separate** value so each review prompt's `{prior_episodes}` placeholder resolves without forcing the sub-agent to re-parse the plan. Used for cross-track consistency checks. |
| `previous_findings` | Findings from earlier iterations of the same review type (empty on iteration 1; populated on iterations 2–3). Used to avoid re-surfacing already-accepted/deferred findings and to verify that review-fix commits resolved prior findings. |

Phase A orchestration always passes both `plan_path` and
`step_file_path` to each sub-agent. Prompts that read the track
description from the plan-file entry use `plan_path`; prompts that
read it from the step file's `## Description` section use
`step_file_path`. The Inputs block and the per-review mini-sections
below do not need to change when an individual prompt switches sources
— only the prompt file itself is edited.

**Gate-verification-specific inputs.** The review gate verification
sub-agent additionally receives `findings` (the current iteration's
findings under re-check — semantically distinct from `previous_findings`,
which carries finalised findings from earlier iterations) and a
`review_type` value identifying which review produced the findings
(`technical` / `risk` / `adversarial`). These two are not part of the
shared set because the track-scoped reviews do not consume them; the
gate-verification mini-section below notes their role.

### Track-scoped technical review

Spawn a sub-agent with the technical review prompt. Inputs: the shared
set defined in §Inputs passed to Phase A review sub-agents above.

**Prompt file:** [`prompts/technical-review.md`](prompts/technical-review.md)

### Track-scoped risk review

Spawn a sub-agent with the risk review prompt. Inputs: the shared set
defined in §Inputs passed to Phase A review sub-agents above.

**Prompt file:** [`prompts/risk-review.md`](prompts/risk-review.md)

### Track-scoped adversarial review

Spawn a sub-agent with the adversarial review prompt. Inputs: the
shared set defined in §Inputs passed to Phase A review sub-agents
above.

**Prompt file:** [`prompts/adversarial-review.md`](prompts/adversarial-review.md)

### Review gate verification

After fixes are applied, spawn a sub-agent to verify. Inputs: the
shared set defined in §Inputs passed to Phase A review sub-agents
above, **plus** the gate-verification-specific `findings` (the
iteration's findings under re-check) and `review_type`
(`technical` / `risk` / `adversarial`).

**Prompt file:** [`prompts/review-gate-verification.md`](prompts/review-gate-verification.md)

### Review iteration protocol

Max 3 iterations per review type, findings cumulative. Full iteration
limits, finding ID prefixes, finding format, and gate verification output
are in [`review-iteration.md`](review-iteration.md) — load that file when
running the review loop. If blockers persist after 3 iterations, note them
and proceed with caution — the step implementation phase will surface
concrete issues if they exist.

### Step Decomposition

After track review passes, decompose scope indicators into concrete steps.
Decompose **all steps at once** — tracks are capped at ~5-7 steps, making
full upfront decomposition feasible.

#### Inputs for decomposition

- Track description, scope indicators, component diagram, and relevant
  Decision Records
- Track episodes from all completed tracks
- Codebase knowledge gained from track review

#### Decomposition rules

- Each step = one commit.
- Each step = fully tested, self-contained change with 85% line / 70%
  branch coverage.
- If a step touches more than ~3 files or does unrelated things, split it.
- If a step feels trivial (single import, single rename), merge it into
  a neighbor.
- Note **cross-cutting concerns** (shared types, refactors) as separate
  steps rather than embedding them inside feature steps.

#### Risk tagging

Assign a risk tag — `low`, `medium`, or `high` — to each decomposed
step. The tag controls whether Phase B runs step-level dimensional
review (`high` runs the full review loop; `medium` and `low` skip it
and rely on tests plus track-level review). Track-level review at
Phase C always runs against the cumulative track diff regardless of
the per-step distribution.

Apply the criteria in [`risk-tagging.md`](risk-tagging.md) — load that
file at the start of decomposition. Six HIGH categories (concurrency,
crash-safety/durability, public API, security, architecture,
performance hot path), one MEDIUM band (multi-file logic, test
infrastructure, build config, observability changes), and a LOW
default for refactoring / new tests / docs / isolated bug fixes. When
in doubt, mark `high` — over-tagging costs an extra review, but
missing a real high-risk step ships bugs.

Write the tag inline in each step's description blockquote:

```markdown
- [ ] Step: <description>
  > **Risk:** <level> — <category, "default", or "override: <reason>">
```

The tag stays in place through Phase B (where it gates
`step-implementation.md` sub-step 4) and Phase C (where `medium` and
`high` are treated as focal points by the track-level reviewers).
Once a step is implemented, the tag is locked.

#### Parallel step annotation

During decomposition, you may identify independent steps within the
track — steps that don't depend on each other and don't modify the same
files. Annotate them with `*(parallel with Step N.M)*` in the step file.
Must not modify the same files.

#### Output

Write decomposed steps to the **step file**
(`docs/adr/<dir-name>/_workflow/tracks/track-N.md`), creating it if it doesn't exist.
Scope indicators in the plan file are NOT replaced — step details live only
in the step file.

The scope indicators serve as a starting point, not a binding contract. You
may produce more or fewer steps than the indicator suggested, or cover
different aspects, based on what is actually needed.

### Phase A Resume

The step file already exists from Phase 1 with `## Description`
populated, so Phase A resume's only concerns are (1) what state the
Track Pre-Flight gate left behind and (2) which Phase A activities
have completed. When `/execute-tracks` auto-resumes into Phase A
(the Startup Protocol's State C `Review + decomposition is [ ]` row
routes here), the main agent applies the rules below.

**Track Pre-Flight gate.** The gate re-fires on resume only when no
review has been recorded in the step file's `## Reviews completed`
section yet. Once any review has been recorded, the gate's outcome
(amendments + clarifications) is baked into the step file the
reviews ran against — re-firing would invalidate that work. The
re-fired gate honours the resume idempotency rule in §Track
Pre-Flight step 7: if a `**Strategy refresh:**` line is already on
disk under the just-completed/skipped track's block, Panel 1 is
skipped and only Panel 2 is presented. Clarifications captured in
the prior session lived only in the orchestrator's conversation
context and are lost; the re-fired gate sees committed amendments
on disk (an on-disk `### Clarifications` subsection persists and
interacts with this round's buffer per §Track Pre-Flight step 6's
replace-then-write rule), but the user must re-enter any
clarifications they had given previously.

**Uncommitted gate state.** Before re-firing the gate, run
`git status --porcelain docs/adr/<dir-name>/_workflow/implementation-plan.md
docs/adr/<dir-name>/_workflow/tracks/track-<N>.md`. If either path is
dirty, the previous session was interrupted between applying
amendments and committing them. Surface the diff to the user and ask
whether to keep or revert the uncommitted changes before continuing —
silently committing them would smuggle un-reviewed edits into the
gate's audit trail, and silently reverting them would lose user-
approved amendments.

**Resume actions** (after the gate has cleared, or skipped because
reviews are already in progress):

| `## Reviews completed` state | `## Steps` state | Action |
|---|---|---|
| Empty | Empty | Re-fire the gate (per the rules above), then run §What You Do sub-steps 1-6 from the top. |
| One or more reviews recorded as `[x]` | Empty | Skip the gate. Resume reviews from the next missing review type (§What You Do sub-step 3 onward). |
| All planned reviews recorded | Non-empty `[ ]` items | Skip the gate. Decomposition has run; resume from sub-step 6 (commit) if not yet committed, otherwise the step file is already in steady state and `/execute-tracks` should route to Phase B on the next invocation. |

The non-re-copy rule (no operation re-derives `## Description` from
external sources during Phase A) protects any amendments / inline-
replan rewrites the step file may have accumulated since Phase 1 from
being silently overwritten on resume.

---

## Phase A Completion — MANDATORY SESSION BOUNDARY

> **Do NOT proceed to Phase B in the same session.** Phase A always ends
> with a session boundary. The user clears context and re-runs
> `/execute-tracks` to begin Phase B with fresh context.

After writing the step file with all decomposed steps:

1. **Verify the step file** on disk has:
   - `Review + decomposition` marked `[x]` in Progress
   - All reviews recorded in Reviews completed
   - All steps listed as `[ ]` items
2. **Verify the Phase A commit landed.** Run `git status --porcelain`;
   the working tree must be clean. Run `git log -1 --oneline` and
   confirm the tip is `Phase A review and decomposition for <track>`.
   If the commit is missing (e.g., the session was interrupted
   between step 5 and step 6 of §What You Do), run step 6 now —
   the implementer's `git reset --hard HEAD` would otherwise
   discard the decomposition.
3. **Inform the user** that Phase A is complete:
   - How many steps were decomposed
   - Which reviews were run and key findings
   - Any concerns or risks noted during review
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase B (step implementation)."
4. **Run self-improvement reflection.** Load
   `.claude/workflow/self-improvement-reflection.md` on-demand and
   follow it. Phase A friction worth recording typically lives in
   the review-iteration loop, the technical/risk/adversarial sub-agent
   prompts, the decomposition rules, or the step-file template. The
   protocol creates approved proposals as YouTrack issues under
   `YTDB` with the `dev-workflow` tag (or skips with a notice if
   the YouTrack MCP server is unreachable); reflection produces no
   commit. Then proceed to Step 5.
5. **End the session.** Do not proceed to Phase B in the same session.

**Why:** Phase A is exploratory (reading code, validating assumptions).
That "reviewer mindset" context is not helpful during implementation —
it dilutes focus and carries stale exploratory context. The step file
bridges everything the implementation phase needs.
