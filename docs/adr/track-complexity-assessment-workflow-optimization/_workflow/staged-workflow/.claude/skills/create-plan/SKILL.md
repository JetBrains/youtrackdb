---
name: create-plan
description: "Research the codebase and create an implementation plan with architecture notes, design document, and track decomposition. Use when starting a new feature or large change."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: planner.
Your phase: determined by the auto-resume State in `workflow.md` § Startup Protocol.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

Read and follow the workflow for Phase 0 (Research) and Phase 1 (Planning).

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned sentence patterns`, `## Banned analysis patterns`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:planner:0,1 `§1.5` for the workflow-level anchor and tier mapping.

> **Stamp discipline.** Every `_workflow/**` artifact this SKILL creates carries a line-1 `<!-- workflow-sha: <40-char SHA> -->` stamp written at creation. Direct-mutation kinds applied later by `edit-design` (`content-edit`, `section-add`, `section-remove`, `section-rename`, `section-move`, `structural-rewrite`, `mechanics-edit`, `design-sync`) leave the stamp untouched and preserve its line-1 position; only artifact creation, migration replay, and no-drift normalization write the stamp. The format definition, parser idioms, and the paired SHA-computation idiom this SKILL copies into its planning-transition step are anchored in conventions.md:planner:1 `§1.6`. Read that section for the single source of truth.

**Step 1 — Read workflow documents.**

Read these in order before doing anything else (do NOT ask the user anything yet):
1. `.claude/workflow/conventions.md` — shared formats,
   glossary (including the **complexity axes** and **design gate** terms),
   plan file structure, the `§1.2` *Per-axis artifact set*, scope
   indicators, review iteration protocol
2. `.claude/workflow/research.md` — Phase 0 instructions:
   interactive research, code exploration, internet research, the
   **research log** (the durable Phase-0/1 decision ledger Phase 0 writes),
   transition rules

Do **NOT** read `.claude/workflow/planning.md` or
`.claude/workflow/design-document-rules.md` yet — they are only needed when
the user asks to create the plan (Step 4). Load them on demand at that point.

**Resolve `<dir-name>`.** All subsequent steps reference
`docs/adr/<dir-name>/_workflow/`; resolve the placeholder once before
running any command that uses it. If `"$ARGUMENTS"` is non-empty, use
it. Otherwise, default to `$(git branch --show-current)`.

**Step 1.5 — Workflow drift check (mandatory, before any other on-disk work).**

**Ordering:** this step depends on the `<dir-name>` resolver above being complete and Step 1b's `mkdir` not yet having run — see the trailing paragraph below for the gate's Skip-#1 rationale.

Invoke the drift gate defined in
workflow-drift-check.md:planner:1.
The gate is shared with `/execute-tracks`; its intro names both callers
and its body is caller-symmetric, so this step is a thin orchestration
handoff that defers to the gate rather than restating its detection. Run
the gate's § Detection against the resolved `<dir-name>` from the
previous block. Detection now runs the two-phase drift walk inside
`.claude/scripts/workflow-startup-precheck.sh` under `--mode full` and
reads the resulting `drift` JSON object; the script resolves the plan
dir from the active branch, so no inline `PLAN_DIR=` bash line runs
here. Follow its § Skip conditions, § No-drift normalization, and
§ Resolutions flow verbatim.

The three-resolution prompt fires only when drift surfaces and no
skip condition matched. The user picks one:

- **Migrate now** — print `Run /migrate-workflow from this worktree,
  then re-invoke /create-plan afterward.` (the single instruction
  line per `workflow-drift-check.md` § Migrate now, with the
  `/create-plan` re-invocation hint appended), then end the session.
  Exit immediately; no on-disk work has run yet (Step 1b's `mkdir`,
  Step 2's aim prompt, and Step 5's commit and push are all
  downstream of Step 1.5).
- **Defer** — continue this session. Record the deferred-drift count
  via the TaskCreate todo described in `workflow-drift-check.md`
  `§ Defer`; Step 5's deferred-drift recital reads that todo and prints
  the same line shape `workflow.md § What to do before ending a session` uses for `/execute-tracks`. If TaskCreate is unavailable
  in this session, hold the `<count>` and `<short-stamp-base-SHA>`
  (or the unstamped variant flag) in in-context memory instead,
  matching the gate file's § Defer paragraph.
- **Suppress** — continue this session with no recital at session
  end.

No-drift (with or without the gate's normalization commit), Defer,
and Suppress all proceed to Step 1a without further user prompt.
Ordering: Step 1.5 runs after the `<dir-name>` resolver (so the
resolved name is available when the script's `--mode full` walk
resolves the plan dir from the active branch) and before Step 1b's
`mkdir` (so the script's internal Skip-#1 directory check reads the
pre-creation `_workflow/` state on fresh `/create-plan` invocations).
The skip check is now internal to the script, not an inline gate-bash
`[ -d … ]`.

**Interaction with Step 1a's handoff scan.** Step 1.5 fires before
Step 1a. On a `/create-plan` resume where `handoff-*.md` exists in
`docs/adr/<dir-name>/_workflow/`, the drift gate fires before the
handoff loader notices. No failure mode loses the handoff: on Migrate now the
handoff file persists on disk (it is already committed) and the next
`/create-plan` invocation's Step 1a picks it up after the drift gate
clears; on Defer or Suppress, Step 1a's handoff resume runs after
Step 1.5 in the same session. Per-session TaskCreate todos do not
survive `/clear`, so a paused Session A's Defer state is not carried
into Session B — Session B's Step 1.5 re-evaluates drift independently.

**Step 1a — Handoff check (mandatory, before any other on-disk work).**
Run:
```bash
ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
```
If any files exist, load
mid-phase-handoff.md:planner:1
and follow its `§Resume protocol` BEFORE Step 1b. A previous
`/create-plan` session paused mid-research or mid-planning and left a
handoff to be re-presented. Do NOT ask for the aim, start fresh
research, or write plan files until the handoff is resolved.

**Step 1b — Create the workflow directory.**

As the first durable action of `/create-plan`, ensure the workflow
directory exists so research handoff files have a home if context
fills up before Step 4:
```bash
mkdir -p docs/adr/<dir-name>/_workflow/plan
```
This is idempotent — safe to re-run on resume. The directory carries
the research log, plan, design, track files, review files, and handoff
files; the Phase 4 cleanup commit removes it before merge (see
`.claude/workflow/conventions.md` `§1.2`).

**Step 1c — Resume check (before the aim prompt).**

After Step 1.5 (drift) and Step 1a (handoff) have cleared, check the
design-first artifacts plus the phase ledger and the single-track glob on
disk:

```bash
ls docs/adr/<dir-name>/_workflow/design.md \
   docs/adr/<dir-name>/_workflow/implementation-plan.md \
   docs/adr/<dir-name>/_workflow/phase-ledger.md \
   docs/adr/<dir-name>/_workflow/plan/track-1.md 2>/dev/null
```

The routing signal is the three complexity axes the ledger now carries (D10):
the **design gate** (does a `design.md` exist), the **plan-presence /
track-count** signal (how many track files the planner authored — more than
one means an `implementation-plan.md` exists), and the **Phase-1-complete
marker** (did Phase 1 finish cleanly). A plan-less change has **no plan**
(D2), so `implementation-plan.md` presence cannot disambiguate it; the
**phase ledger** is the signal — when the ledger records `design_gate=no` and
the plan-presence signal is single (`tracks=1`), an interrupted plan-less
session resumes off the ledger, with the `plan/track-1.md` glob as the
secondary signal that the one track file was written. When the plan is
present (`tracks` > 1), `implementation-plan.md` **presence** stays the
routing signal, and the design gate is read from the **ledger** `design_gate`
field, never from a plan line (the plan no longer carries a tier line; the
classification moved to the ledger per D4) and never from a fresh read of the
research log, which would be a third decision-content read site and break S2.

The new shape the three axes make expressible is a **design with one track
and no plan** (`design_gate=yes`, `tracks=1`): on disk it is a `design.md`,
no plan, and one track file — a file set **identical** to a mid-authoring
crash that wrote `design.md` before deriving any plan. File presence alone
cannot tell the two apart, so the **Phase-1-complete marker** is the
disambiguator: set (`phase1_complete=yes`) means the design+single-track
steady state (do not re-author); unset means a mid-authoring crash (re-enter
Step 4a). The marker check runs first to separate "Phase 1 is done" from
"Phase 1 is not done"; the existing committed-and-clean `design.md` check
still applies *within* the crash arm to tell a frozen design apart from an
unfrozen one.

Read the three fields from the ledger once before routing — the same
last-value-wins fields the script's `--append-ledger` seeds at Phase 1
(`conventions.md` `§1.1` *Phase ledger*):

```bash
LEDGER="docs/adr/<dir-name>/_workflow/phase-ledger.md"
# design gate (yes/no), track count (integer), and the Phase-1-complete
# marker (yes); each last-value-wins, empty if no ledger or no such line.
LEDGER_DESIGN_GATE="$(sed -n 's/.* design_gate=\([a-z]*\).*/\1/p' "$LEDGER" 2>/dev/null | tail -n 1)"
LEDGER_TRACKS="$(sed -n 's/.* tracks=\([0-9]*\).*/\1/p' "$LEDGER" 2>/dev/null | tail -n 1)"
LEDGER_PHASE1_COMPLETE="$(sed -n 's/.* phase1_complete=\([a-z]*\).*/\1/p' "$LEDGER" 2>/dev/null | tail -n 1)"
```

Route on what exists. Evaluate the branches in order; the first whose
condition holds wins. The `design.md`-present branch fans out on the
Phase-1-complete marker (steady state vs mid-authoring crash); the no-design
branches differ from it by `design.md` absence. The plan-less resume branch
and the fresh-start branch both describe the no-plan/no-design state, so the
order matters: the more-specific plan-less resume is reached first and the
fresh-start branch is the catch-all that fires only when no earlier branch
matched.

- **`design.md` exists, `implementation-plan.md` does not** — a `design.md`
  is written (`design_gate=yes`) and no plan exists. Two on-disk-identical
  states share this signature — the design+single-track steady state and a
  mid-authoring crash — so check the **Phase-1-complete marker first**:
  - **Marker set (`LEDGER_PHASE1_COMPLETE` = `yes`)** — Phase 1 finished
    cleanly. This is the design+single-track steady state (`design_gate=yes`,
    `tracks=1`): the design and its one track file are the durable Phase-1
    artifacts and there is no plan by derivation (a cross-track summary is
    vacuous for one track). This is a **normal resume**, not a Step-4 entry:
    the drift / handoff / state routing above already handled it; **do not
    re-author** the design or re-derive a plan. Proceed to Step 2 only if the
    user explicitly asks to start a new aim against the same dir (rare). The
    marker alone is **sufficient by construction** here: a clean Phase-1 seed
    co-writes `phase1_complete=yes` and `design_gate=yes` on the same line, so
    a set marker implies the gate and no cross-check of `LEDGER_DESIGN_GATE` is
    needed on this arm. The `LEDGER_DESIGN_GATE` / `LEDGER_TRACKS` locals
    parsed at the top of the step are read only by the lower no-design
    branches — they are intentionally unused here, not a missing check.
  - **Marker unset (`LEDGER_PHASE1_COMPLETE` blank)** — Phase 1 did **not**
    finish: the prior `/create-plan` invocation authored (and possibly
    committed) `design.md` but ended — crash, context-full `/clear`, or the
    user stopping the session — before the plan derivation completed and the
    marker was seeded. This is the **crash-recovery** arm. File presence alone
    is not proof the design is frozen: `edit-design` writes `design.md` to
    disk in its *apply* step, **before** the cold-read review runs and before
    the design commit lands. A session interrupted after the write but before
    the review passed leaves an **unreviewed, uncommitted** `design.md` on
    disk. So confirm the design is **committed and clean** — the on-disk proxy
    for "frozen and reviewed", since the `Add initial design` commit lands only
    after its review passes:

    ```bash
    # committed: at least one commit touches design.md
    git log -1 --format=%h -- docs/adr/<dir-name>/_workflow/design.md
    # clean: no uncommitted changes to design.md (empty output = clean)
    git status --porcelain docs/adr/<dir-name>/_workflow/design.md
    ```

    - **Committed (non-empty `git log`) AND clean (empty `git status`)** —
      the design is frozen and reviewed, and the prior invocation crashed
      between the design commit and the plan derivation. **Auto-resume into
      Step 4b** (plan derivation): skip Step 2's aim prompt and Step 3's
      Phase 0 research loop entirely — the aim and research are already
      captured in the frozen `design.md` and the conversation that produced
      it. Read `planning.md` (deferred from Step 1) and derive the plan from
      the frozen design. This crash-recovery resume reaches the same Step 4b
      the collapsed happy path flows into directly, so the plan derives
      identically whether or not the prior session was interrupted.
    - **Uncommitted (empty `git log`) OR dirty (non-empty `git status`)** —
      a session was interrupted mid-design-authoring (before the `Add initial
      design` freeze-and-commit). **Resume Step 4a**, not Step 4b: re-enter
      the `edit-design` review loop so the adversarial gate and cold-read pass
      run and the design is committed before any plan derives from it. This
      arm is **retained** by the collapse: even though the happy path no
      longer crosses a session boundary, a crash mid-authoring still leaves an
      unfrozen `design.md`, and re-entering Step 4a is how that state recovers.
      Re-entering the loop on an already-good design is idempotent and
      harmless, so this branch is safe even on a false alarm (e.g., a stray
      editor write left the file dirty).
- **`implementation-plan.md` exists, `design.md` does not** — a no-design
  multi-track change whose plan is already derived (`design_gate=no`,
  `tracks` > 1; a one-track change has no plan, so it never reaches this
  branch). The missing `design.md` is by derivation (`design_gate=no`), not a
  sign of an interrupted Step 4a. This is a normal resume, not a Step-4 entry:
  the drift / handoff / state routing above already handled it; do not re-run
  Step 4 and do not route to design authoring. If the ledger instead records
  `design_gate=yes` (or is absent / unreadable), a multi-track plan with no
  `design.md` is malformed (the design should have been authored and committed
  first) or the branch predates the ledger scheme; treat it as the **Both
  files exist** normal resume below and surface the inconsistency to the user
  rather than silently re-deriving. Proceed to Step 2 only if the user
  explicitly asks to start a new aim against the same dir (rare).
- **Plan-less resume — ledger records `design_gate=no` and `tracks=1`,
  `plan/track-1.md` present, no `implementation-plan.md`, no `design.md`** — a
  no-design single-track session whose one track file is already written
  (D10). The ledger, not a plan, is the resume signal here; the
  `plan/track-1.md` glob is the secondary signal that the track file landed.
  This is a normal resume, not a Step-4 entry: the drift / handoff / state
  routing above already handled it; do not re-run Step 4 and do not author a
  plan (a one-track change has none). Proceed to Step 2 only if the user
  explicitly asks to start a new aim against the same dir (rare).
- **Ledger absent, `plan/track-1.md` present, no `implementation-plan.md`,
  no `design.md`** — a no-design single-track Phase-1 session interrupted
  between the track-file write and the ledger seed (the seed runs after the
  track file is written; Step "Seed the phase ledger"). The durable Phase-1
  artifact for a one-track no-design change is the track file, and it landed,
  so this is **not** a fresh start: re-authoring `plan/track-1.md` would
  clobber work already on disk. Resume by seeding the ledger (`--design-gate
  no --tracks 1`, plus the matched categories and any `§1.7` staging mode) and
  continuing from the recorded state, not by re-running research,
  classification, or the Step-4b track-file Write. (A multi-track change
  cannot reach this branch: its durable Phase-1 artifact is
  `implementation-plan.md`, whose presence is matched by an earlier branch
  above.)
- **Neither `implementation-plan.md` nor `design.md` exists, and no plan-less
  resume signal is present** — fresh start. The "no plan-less resume signal"
  condition is the OR of three testable arms, evaluated against the
  `LEDGER_DESIGN_GATE` / `LEDGER_TRACKS` values parsed at the top of this
  step: the ledger is **absent**; OR the ledger is present but its
  `design_gate` field is **empty or unreadable** (`LEDGER_DESIGN_GATE` blank);
  OR the ledger records `design_gate=no` and `tracks=1` but `plan/track-1.md`
  has **not** been written yet (the branch immediately above already claimed
  the case where the track file exists). Proceed to Step 2 (aim), then Step 3
  (research), then Step 4 (the design-gate classifier + adversarial gate, then
  Step 4a/4b). This also covers the narrow `/clear` window where Step 4's gate
  cleared but no artifact was written yet: with no plan, no `design.md`, no
  seeded ledger fields, and no track file on disk there is no resume signal,
  so the resume correctly reads as a fresh start and Step 4's classifier
  re-runs, re-deriving the classification from the now-populated log through
  its existing sanctioned authoring read — no extra read site, S2 intact.
- **Both files exist** — the plan is already derived (a design-and-plan change
  with both committed); this is a normal resume, not a Step-4 entry. The
  drift / handoff / state routing above already handled it; do not re-run
  Step 4. Proceed to Step 2 only if the user explicitly asks to start a new
  aim against the same dir (rare); the common case is the session has nothing
  new to plan.

This check has a defined resume path for every artifact combination, so the
"never a dead end" invariant holds for every arm. A `design.md` with no plan
is never a dead end — with the **marker set** it is the design+single-track
steady state (do not re-author); with the **marker unset** it is a crash, and
the committed-and-clean check then routes a frozen design to Step 4b and an
unfrozen one back to Step 4a. A derived plan resumes normally without
re-entering design authoring, and a plan-less single-track session resumes off
the ledger and its single track file (or, when the seed had not yet run, by
seeding the ledger and continuing) rather than reading as a fresh start. The
check runs **after** the drift and handoff gates so a pending migration or
handoff resolves first (those can change what is on disk), and **before** the
aim prompt so a Step-4b crash-recovery resume does not re-ask for an aim
already captured in the design or the research log.

**Step 2 — Ask the user for the aim, then seed the research log.**

Skip this step when Step 1c auto-resumed into Step 4b (the aim is already
captured in the frozen `design.md` and the research log). Otherwise, after
you have finished reading the workflow documents, ask the user to describe
the aim and goal for this session. Do NOT proceed until the user provides
the aim. Wait for the user's response before starting any research or
planning work.

Once the user provides the aim, write the **research log's `## Initial
request`** (the verbatim aim) as the first durable Phase-0 action. The
`_workflow/plan/` directory already exists from Step 1b
(`mkdir -p .../plan`), so write the log directly: create
`docs/adr/<dir-name>/_workflow/research-log.md` (a `Write`, not a shell
command) with the six
sections `research.md` §The research log defines: `## Initial request`
(the verbatim aim, written once); the empty `## Decision Log`,
`## Surprises & Discoveries`, `## Open Questions` continuous logs;
`## Baseline and re-validation` filled **only** on a workflow-modifying
branch; and the empty `## Adversarial gate record` the Step 4 gate appends
its verdict headings to. The log is created **unstamped**: it is on the
`§1.6(f)` never-stamped list (D19), so no line-1 `workflow-sha` comment is
written and the `§1.6(b)` paired-idiom does not run for it. Idempotent on resume: if the
log already exists (a prior Phase-0 session created it), leave its
`## Initial request` intact and append to the continuous logs only. The log
is the agent's internal memory: seed it without narrating the seeding to
the user (`research.md` §Rules, the *Keep the research log agent-internal* rule).

The plan will be saved to:
`docs/adr/<dir-name>/_workflow/implementation-plan.md`
(the `_workflow/` subdir holds every ephemeral working file — research
log, plan, design, track files, reviews — and is removed in the Phase 4
cleanup commit before merge; see `conventions.md` `§1.2` and
`workflow.md` § Final Artifacts).
The codebase is at the current working directory.

**Step 3 — Research phase (Phase 0).**

Once the user provides the aim, enter **research mode**. In this mode:
- Answer user questions about the codebase, architecture, and design
- Explore code (read files, search for patterns, trace call chains)
- Perform internet research when asked (web search, fetch documentation)
- Present findings and intermediate conclusions
- Help the user evaluate trade-offs and alternatives
- **Append decisions, surprises, and open questions to the research log**
  as they settle — each entry an ISO timestamp and a `[ctx=<level>]` tag,
  each `## Decision Log` entry carrying the `**Why:**` and
  `**Alternatives rejected:**` fields the Step-4 adversarial gate
  challenges (`research.md` §The research log for the append cadence). Do
  this silently: the log is agent-internal, so surface its content to the
  user as plain conversational prose, never as log quotes, section names,
  or D-numbers (`research.md` §Rules, the *Keep the research log agent-internal* rule)
- Do **NOT** produce plan files, design documents, or track decompositions

Stay in research mode until the user explicitly asks to create the plan
(e.g., "create the plan", "let's plan this", "proceed to planning").

**Step 4 — Classify the design gate, gate the research log, then transition to planning (Phase 1).**

Phase 1 is **complexity-adaptive** (`planning.md` §Tier classification): a
one-line fix does not pay the ceremony a durability rework needs. Step 4
runs in three parts at the Phase 0 → 1 boundary, before any Phase-1
artifact is authored:

1. **Design-gate classification** — propose the change-level **design gate**
   (does a `design.md` exist) from the now-rich research log, with the
   centrally-matched HIGH-risk categories, and let the user confirm.
2. **The adversarial gate** — run the relocated adversarial review on the
   research log as a gate (loop on blockers, gate on should-fix, no
   `skip`), domain-primed by the confirmed matched categories.
3. **Transition** — branch to Step 4a (design-first, when the design gate is
   yes) or straight to Step 4b (thinned plan + tracks, or one track file when
   the change is single-track).

**Step 4 part 1 — The design-gate classifier.**

When the user asks to create the plan, before authoring anything, confirm
the research log captures the conversation's decisions (append any settled
but unlogged), then classify the change. Read the now-rich research log's
`## Decision Log`, `## Surprises & Discoveries`, and `## Open Questions` —
this is a sanctioned Step-4 authoring read (S2). Classify the change on two
orthogonal gates (`planning.md` §Tier classification):

| Gate | Question | Answers |
|---|---|---|
| Gate 1 | Does the change need a `design.md`? | yes / no |
| Gate 2 | Does the change span multiple tracks? | multi / single |

Gate 1 is the **design gate** this part persists: it decides whether a
`design.md` (and the Phase-4 `design-final.md`) exists. Gate 2's
multi-vs-single answer is **not** decided up front here — the track count is
not knowable until the planner has decomposed the change, so plan presence is
decided at the end of Step 4b from the authored track count (D1). `design =
yes` implies multi-track, so the only single-track shape Gate 2 adds beyond
the design gate is the no-design single-track change.

Gate 1's "needs a design" test reuses the HIGH-risk category list in
`risk-tagging.md` §Gate 1 reuse (change-level), read at the **change**
level: Gate 1 is yes only when one of those categories is **central to the
change's purpose**, not merely touched by one incidental edit. Record the
**centrally-matched** categories — they prime the adversarial gate's
lenses in part 2 and seed the Phase-4 durable carrier's lens set (D16).

Propose the design gate (`design_gate=yes/no`) and the matched categories to
the user and **wait for confirmation**. The user confirms or overrides the
design gate in either direction, and may **add or drop an adversarial lens**
explicitly at this point (D16) — confirming the design gate confirms the
matched categories, so an override may shift the lenses. A `design_gate=no`
change runs its gate lens-free unless the user adds one. This is a human gate
on the artifact-shedding decision; do not proceed to part 2 until the design
gate and the lens set are confirmed. The confirmed `design_gate` value is
seeded into the phase ledger at the Step "Seed the phase ledger" below — it
is the resume router's design-gate signal and the consistency / structural
reviews' design-presence read.

**Step 4 part 2 — The adversarial gate on the research log.**

Once the design gate is confirmed, run the relocated adversarial review on the
research log as a **gate** before any Phase-1 artifact derives (D6,
`planning.md` §Tier classification). The gate spawns the existing
`reviewer-adversarial` in its research-log scope
(`prompts/adversarial-review.md` §Research-log-scoped review (Phase 0→1));
no new reviewer is added.

Create the gate's review-file directory once (idempotent), before the
first spawn — the canonical track-anchored `plan/track-N/reviews/` home
does not exist yet, so the Phase-0→1 gate writes to a plan-scoped directory
(`conventions-execution.md` `§2.5` §Third-scope review-file home):

```bash
mkdir -p docs/adr/<dir-name>/_workflow/reviews
```

Spawn the adversarial sub-agent via the `Agent` tool (the same recipe the
sibling `edit-design/SKILL.md` uses for the identical reviewer; the
research-log Inputs block in `prompts/adversarial-review.md` §Research-log
Inputs substitutes the inputs):

- `subagent_type`: `general-purpose`
- `description`: `"Adversarial research-log gate (Phase 0→1)"`
- `prompt`: the full content of
  `.claude/workflow/prompts/adversarial-review.md`. The prompt's
  TOC-protocol header resolves the reviewer's phase to 0→1, which routes it
  to the § Research-log-scoped review (Phase 0→1) section. Substitute these
  inputs into that section's `### Research-log Inputs` block:

  ```
  - research_log_path: docs/adr/<dir-name>/_workflow/research-log.md
  - matched_categories: the centrally-matched HIGH-risk categories from the
    confirmed design gate's Gate 1, plus any user-added lens — or (none) for a
    `design_gate=no` change with no user lens
  - output_path: docs/adr/<dir-name>/_workflow/reviews/research-log-adversarial-iter<N>.md
    (one file per gate iteration, <N> starting at 1 — the <type>-iter<N>.md
    naming conventions-execution.md §2.5 §Third-scope review-file home fixes)
  - codebase_path: the repo root
  ```

**Model and effort (D14).** Pin the spawn's model on the `Agent` tool's
`model` field by the confirmed design gate:

- `design_gate=yes` → `model: fable`
- `design_gate=no` → `model: opus`

The `Agent` tool has **no per-spawn effort field**, and there is no
adversarial-reviewer agent file under `.claude/agents/` to carry effort in
frontmatter — the adversarial reviewer is a prompt-file plus a
`general-purpose` spawn. So the model half lands on the `model` field as
above, and the xhigh-effort half rides the session default (it cannot be
pinned per-spawn through this surface). D14 accepts the effort caveat: the
effort half degrading to the session default does not reopen the decision.

**Output handling (D17).** The reviewer's output mode is **file**: it
persists the `conventions-execution.md` `§2.5` manifest-plus-sections review
file to `output_path` and returns only the thin manifest. Validate the
manifest's `findings` count against the file with the `§2.5` count grep
(`grep -cE '^### [A-Z]+[0-9]+ ' <file>`) before trusting the index, then
**partial-fetch `## Findings`** from disk — do not pull the whole file into
context. This caps the gate loop's context cost and makes a mid-gate
`/clear` resumable from the committed file. Commit the review file at
reviewer-return as a Workflow-update commit (the resume precondition;
`conventions-execution.md` `§2.5` §Third-scope review-file home).

**Gate semantics (no `skip`).** This run is a gate, not an advisory pass:

- A `blocker` sends the decision back to research to be re-decided; the
  gate **loops** — re-spawn the reviewer (incrementing `<N>`) after the log
  decision is revised, until no blocker remains. The iteration-1 run is a
  fresh finding set; **iteration ≥2 runs use the verdict-producer manifest
  variant** (per-prior-finding `VERIFIED` / `STILL OPEN` / `REJECTED`
  verdicts plus any new finding), per `conventions-execution.md` `§2.5`
  §Verdict-producer manifest variant.
- A `should-fix` **gates**: the log's rationale must strengthen before the
  gate clears.
- **Iteration cap (`iteration_budget`, default 3).** The re-spawn loop is
  bounded exactly as the sibling `edit-design` cold-read loop is
  (`edit-design/SKILL.md` § Inputs `iteration_budget`, § Failure modes and
  recovery). Cap the gate at `iteration_budget` re-spawns. On exhaustion with
  blockers or unaddressed should-fix findings still open, **do not loop
  further: the user is the gate** — surface the still-open findings and the
  decision history and let the user accept the risk, revise the decision, or
  abandon the change, mirroring `edit-design` § Failure modes "the user is
  the gate when the budget is exhausted". A gate must never spin unbounded on
  a contested decision.
- There is **no `skip`** — the log is not a track that can be dropped; a
  would-be `skip` is raised to a `blocker` so the change is re-justified in
  research before any artifact derives.

**Record the verdict on the log.** At each gate-clear or re-challenge,
append one verdict heading to the research log's `## Adversarial gate record`
section in the canonical shape defined once in `research.md` §The research log
(Gate-record cadence): `### Adversarial review of this log (<ISO>) — <PASS | NEEDS REVISION[: <counts>]>`,
followed by a one-line pointer to the iteration's `_workflow/reviews/research-log-adversarial-iter<N>.md`
file. This on-log record is the gate's durable verdict carrier. The gate's
review files are ephemeral (they die at the Phase 4 cleanup); the durable
verdict carrier the Phase-4 consumers and the S3 freeze-order gate read is
this `## Adversarial gate record` section, not the review files
(`conventions-execution.md` `§2.5` §Third-scope review-file home).

**Pre-presentation re-trigger vs post-presentation queue.** While
authoring a Phase-1 artifact (the `full`-tier Step-4a design), a
load-bearing decision appended to the research log re-opens the gate on
**that entry** immediately (D5). Once a frozen-ready artifact is presented
for user review, findings instead **queue and batch** through one gate run
— the D15 review-iteration batching, whose queue mechanics (the tagged
`[clarification]`/`[decision]` queue, the three-step batch, and the
multi-session handoff queue block) live in this SKILL's review-hold
batching section and `mid-phase-handoff.md`. Step 4 here owns the first,
pre-presentation gate run; the batch loop is the consumer of the same gate.

**Step 4 part 3 — Transition to Phase 1.**

After the gate clears, branch on the confirmed design gate (part 1's only
confirmed output — the multi-vs-single track count is **not** decided here;
it is settled at the end of Step 4b, where the plan-presence decision lives):

- **`design_gate=yes`** — design-first: Step 4a (author + review + freeze
  `design.md`) then Step 4b, within one `/create-plan` invocation (the
  freeze-and-commit between them stays the logical gate and crash checkpoint,
  but is no longer a session boundary), exactly as the rest of this Step
  describes.
- **`design_gate=no`** — no `design.md`. Skip Step 4a; go straight to
  Step 4b and author the track files directly from the research log in a
  **single Phase-1 session**, with the full inline Decision Records and no
  design seed to derive from. The thinned derived-mirror plan is authored at
  the end of Step 4b iff the planner decomposed into more than one track (the
  plan-presence decision, D1); resume state otherwise lives in the phase
  ledger (`conventions.md` `§1.2` *Per-axis artifact set*).

The `design_gate=yes` design-first split keeps the design-authoring and
plan-derivation work in order, but both run in one `/create-plan` invocation:

- **Step 4a (design authoring, `design_gate=yes` only)** — author `design.md` via
  `edit-design`, run its review, and freeze it. The design's review passing
  (or the user accepting open risks) is the gate that releases Step 4b; the
  freeze-and-commit is the crash checkpoint but no longer ends the session.
- **Step 4b (plan derivation)** — in the same `/create-plan` invocation for
  `full` (flowing on from Step 4a once the design is frozen and committed),
  or the same Phase-1 session for `lite`/`minimal`, derive the
  Architecture Notes, Decision Records, and track files (from the frozen
  `design.md` in `full`; from the research log in `lite`/`minimal`).

**Design→plan flow within one invocation (`full` tier only).** In `full`,
Step 4a freezes and commits `design.md` (`Add initial design`) and then
**flows straight into Step 4b** in the same `/create-plan` invocation; the
two no longer span a session boundary. The freeze-and-commit stays the
logical gate and the crash checkpoint — the plan derives only from a
committed, frozen design — but it no longer ends the session. The context
isolation the old boundary forced is supplied directly by sub-agent
authoring: Step 4b's track derivation runs through the `design-author` spawn,
a fresh cold spawn that reads the frozen committed design regardless of
session, so no `/clear` is needed to keep design-authoring context out of
plan derivation. The collapse therefore rests on a **by-reference
orchestration invariant** (built in Track 1, confirmed by gate A6 before this
collapse was applied): the author spawn returns only a thin summary, never the
drafted document, so the combined session does not re-accumulate the design and
plan context the boundary kept apart. The running skill always takes the
collapsed path — there is no runtime branch here that the executor evaluates.
The "retain the boundary instead" fallback is a design-time decision rule, not
a live one: it governs whether this collapse ships at all. By-reference was
confirmed statically (gate A6, held green) for this staged change; the
live-harness re-confirmation is a deferred Phase-4-promotion / first-live-run
gate. Were a future change to break the invariant, the boundary would be
reinstated at that authoring step, not skipped at runtime.

The startup protocol's auto-resume into Step 4b is now **crash-recovery-only**:
it fires when **`design.md` is committed and clean, `implementation-plan.md`
does not exist, AND the Phase-1-complete marker is unset** — the state a crash
leaves between the design commit and the plan derivation. The marker check
comes first: a *set* marker with that same on-disk file set is the
design+single-track steady state (do not re-author), not a crash; the
committed-and-clean test then (within the unset-marker crash arm) proves the
design is reviewed rather than abandoned mid-authoring. This is checked after
Step 1.5 (drift) and Step 1a (handoff) have cleared and before the aim prompt
(Step 2): a crash-recovery resume into Step 4b skips the aim prompt and the
Phase 0 research loop, because the aim and research are already captured in
the frozen `design.md` and the conversation that produced it. Step 1c above is
the single decision-rule home for every artifact combination — it spells out
the marker fan-out, the exact `git log` / `git status` check, the branch
order, the never-a-dead-end fallback, and the resume-Step-4a arm for a dirty
or uncommitted design; this block does not re-derive that routing.

A `design_gate=no` change has **no `design.md`** and so no Step 4a at
all: its Step-4b plan derivation runs in the same Phase-1 session that
Step 4 part 1/2 ran in, with no design freeze in between. After the collapse
`full` also runs Step 4a and Step 4b in one invocation, so the difference is
no longer single-session vs two-session — it is whether a `design.md` is
authored and frozen first (`full`) or not (`lite`/`minimal`). Step 1c's
tier-aware branch keeps an interrupted no-design tier (plan on disk, no
`design.md` by design) routing to a normal resume rather than back into
design authoring.

**Step 4a — Author the design first (`design_gate=yes` only).**

This sub-step runs when `design_gate=yes` only. When `design_gate=no` there
is no `design.md`; skip directly to Step 4b. When the user asks to create the
plan with `design_gate=yes` (and `design.md` does not yet exist):

First, read the design workflow document (deferred from Step 1):
- `.claude/workflow/design-document-rules.md` — design document rules,
  structure, and examples

Summarize the key research findings and decisions from the conversation, then
author `design.md` via the `edit-design` skill (`phase1-creation` kind) —
**not** direct `Edit` / `Write`. `phase1-creation` runs the dual-clean inner
loop (see `edit-design/SKILL.md` § Workflow and `design-document-rules.md`
§ Working / sync): each round spawns the code-grounded author plus a per-round
`readability-auditor` plus a separate per-round warm `absorption-check` that
runs the absorption-completeness cross-check (every load-bearing research-log
decision in scope appears as a seed D-record in `design.md`). After the inner
loop converges to dual-clean, the cold `comprehension-review` gate runs once;
it assesses whether a fresh reader can build a working mental model and runs
**no** absorption cross-check inline (that check is the separate
`absorption-check` spawn's job). The cold comprehension gate is **gated behind
the log-adversarial gate clearing** (S3): the gate cannot run while a
log-adversarial entry is open, so a load-bearing decision surfaced while
authoring the design is appended to the log, re-challenged at the gate, and
cleared before the comprehension gate assesses the draft, matching the ordering
the in-`edit-design` adversarial pass used to give. Iterate until the
comprehension gate passes (or the user accepts open risks), then write the
design document to
`docs/adr/<dir-name>/_workflow/design.md` using the structure below. The
design document must incorporate findings and decisions from the research
phase — it reflects the design choices discussed with the user.

Commit the frozen `design.md` (Step 5 carries the commit/push/draft-PR
mechanics; the `Add initial design` commit is the logical gate and crash
checkpoint), then **flow straight into Step 4b** in the same `/create-plan`
invocation — do not end the session. The freeze-and-commit no longer ends the
session; sub-agent authoring supplies the context isolation the old boundary
forced (see the Design→plan flow block above). The auto-resume condition above
is the crash-recovery path that re-enters Step 4b only when this invocation
ended before deriving the plan.

**Step 4b — Derive the plan and track files.**

In `full`, Step 4b runs in the same `/create-plan` invocation, flowing on
from Step 4a once `design.md` is frozen and committed (the design seed the
plan derives from); the crash-recovery resume re-enters here only when a
session ended after the design commit but before the plan derived (`design.md`
committed and clean, `implementation-plan.md` absent). In `lite`/`minimal`,
Step 4b runs in the same Phase-1 session immediately after the Step 4 gate
clears — there is no `design.md`, so the **research log** is the seed the
carriers absorb. Read the planning workflow document (deferred from Step 1):
- `.claude/workflow/planning.md` — Phase 1 instructions:
  goal, tier classification, plan file structure, architecture notes
  format, track descriptions, scope indicators, checklist decomposition
  rules

Then derive the plan and track files. **The decision seed is tier-keyed**
(S2): in `full`, seed Decision Records from the frozen `design.md` seed's
D-records; in `lite`/`minimal`, Step-4b authoring is itself the research
log's sanctioned read point, so the track Decision Records absorb the log's
load-bearing decisions directly. The thinned plan (in `lite`/`full`; absent
in `minimal`), the track Decision
Records, and track files **must** incorporate findings and decisions from
the research phase (and, in `full`, the frozen design):
- Track Decision Records reflect alternatives explored during research,
  absorbed as full inline records in each relevant track's `## Decision Log`
  (the **track-canonical live decision**, D7)
- Architecture Notes build on codebase exploration findings
- Track descriptions incorporate constraints discovered during research
- In `full`, the `design.md` seed reflects design choices discussed with
  the user; the track inline records are seeded from (and stay faithful to)
  it

Help the user develop the plan:
1. Understand the relevant parts of the codebase — explore the modules,
   packages, and classes relevant to the goal. Build a mental model before
   proposing anything.
2. Identify key decisions and constraints — technical, performance,
   compatibility, and process constraints that will shape the plan.
3. Produce Architecture Notes following the workflow rules:
   - Component Map (required): Mermaid diagram if 3+ components with
     non-trivial relationships, always paired with annotated bullet list.
   - Decision Records (required): one per non-obvious design choice, with
     alternatives, rationale, risks, and track references.
   - Invariants & Contracts (if applicable): must map to testable assertions.
   - Integration Points (if applicable): how new code connects to existing code.
   - Non-Goals (if applicable): explicit scope boundaries.
4. Decompose the work into tracks with full descriptions following the
   workflow rules:
   - Every track gets an **intro paragraph** in the plan checklist
     entry (a short paragraph of high-level context) and a matching
     `plan/track-N.md` track file whose `## Purpose / Big Picture`
     section carries a one-line BLUF followed by the same intro
     paragraph. The track's detailed content spreads across three
     other plan-at-start homes (no length cap on any of them):
     `## Context and Orientation` carries the codebase state at the
     start of the track and the concrete deliverables it produces;
     `## Plan of Work` carries the prose sequence of edits and
     additions plus ordering constraints and invariants to preserve;
     `## Interfaces and Dependencies` carries in-scope/out-of-scope
     file boundaries, inter-track dependencies, and library/function
     signatures. See `conventions-execution.md` `§2.1` for the
     canonical section list and lifecycle.
   - Populate each track file's **`## Decision Log`** with the full
     inline Decision Records the track owns — the **track-canonical live
     decision** carrier (D7) in every tier. Each is a complete four-bullet
     DR (Alternatives considered, Rationale, Risks/Caveats, Implemented-in),
     seeded from the frozen `design.md` D-records in `full` and authored
     directly from the research log in `lite`/`minimal`. The track file is
     the live authority in every tier; in `full`, the `design.md` seed copy
     is historical provenance with an optional `**Full design**` line into
     the seed's mechanism — it never substitutes for the inline record.
   - Populate each track file's combined **`## Invariants & Constraints`**
     section (D9) with the track's testable technical / performance /
     compatibility constraints and the testable invariants the plan's
     Architecture Notes used to carry — they share this home because both
     are a property that must hold, backed by a test (each invariant becomes
     a test assertion in the relevant step). A process-only, non-testable
     constraint goes to `## Context and Orientation` or `## Decision Log`
     instead; Integration Points fold into `## Interfaces and Dependencies`,
     and Non-Goals move to the research log and the PR `## Motivation` (and
     `design.md` in `full`).
   - Include a track-level Mermaid component diagram inside the
     track file's `## Context and Orientation` section when the
     track has 3+ internal components with non-trivial interactions.
     Track-level diagrams are **never rendered in the plan file**.
   - Track sizing rule: size each track by its in-scope file footprint, not
     its step count. *Maximize* — pack autonomous units in up to the soft
     footprint ceiling (related or not), opening a new track only when the
     next unit breaches the ceiling or breaks independent mergeability. A
     track ≤~12 in-scope files that folds into a neighbor is a merge candidate
     (flag-only); a track over ~20-25 in-scope files is a split candidate.
     Both bounds are soft: an out-of-bounds track passes when its track file
     carries a written justification. The full rule lives in `planning.md`
     §Track descriptions. The execution agent handles sequencing and episode
     propagation between dependent tracks.
5. For each track, include a **Scope indicator**:
   - Format: `> **Scope:** ~N files covering X, Y, Z`
   - Approximate file footprint + brief list of major work pieces. The
     footprint is a per-track soft heuristic, not the per-step `~12` split
     cap; the in-scope file set already lives in the track file's §Interfaces.
   - These are strategic signals, not tactical commitments — step
     decomposition happens during Phase 3 execution.
   - Do NOT include full `- [ ] Step:` items or *(provisional)* markers.
   - Focus energy on track descriptions and architecture, not premature
     step decomposition.
6. Order the tracks so dependencies are respected — earlier tracks don't
   depend on later ones. Annotate dependencies with
   `> **Depends on:** Track N`.
7. Identify key test scenarios and invariants that must be covered — this
   is strategic (what to test and why), not tactical (how to implement tests).
8. **Anchor the carriers to the tier's decision seed.** In `full`, anchor
   every Architecture Note, Decision Record, and track description to the
   **frozen `design.md`** authored in Step 4a: the design is the seed the
   plan derives from, so the track inline Decision Records mirror its
   D-records, the Component Map matches its class / workflow diagrams, and
   each DR that needs long-form support links to a design section via
   `**Full design**`. The design is **not** re-authored here — it is frozen
   (`design-document-rules.md` Rule 15). If plan derivation surfaces a
   design gap that the frozen design cannot answer, route the design intent
   through a fresh `edit-design` mutation in this Step 4b session before the
   freeze re-applies; do not back-fill it silently into the plan. In
   `lite`/`minimal` there is no `design.md`: anchor the carriers to the
   **research log** directly — Step-4b authoring is the log's sanctioned
   read point (S2), and the track inline Decision Records absorb the log's
   load-bearing decisions and their rejected alternatives.
9. **Author the track sections through the dual-clean loop (Step-4b loop,
   every tier).** Do not author the track-file prose inline. The track files
   are the durable human-facing carrier in every tier (the only one in
   `lite` / `minimal`, where no `design.md` buffers the reader from
   log-derived density), so they go through the same code-grounded author plus
   dual-clean inner loop that `edit-design` runs on `design.md`. Items 1-8
   above settle the decisions, the track boundaries, the Decision Records, and
   the section homes; this item hands that settled shape to the `design-author`
   spawn, which writes the prose, and runs the readability auditor and the
   absorption check against what it wrote. This is the track-path analog of the
   `edit-design phase1-creation` loop (`edit-design/SKILL.md` § Workflow,
   § Step 6), parameterized to `target=tracks`. Run it after items 1-8 produce
   the track shape and before the Step 5 commit.

   The loop reuses three of the four roles Track 1 built (the
   `design-author`, the `readability-auditor`, and the `absorption-check`) plus
   the de-warmed `comprehension-review` gate. Each is an agent definition with a
   minimal `tools:` allow-list spawned by `subagent_type` basename, with its
   per-spawn parameters in a params file under `_workflow/reviews/` (one file per
   spawn; the plan-scoped authoring-loop review home, `conventions-execution.md`
   `§2.5` Third-scope review-file home — the same home `edit-design/SKILL.md`
   § Step 4 uses) and a byte-identical spawn-prompt body that names only that
   file, so the shared prompt body caches across the fan-out. The spawn
   contracts, the
   params-file keys, and the fan-out cache warm-up are the same ones
   `edit-design/SKILL.md` § Step 4 defines for the creation kinds; this item
   reuses them with the track-path values below rather than restating them.

   **Round 1: spawn the author** (`subagent_type: design-author`,
   `description: "Track authoring (Step-4b round 1)"`). Its params file carries
   `target=tracks`, `output_path` set to the `plan/` directory the track files
   land under, `research_log_path` set to the research log under `_workflow/`,
   `round=1`, and (in `full` only) `design_path` set to the frozen `design.md`
   the track prose derives from. The author's seed source is the frozen
   `design.md` in `full`, the research log directly in `lite` / `minimal`. The
   author grounds the whole change, writes every track file's plan-at-start
   sections, and returns a thin summary only, never the drafted track files
   (the by-reference contract; if it returns the draft, the track files are
   still on disk, so proceed with them and note the violation, per
   `edit-design/SKILL.md` § Failure modes). On later rounds the author is
   re-spawned with the auditor's flagged passages and re-grounds only those, as
   in the inner loop below.

   **Per round, run the pair, then evaluate dual-clean.** Each round spawns the
   cold `readability-auditor` (range-sliced fan-out, one spawn per slice,
   sequenced behind the cache warm-up) and the warm `absorption-check`, then the
   round is **dual-clean** when neither returns a `blocker` or `should-fix`
   finding:

   - The **`readability-auditor`** (`subagent_type: readability-auditor`,
     `description: "Readability audit (Step-4b round <N>)"`) audits the track
     prose with `target=tracks` and a slice `range` per spawn. **Slice the
     track-path fan-out per track file: one `readability-auditor` spawn per
     `plan/track-N.md` (in track-number order), its params file carrying
     `target=tracks`, `target_path` set to that one track file, and a
     whole-file `range` (line 1 to the file's last line).** This per-file fan-out
     **omits** `slice_count` and `total_lines` — those two params are passed only
     on the design-path creation-kind fan-out, so the agent-side whole-doc guard
     stays inert on the track path (per `readability-auditor.md` § The whole-doc
     guard, the guard applies only when both params are present). The single
     `(target_path, range)` slice model that `edit-design/SKILL.md` § Step 4
     defines for the design path (one ~200-line window in the single
     `design.md`) does not partition N track files on its own; this per-file
     rule is the deterministic partition, so every orchestrator run produces
     the same set of slices and the same per-slice anchor visibility. The
     standing anchors fill the cross-file vocabulary a single-file slice
     lacks: with `target=tracks` they are the **plan Component Map and each
     track's `## Purpose / Big Picture`** (the design-path anchors
     `## Overview` and `## Core Concepts` do not exist on the track path). It
     owns the prose AI-tell axis on this surface (S4) and reads no log (S1).
     Its too-terse findings become the next round's author `flagged_passages`.
   - The **`absorption-check`** (`subagent_type: absorption-check`,
     `description: "Absorption check (Step-4b round <N>)"`) is the **second
     check**: a separate spawn, not a clause inside the comprehension gate. Its
     params file carries `target=tracks`, `research_log_path` set to the
     research log, and `draft_path` set to the `plan/` *directory* (a directory,
     not a single file: the absorption check reads every `plan/track-N.md`
     `## Decision Log` under it, per `absorption-check.md § Inputs`), plus
     `design_path` set to the frozen `design.md` in `full`. It two-way
     coverage-matches the load-bearing research-log (or, in `full`, `design.md`
     seed) decisions against each track's `## Decision Log` records: a
     load-bearing decision missing from the tracks is a finding, and a track
     record inventing a decision the log lacks is a finding that re-opens the S3
     gate (below) when it is load-bearing, exactly as a decision-shaped
     comprehension finding does. It carries `design_path` only as that seed
     decision source; the **seed↔track fidelity criterion is owned by the
     comprehension gate** (below), so the absorption check does not run it.

   A round that is not dual-clean re-spawns the author with the auditor's
   flagged passages and any absorption log-missing-from-draft decision to seed,
   then re-runs the pair. The loop is bounded by `iteration_budget` (default 3)
   and exits to the user on budget exhaustion (S5), the same termination
   contract the Step 4 part 2 gate and `edit-design/SKILL.md` § Step 6 carry —
   the cap is restated here so the loop carries its own termination rather than
   borrowing it across skills.

   **Cross-round convergence: the same mechanism, parameterized.** The track
   path runs the identical cross-round convergence mechanism the design path
   does — `edit-design/SKILL.md` § Step 6 "The canonical convergence mechanism
   (section-keyed settled-state)" is the canonical statement; this loop
   cross-references it rather than restating it. Apply it with the track-path
   parameters: the **settled-state key** is per `track-N.md` file (not per
   `##` / `# Part` section as on the design path), and the **standing-anchor
   set** the anchor-folded content hash folds in is the plan Component Map plus
   each track's `## Purpose / Big Picture` (not `## Overview` + `## Core
   Concepts`). Fold in only the track-path anchors that exist, mirroring the
   design-path "when present" tolerance: each track's `## Purpose / Big Picture`,
   plus the Component Map when the plan carries one. An absent Component Map on a
   thin `minimal` plan is not an error and does not force a re-audit by itself —
   the hash simply folds fewer anchors. A track that returned clean and is
   unchanged (its anchor-folded hash matches last round) has its re-flags dropped;
   a Component Map edit re-opens every track's settled-state, the same way an
   Overview edit does on the design path. The standing anchors are byte-stable
   for the loop's
   duration — items 1-8 settle the plan Component Map and the track skeletons
   *before* this item's dual-clean loop runs — so the hash does not churn (this
   holds in `lite` / `minimal` too: the Component Map is not still in flux
   during the loop). This adds **only** the convergence-mechanism cross-
   reference. The **slicing unit stays per-file as already stated above** — one
   `readability-auditor` spawn per `plan/track-N.md`, whole-file `range`; do not
   read this as "apply the same partition," which is the design-path per-window
   rule that does not partition N track files.

   **The gate-A7 warm-up deferral (inherited from Track 1).** The fan-out cache
   warm-up is a cost lever, not a correctness dependency: the loop must produce
   correct dual-clean output with the warm-up disabled (the disabled path just
   pays N cold prefixes). When the harness offers no non-blocking fixed-delay
   mechanism, disable the warm-up; Step-4b acceptance does not require a working
   warm-up.

   **The S3 freeze-order gate, then the comprehension gate.** After the inner
   loop reports dual-clean, hold the **S3 freeze-order gate** across the loop
   before running the comprehension gate: the comprehension gate must not run
   while a log-adversarial entry is open. Read the research log's
   `## Adversarial gate record` (the gate's durable verdict carrier; the
   open/resolved and latest-dated-entry rules are in `research.md` §The research
   log under Gate-record cadence), and run the comprehension gate only when the
   latest entry is resolved (the same ordering `edit-design/SKILL.md` § The S3
   freeze-order gate applies on the design path). Then spawn the de-warmed
   `comprehension-review` gate once (`subagent_type: comprehension-review`,
   `description: "Cold comprehension gate (Step-4b track cold-read)"`). Its
   params file carries the `## Inputs` it forwards to
   `prompts/design-review.md` (`target=tracks`, `scope=whole-doc`,
   `plan_dir`, and `plan_path`, plus `design_path` in `full` for the full-tier
   seed↔track fidelity criterion) and names **no** `research_log_path` and
   **no** `output_path`: the absorption cross-check moved off this role onto the
   `absorption-check` spawn above, so the comprehension reviewer runs no prose
   axis (S4) and no absorption cross-check, and with no `output_path` it returns
   its verdict inline. The Step-4b gate omits the `output_path` file-write
   branch that `phase4-creation` uses (`edit-design/SKILL.md` § Step 4)
   deliberately: its return is the bounded comprehension verdict plus a
   summary-shaped `## Structural findings` list, not the long-form structural
   detail `phase4-creation` persists, so the inline return stays small even
   on a wide `full` surface of N track files plus the frozen `design.md`. A decision-shaped comprehension-gate finding re-opens the
   S3 gate and re-enters the inner loop (the author seeds the surfaced decision,
   the absorption check confirms coverage, the auditor re-checks the prose), so
   the comprehension gate re-runs only once the gate clears again. **A
   comprehension-gate re-open consumes a round of the same `iteration_budget`
   the inner loop counts down — it does not start a fresh count.** The
   dual-clean inner-loop rounds and any gate re-entries share one budget, so the
   whole Step-4b review is bounded by `iteration_budget` total rounds across
   both; this is the single-budget reading `edit-design/SKILL.md` § Step 6
   settles on the design path, restated locally so the loop's termination is
   fully self-contained rather than borrowed across skills.

   The whole Step-4b review therefore exits when the inner loop is dual-clean
   **and** the comprehension gate passes, or the budget is spent. The written
   plan and tracks are then presented for the user's pre-persist confirmation,
   which is the presentation D15's review window opens at.

Do NOT implement anything. Only research and plan.

**Compute the workflow-SHA stamp once before writing the templates.**
Run the paired test-and-fallback idiom from
conventions.md:planner:1 `§1.6(b)` verbatim;
every artifact created in **this Step 4b session** reuses the single
`$WORKFLOW_SHA` value, so the plan and track files seeded together share
a stamp by construction:

```bash
WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills .claude/agents)"
[ -z "$WORKFLOW_SHA" ] && WORKFLOW_SHA="$(git rev-parse HEAD)"
```

Substitute the **resolved** value (not the literal `$WORKFLOW_SHA`
token) into the line-1 stamp comment of each of the two Step-4b fenced
templates that follow (the implementation-plan and the track-file
templates). `Write` does not perform shell expansion. If you emit
`$WORKFLOW_SHA` verbatim, the artifact's stamp is malformed and the
drift check will route to migration on the next gate run. The fallback
to `git rev-parse HEAD` covers fresh repos and repos where workflow
paths have been moved; in every other case the path-scoped log already
returns a usable SHA.

The orchestrator (not the author spawn) owns the line-1 stamp, the same
split `edit-design` uses where the skill owns the stamp and the author owns
the prose. The orchestrator writes the plan file directly with the stamp on
line 1; for the track files the author writes in item 9's loop, the
orchestrator hands the author the track-file template with the resolved stamp
already on line 1 (so the author fills only the prose below it) and confirms
each authored track file's line-1 stamp is the resolved value before the
Step 5 commit.

**The `design.md` template is authored in Step 4a, not here (and only in
`full`).** `lite` and `minimal` have no `design.md` — skip this template
and its reference block entirely. In `full`, `design.md` is seeded in the
earlier Step 4a flow via `edit-design` (`phase1-creation`), which
carries its own
idempotency-guarded stamp directive and computes `$WORKFLOW_SHA` at that
point's HEAD. On the collapsed happy path Step 4a and Step 4b run in the
same `/create-plan` invocation, so the design and the plan / track stamps
are normally computed against the same HEAD and match. They can still
diverge on the **crash-recovery path**, where the design committed in one
invocation and the plan derives in a later one: the design's stamp then
differs from the plan / track stamps by however many workflow-format commits
landed between the two invocations. That asymmetry is expected and benign:
the drift gate's no-drift normalization collapses the divergence on the next
clean gate run, and the per-branch migration reunifies the stamps. The
design template below is reproduced for the Step 4a author's reference; do
not re-write `design.md` in Step 4b (it is frozen —
`design-document-rules.md` Rule 15).

The dual-seed `design-mechanics.md` case (when the planner seeds
both `design.md` and `design-mechanics.md` together) does NOT get a
fourth fenced template in this Step. The dual-seed write routes
through `edit-design phase1-creation` with `target=both`, which
carries an idempotency-guarded stamp directive that stamps the file
when it has not already been stamped. Keeping the dual-seed write on
the existing `edit-design` route avoids duplicating a near-identical
template here; the idempotency guard covers both the
`/create-plan`-driven dual seed and a direct `edit-design
phase1-creation` invocation outside `/create-plan`.

In `lite`/`full`, write the **thinned derived-mirror plan** to
`docs/adr/<dir-name>/_workflow/implementation-plan.md`; in `minimal` there
is **no plan** (D2) — skip it and the template below. The planner writes the
thin plan file directly (it is a cross-track summary, not dense human-facing
prose). The **track files** are authored by the `design-author` spawn in
item 9's dual-clean loop, not written inline here: one track file per planned
track at `docs/adr/<dir-name>/_workflow/plan/track-N.md`, to the structure
below. The structure below is the shape the author writes to (the section
homes, the seeded Decision Records, and the placeholders), handed to the
author spawn as the settled track shape, with the author supplying the prose.
The plan is a **derived-mirror plan** (D1): a cross-track summary that holds
no fact a track file does not already own — the `## Checklist` plus a thin
cross-track Component Map. Each track file carries that track's detail
spread across the four homes — `## Purpose / Big Picture` (intro
paragraph), `## Context and Orientation` (codebase state and any
track-level Mermaid diagram), `## Plan of Work` (prose sequence of edits
and additions), and `## Interfaces and Dependencies`
(in-scope/out-of-scope file boundaries, inter-track dependencies,
library/function signatures) — plus the full inline Decision Records in
`## Decision Log` (the track-canonical live carrier, D7) and the combined
`## Invariants & Constraints` (D9). Keeping per-track detail out of the plan
keeps `/execute-tracks` startup context small (see
`.claude/workflow/conventions.md` `§1.2` for the directory layout under
`_workflow/`, the `§1.2` *Per-axis artifact set*, and the `§1.2` thinned
`lite`/`full` plan content, and `conventions-execution.md` `§2.1` for the
track-file shape and section lifecycle).

**No tier line in the plan.** The confirmed design gate and its matched
categories live in the **phase ledger** `design_gate` / `categories` fields
(D4, `conventions.md` `§1.1` *Phase ledger*), seeded at Phase 1 below — not in
a plan line. Every fresh `/execute-tracks` session and the Step 1c resume
check read the design gate from the ledger, the one artifact present for every
change (including a plan-less single-track change), so the plan no longer
carries a `**Change tier:**` line.

Before writing the thinned-plan template, substitute the resolved
40-character SHA into the `$WORKFLOW_SHA` placeholder on line 1.

**Thinned derived-mirror plan (`full` / `lite`).** In `lite` there is no
design, so omit the `## Design Document` block. The plan no longer carries
`### Goals`, `### Constraints`, `### Architecture Notes` (the full Decision
Records, Invariants, and Integration Points), `## Plan Review`, or
`## Final Artifacts` (D5/D7): those move to the track files, the research
log, or the ledger / `plan-review.md` per the disposition in
`conventions.md` `§1.2`. The plan keeps only the thin cross-track Component
Map and the Checklist:

```
<!-- workflow-sha: $WORKFLOW_SHA -->
# <Feature Name>

## Design Document
[design.md](design.md)
<!-- omit the two lines above in `lite` — no design.md exists -->

## Component Map
<!-- Thin cross-track Component Map only: the slice of the system the change
     touches, for cross-track impact assessment. Per-track detail, Decision
     Records, invariants, and constraints live in the track files, not here.
     Mermaid diagram (when 3+ components) + annotated bullet list — see
     planning.md §Architecture Notes format for the budget rules. -->
<Mermaid diagram + annotated bullet list>

## Checklist
- [ ] Track 1: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-1.md>
  > **Scope:** ~N files covering X, Y, Z

- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-2.md>
  > **Scope:** ~N files covering A, B
  > **Depends on:** Track 1
```

`minimal` has no plan (D2); the single `plan/track-1.md` is the whole
change's canonical record and the ledger owns its resume state. Skip the
template above entirely in `minimal` and write only the track file below.

Each track file (`plan/track-N.md`) is created (by the `design-author`
spawn in item 9's loop) with the four
plan-at-start homes (`## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`,
`## Interfaces and Dependencies`) populated, the track's full inline
Decision Records seeded into `## Decision Log` (the track-canonical live
carrier, D7 — see item 4 above), the combined
`## Invariants & Constraints` section populated with the track's testable
constraints and invariants (D9), and the track-level
prose in `## Validation and Acceptance` populated (per-step
EARS/Gherkin lines are Phase A placeholders), the remaining continuous-log
sections empty, and the Phase-A-populated sections
(`## Concrete Steps`, `## Idempotence and Recovery`) left as Phase A
placeholders that decomposition will fill. The canonical section list and lifecycle
table — which writer touches which section in which phase — live in
`conventions-execution.md` `§2.1`; the verbatim ready-to-paste
template body is reproduced below so this SKILL stays
self-sufficient (the lifecycle source is durable; the design-doc
copy is ephemeral and removed in the Phase 4 cleanup commit, so it
cannot be a durable pointer target).

Before writing this template, substitute the resolved 40-character
SHA into the `$WORKFLOW_SHA` placeholder on line 1.

````markdown
<!-- workflow-sha: $WORKFLOW_SHA -->
# Track N: <title>

## Purpose / Big Picture
<One-line BLUF stating the user-visible behavior gained after this track lands.>

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

<Intro paragraph from the plan checklist entry, restated here so the file
is self-sufficient — Phase B/C sub-agents that don't read the root plan
see it.>

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form below); the
section then continues as the execution-time continuous log (inline-replan
choices, scope-downs, dependency reveals, gate-override reasons). Seeded
from the frozen design.md D-records in `full`, from the research log in
`lite`/`minimal`. One block per decision: -->

#### D<N>: <Decision title>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this option won — trade-offs, constraints>
- **Risks/Caveats**: <known downsides or things to watch>
- **Implemented in**: this track (step references added during execution)
<!-- Optional in `full` only: a `**Full design**: design.md §<section>`
line pointing at the frozen seed's mechanism — historical provenance, never
a substitute for the inline record above. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
<What state the codebase is in at the start of this track — files,
modules, non-obvious terminology, concrete deliverables this track
produces. Place any optional track-level Mermaid component diagram
(≤10 nodes) inside this section when the track has 3+ internal
components with non-trivial interactions.>

## Plan of Work
<Prose sequence of edits and additions — the approach, ordering
constraints, invariants to preserve, references to the Concrete
Steps roster below. Phase 1 writes the approach prose; Phase A
appends a per-step sequencing summary that references the Concrete
Steps roster.>

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. The `size:`
clause (`— size: ~N files; <reason>`) appears only on an under-filled
`low`/`medium` step (rule in `track-review.md` §Step Decomposition).
Per-step episodes do NOT live here; they live in `## Episodes` below.
The roster is immutable after Phase A except for the status checkbox
flip and the optional `commit:` annotation Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance
<Track-level behavioral acceptance criteria.>

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies
<In-scope and out-of-scope file boundaries, compatibility
requirements, inter-track dependencies (which other tracks supply
prerequisites; which downstream tracks consume this one's output),
library/function signatures relevant to this track, and the
Integration Points the plan's Architecture Notes carried (D9 folds
them here — entry points, SPIs, callbacks, event flows).>

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes both the
per-track testable constraints (technical, performance, compatibility —
the old plan `### Constraints`) and the testable invariants the plan's
Architecture Notes carried; they share this home because they are the
same shape — a property that must hold, backed by a test (each invariant
becomes a test assertion in the relevant step). A process-only,
non-testable constraint goes to `## Context and Orientation`, or to
`## Decision Log` when it is a real decision — not here. Non-Goals move to
the research log and the PR `## Motivation` (and `design.md` in `full`);
per-track out-of-scope already lives in `## Interfaces and Dependencies`. -->
- <Invariant or constraint that must hold> — verified by <test>.
````

The `## Base commit` section is added by Phase B at session start
and is omitted from the Phase 1 skeleton. Full lifecycle for every
section above is tabulated in `conventions-execution.md` `§2.1`.

**Decide plan presence from the authored track count (D1).** Now that the
track files exist, decide whether `implementation-plan.md` is written:
**`implementation-plan.md` exists iff the change spans more than one track**
(D8a). A cross-track Component Map and Checklist are vacuous for a single
track, so a one-track change writes **no** plan — only its one
`plan/track-1.md` and the ledger carry its Phase-1 state. A multi-track change
writes the thinned derived-mirror plan above alongside its track files. This
decision is made here, at the end of Step 4b, not up front: the track count is
not knowable until the planner has decomposed the change, so the
plan-presence question can only be answered once the track files are authored
(D1). The authored track count is the `--tracks` value seeded into the ledger
just below — the resume router's plan-presence signal.

**Seed the phase ledger (D6/D10).** After the track files (and, for a
multi-track change, the plan) are written and before the Step 5 commit, seed
the phase ledger with the Phase-1 boundary so a later `/execute-tracks`
session resumes off the ledger rather than re-running research and
classification. The ledger is the resume-state home for every change — the
one artifact a plan-less single-track change keeps now that it has no plan
(D2/D3). Append one event line with `workflow-startup-precheck.sh
--append-ledger`, recording the confirmed design gate, the authored track
count, the Phase-1-complete marker, the matched categories, and (when the
plan declares workflow-modifying) the `§1.7` staging mode; the phase is `0`
because the next gate is the State-0 autonomous plan review, which has not yet
run (`workflow.md` § Startup Protocol, `phase == "0"`):

```bash
# Parse the `level=` token from the statusline file (the same read the
# `## Progress` / `## Episodes` writers use per conventions-execution.md §2.1);
# default to `safe` when the file is missing.
CTX_LEVEL="$(sed -n 's/.*level=\([a-z]*\).*/\1/p' \
    "/tmp/claude-code-context-usage-$PPID.txt" 2>/dev/null)"
[ -n "$CTX_LEVEL" ] || CTX_LEVEL="safe"

.claude/scripts/workflow-startup-precheck.sh --append-ledger \
    --ctx "$CTX_LEVEL" \
    --phase 0 \
    --design-gate "<the design_gate value confirmed in Step 4 part 1>" \
    --tracks "<count of plan/track-*.md files just authored>" \
    --phase1-complete yes \
    --categories "<comma-separated centrally-matched HIGH-risk categories, or empty>"
    # add --s17 with the staging-mode token only when the plan declares
    # workflow-modifying or takes the §1.7 prose-rule opt-out (conventions.md
    # §1.7(b)/(k) — the marker home is this ledger field, D4). Omit otherwise.
```

`--design-gate` carries the Step-4 part-1 classifier's confirmed value;
`--tracks` carries the authored track count (1 for a single-track change,
> 1 for a multi-track change — the plan-presence signal the resume router
reads, decided at the end of Step 4b just above); `--phase1-complete yes`
records that Phase 1 finished cleanly, which is the marker the resume router
uses to tell the design+single-track steady state apart from a mid-authoring
crash. The `--ctx` value is the current context level (`safe` / `info` /
`warning` / `critical`) parsed from the statusline file; omit `--ctx` to let
the script default it to `safe`. The append is atomic (temp-file-plus-rename)
and **loud**: a malformed field value exits 3 and a write error exits non-zero
with a stderr diagnostic, so check the exit status — a zero exit means the
boundary is recorded (`conventions.md` `§1.1` *Phase ledger*; the grammar and
key set `{ phase, track, design_gate, tracks, phase1_complete, reconciled_tag,
substate, categories, s17, paused }` are pinned in the script header). The
phase vocabulary is exactly `{0, A, C, D, Done}`; `create-plan` only ever
writes `phase 0`. Track is omitted at Phase 1 (the ledger names no active
track until Phase C; the plan-less single-track resume defaults the active
track to `track-1`). The ledger file itself
(`_workflow/phase-ledger.md`) is unstamped (D13, `§1.6(f)`) and is swept
into the Step 5 commit by the blanket `git add docs/adr/<dir-name>/_workflow/`.

In Step 4a (`full` only), write the design document to
`docs/adr/<dir-name>/_workflow/design.md` using this structure (via
`edit-design`, not direct `Write`). Before writing this template,
substitute the resolved 40-character SHA into the `$WORKFLOW_SHA`
placeholder on line 1.

```
<!-- workflow-sha: $WORKFLOW_SHA -->
# <Feature Name> — Design

## Overview
<Brief summary of the design approach — what the solution looks like at a
structural level, which major components are involved, and how they interact.>

## Class Design
<Mermaid classDiagram(s) showing new/modified classes, interfaces, relationships.
Pair each diagram with prose explaining responsibilities and design choices.>

## Workflow
<Mermaid sequenceDiagram(s) and/or flowchart(s) showing runtime behavior of key
operations. Pair each diagram with prose explaining the flow.>

## <Complex Topic 1>
<What the complex part is, why it is designed this way, gotchas/edge cases.>

## <Complex Topic 2>
<What the complex part is, why it is designed this way, gotchas/edge cases.>
```

**Step 4 review-hold batching (D15).**

This is the consumer of the Step 4 part 2 gate. Step 4 part 2 owns the
first, pre-presentation gate run; once a frozen-ready Phase-1 artifact is
presented for user review, every later finding flows through the batch below
rather than re-running the gate one finding at a time. The forward pointer in
the Step 4 part 2 §"Pre-presentation re-trigger vs post-presentation queue"
paragraph lands here.

**When the queue opens.** A frozen-ready artifact is presented for review at
two points: `design.md` after the Step-4a cold-read passes (`full` only), and
the plan plus track files at Step 4b's pre-persist confirmation (every tier).
The window opens at the presentation the user reviews from — a presentation
with a PASS outcome, or the user's acceptance of open risks on a presentation
that still carries residual findings. D5's per-entry immediate gate
re-trigger governs **pre-presentation authoring only**; every decision
surfaced after that boundary joins the queue, whether the user raised it or an
agent surfaced it.

**The tagged queue.** Findings raised during the review collect into a queue,
each tagged by shape:

- `[clarification]` — wording only, no decision content (a confusing
  sentence, a missing cross-reference, a stale framing). Applies in the
  mutation step without a gate run.
- `[decision]` — a new or changed decision (a rejected alternative revived, a
  scope boundary moved, an invariant reworded). Must clear the gate before it
  can apply.

Do **not** process findings one by one. Hold them until the user declares the
review done, then run the batch.

**The three-step batch.** When the user declares the review done:

1. **One gate run.** All `[decision]` items append to the research log's
   Decision Log together, and one gate run (the Step 4 part 2 third-scope
   adversarial spawn, same model/effort per D14, same `_workflow/reviews/`
   output path and thin-manifest return per D17) validates them. **Each loop
   iteration re-challenges every entry in the batch**, not just the changed
   one, because a fix to one entry can invalidate a sibling's PASS; the gate
   re-runs whole-batch until no blocker remains and every should-fix has been
   addressed in the log. The iteration ≥2 runs use the verdict-producer
   manifest variant (per `conventions-execution.md` `§2.5`), exactly as the
   Step 4 part 2 gate loop does. The whole-batch re-run inherits the Step 4
   part 2 gate's `iteration_budget` cap (default 3) and its budget-exhausted
   recovery: on exhaustion with findings still open, the user is the gate
   (the same escalation, not an unbounded loop).
2. **One mutation.** After the gate clears, the cleared `[decision]` items and
   the `[clarification]` items apply to the artifact in **one** mutation
   (through `edit-design` for `design.md`, through the Step-4b plan/track
   authoring for the plan and tracks). A decision-shaped finding surfaced
   **inside** the mutation exits to the log before any fix attempt; it is
   never auto-fixed in place, so the gate always sees it first.
3. **One cold-read, with loop-back.** One cold-read (the Step-4a `design.md`
   cold-read, or the Step-4b dual-clean loop ending in its comprehension gate,
   whichever the presented artifact uses) covers all the batch's
   changes. A decision-shaped finding from it (a comprehension-gate finding or
   an absorption-surfaced draft-invents-decision) **re-enters the gate step**
   (step 1); while a log entry is open the batch cannot close and the artifact
   cannot re-present. S3 holds across the whole loop: no cold-read or
   comprehension gate runs while a log-adversarial entry is open.

A budget-exhausted mutation escalates to the user as a failure, not as a
re-presentation: the artifact does not move mid-review.

**Escape hatch.** The queue is the default, not a hard gate. The user may ask
for immediate processing of a single blocking finding; that finding runs the
single-decision route (one gate run, one mutation, one cold-read for the lone
finding) at its full per-finding cost, and ends with a one-line note of what
moved: in chat, and in the handoff's queue block when the hold spans
sessions. The single-decision route carries the **same step-3 loop-back as
the three-step batch**: if its terminal cold-read surfaces a *new*
decision-shaped finding, that finding re-enters the gate (or is enqueued as a
new `[decision]` item) and the escape-hatch route does **not** close while a
log entry it opened is unresolved — so S3 holds on the escape-hatch path too.
Only once its cold-read is clean is the processed finding dropped from the
in-session queue so the review-done batch does not re-process it (the
handoff's `Escape-hatch findings already processed this hold` line is the
cross-session form of the same dedup rule).

**Multi-session holds.** A queue that outlives the session carries in the
mid-phase handoff. When the context-consumption gate fires a pause with a
non-empty review-hold queue, the handoff author records the queue per the
queue block in `mid-phase-handoff.md`:planner:1 § Review-hold queue block
(D15), so the flush session re-loads the tagged entries instead of asking the
user to re-raise them. A held batch flushes at cold context in that later
session, which counts as the "same session" for the blocker loop above; a
batch of one degenerates to the single-decision route.

**Step 5 — Commit, push, and open the draft PR.**

Step 5 commits whatever the session produced, and its commit cadence is
**tier-keyed**. The research log itself (created in Phase 0) and the gate's
committed review files under `_workflow/reviews/` are already on disk; the
blanket `git add docs/adr/<dir-name>/_workflow/` below sweeps any of them
not yet committed.

- **`full`, two session-end commits, one session (Step 4a then Step 4b)** — after the
  4a/4b collapse both session-end commits land in **one** `/create-plan`
  invocation, in order, without a session boundary between them.
  - **First commit, at the Step 4a freeze (the crash checkpoint).** `design.md`
    is frozen but no plan exists yet. Commit the design (and the research log,
    if not yet committed) with the message `Add initial design`, then push. Open
    the draft PR here (sub-steps 4-7 below) so the frozen design is visible to
    teammates and so the commit survives a crash before plan derivation — this
    first commit is the crash checkpoint the collapse preserves (D15). Do **not**
    end the session; flow on to Step 4b. **Draft-PR-exists guard.** A
    crash-recovery resume that re-entered Step 4a (Step 1c routed an
    interrupted-and-dirty 4a back through the `edit-design` loop) may have
    already pushed and opened the draft PR before the interruption. If
    `gh pr view` shows a draft PR already exists for this branch, skip the
    PR-open sub-steps (4-7) and only commit/push the re-frozen `design.md`. This
    mirrors the second-commit skip below.
  - **Second commit, at the end of Step 4b (plan derivation).** The plan and
    track files now exist alongside the just-committed design. Commit them with
    the message `Add initial implementation plan`, push (the upstream and draft
    PR already exist from the first commit, so skip the `-u` and the PR-open
    sub-steps), and end the session. **Idempotency guard** (mirrors the Step 1c
    "both files exist" guard): if `implementation-plan.md` is already committed
    and clean, the plan was persisted on a prior attempt; skip the commit and
    proceed to push/end. On a crash-recovery resume that re-entered at Step 4b
    (the design was already committed and clean from the prior invocation's
    first commit), only this second commit lands in the resuming session — the
    `Add initial design` checkpoint is already on disk.
- **`lite` / `minimal`, one session-end commit, one session** — there is no `design.md`
  and no session boundary: the research log, the phase ledger, the thinned
  plan (`lite` only — `minimal` has no plan, D2), and the track files were
  produced in one session. Commit them together
  with the message `Add initial implementation plan`, push **with `-u`**
  (this is the first push on the branch, so the upstream and draft PR are
  opened here, sub-steps 4-7), and end the session. The same draft-PR-exists
  and idempotency guards apply: skip the PR-open sub-steps if `gh pr view`
  already shows a draft PR, and skip the commit when the tier's primary
  Phase-1 artifact is already committed and clean — `implementation-plan.md`
  in `lite`, `plan/track-1.md` in `minimal` (which has no plan).

Once the user confirms the files this session produced look right, persist
the work to GitHub so it survives local-disk loss and is visible to
teammates as a draft PR:

1. Stage and commit the `_workflow/` files in a single commit (use the
   tier-appropriate message from the bullets above):
   ```bash
   git add docs/adr/<dir-name>/_workflow/
   # "Add initial design" at full Step 4a;
   # "Add initial implementation plan" at full Step 4b and at lite/minimal
   git commit -m "Add initial implementation plan"
   ```
2. Push the branch:
   ```bash
   git push -u origin <branch>
   ```
   (Use `git push` on subsequent pushes once upstream is set.)
3. **Deferred-drift recital (silent no-op when nothing was deferred).**
   If Step 1.5's Defer resolution created the TaskCreate todo titled
   `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>`
   (or the unstamped variant `Deferred workflow drift: unstamped
   artifacts in active plan, see /migrate-workflow`) earlier in this
   session, read the todo title and recite it verbatim, followed by
   an instruction to run `/migrate-workflow` from this worktree to
   pick up the deferred work. Scan session TaskCreate todos for any
   title matching the prefix `Deferred workflow drift:` — there is at
   most one per session because Step 1.5 fires at most once. If
   TaskCreate was unavailable at Step 1.5 and the two fields are held
   in in-context memory instead, recite the same line shape from
   memory. If no TaskCreate todo can be located and no
   in-context-memory fallback was recorded at Step 1.5 (i.e., no
   Defer resolution fired this session), skip this sub-step silently
   rather than fabricate a recital. The recital fires before the
   draft PR is opened so the user sees the residue in the same
   session; it mirrors the recital `workflow.md § What to do before ending a session` runs for `/execute-tracks`.
4. Ask the user **once**, before opening the PR:
   *"Provide an issue prefix for the PR title (e.g. `YTDB-123`)?
   Leave blank to skip."*
   Branch names in this project often do not encode the issue
   prefix; the user tracks it in the PR title instead.
5. Compose the PR title:
   - With a prefix `<P>`: `[<P>] <feature title>` — e.g.
     `[YTDB-123] Index histogram for selective range scans`
   - Without a prefix: `<feature title>`
6. Compose the PR body: `## Motivation` (the change's aim and constraints
   from the research log's `## Initial request` and `## Decision Log` —
   under the derived-mirror model the plan no longer carries `### Goals` /
   `### Constraints`, so the aim is sourced from the research log in every
   tier — distilled into prose; apply the Ephemeral
   identifier rule from `conventions-execution.md` `§2.3` to the body
   since PR titles and descriptions are durable), `## Plan` (one line per
   track — from the `lite`/`full` plan `## Checklist`, or from the single
   `plan/track-1.md` in plan-less `minimal` — no internal IDs, a single
   line in `minimal`), and a `## Status` line stating *"Draft — workflow
   scaffolding under `docs/adr/<dir-name>/_workflow/` will be removed in
   the Phase 4 cleanup commit before merge."* In `minimal`, the PR
   description is also the **durable verdict carrier**: Phase 4 folds the
   research log's adversarial-gate verdict summary into it (D16), since a
   `minimal` change writes no `docs/adr/` entry. Step 5 only seeds the body;
   the Phase-4 fold is `create-final-design`'s job.
7. Open the PR in **draft** mode using `gh`:
   ```bash
   gh pr create --draft --base develop \
       --title "<title built above>" \
       --body "$(cat <<'EOF'
   ...
   EOF
   )"
   ```
   Print the resulting PR URL so the user can share it.

CI does not run on draft PRs, so the per-commit pushes through the
rest of the workflow carry no CI cost. The user manually flips the
PR from draft to "ready for review" at the end of Phase 4 — Claude
never runs `gh pr ready` automatically.

When I'm satisfied, I'll run `/execute-tracks` to start track execution.
The autonomous plan review (Phase 2 — consistency + structural) runs as
its first phase and ends the session before track work begins. I can
also run `/review-plan` manually at any time to re-validate the plan
(useful after inline replanning produces a revised plan).
