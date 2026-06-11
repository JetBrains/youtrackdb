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

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:planner:0,1 `§1.5` for the workflow-level anchor and tier mapping.

> **Stamp discipline.** Every `_workflow/**` artifact this SKILL creates carries a line-1 `<!-- workflow-sha: <40-char SHA> -->` stamp written at creation. Direct-mutation kinds applied later by `edit-design` (`content-edit`, `section-add`, `section-remove`, `section-rename`, `section-move`, `structural-rewrite`, `mechanics-edit`, `design-sync`) leave the stamp untouched and preserve its line-1 position; only artifact creation, migration replay, and no-drift normalization write the stamp. The format definition, parser idioms, and the paired SHA-computation idiom this SKILL copies into its planning-transition step are anchored in conventions.md:planner:1 `§1.6`. Read that section for the single source of truth.

**Step 1 — Read workflow documents.**

Read these in order before doing anything else (do NOT ask the user anything yet):
1. `.claude/workflow/conventions.md` — shared formats,
   glossary (including the **change tier** and **tier gates** terms),
   plan file structure, the `§1.2` *Per-tier artifact set*, scope
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

**Step 1c — Tier-aware resume check (before the aim prompt).**

After Step 1.5 (drift) and Step 1a (handoff) have cleared, check the
two design-first artifacts on disk:

```bash
ls docs/adr/<dir-name>/_workflow/design.md docs/adr/<dir-name>/_workflow/implementation-plan.md 2>/dev/null
```

The branch added for tier-adaptive Phase 1 is the **no-design tier**: in
`lite` and `minimal` there is no `design.md` by design (`planning.md`
§Tier classification), so a no-design tier interrupted mid-planning must
not route back to Step 4a design authoring on resume the way the old
bare-presence routing would. The disambiguator is `implementation-plan.md`
**presence and its tier line** (D18) — never a fresh read of the research
log, which would be a third decision-content read site and break S2. The
tier line is the one Step-4 writes into the plan (see Step 4 / the
aggregator-plan template). The plan stub is shape-complete from the moment
Step 4 writes it (D1), so the no-design-tier-in-progress case fires only
when `implementation-plan.md` is present (its tier line readable there)
and `design.md` is absent.

Route on what exists:

- **`design.md` exists, `implementation-plan.md` does not** — `full` tier
  mid-authoring (only `full` writes a `design.md`). The design seed may be
  frozen, but file presence alone is not proof: `edit-design` writes
  `design.md` to disk in its *apply* step, **before** the cold-read review
  runs and before Step 5 commits it. A Step 4a session interrupted after
  the write but before the review passed (context-full `/clear`, crash, no
  handoff) leaves an **unreviewed, uncommitted** `design.md` on disk. So
  before auto-resuming into Step 4b, confirm the design is **committed and
  clean** — the on-disk proxy for "frozen and reviewed", since Step 5
  commits `design.md` only after its review passes:

  ```bash
  # committed: at least one commit touches design.md
  git log -1 --format=%h -- docs/adr/<dir-name>/_workflow/design.md
  # clean: no uncommitted changes to design.md (empty output = clean)
  git status --porcelain docs/adr/<dir-name>/_workflow/design.md
  ```

  - **Committed (non-empty `git log`) AND clean (empty `git status`)** —
    the design is frozen and reviewed. **Auto-resume into Step 4b** (plan
    derivation): skip Step 2's aim prompt and Step 3's Phase 0 research
    loop entirely — the aim and research are already captured in the frozen
    `design.md` and the conversation that produced it. Read `planning.md`
    (deferred from Step 1) and derive the plan from the frozen design.
  - **Uncommitted (empty `git log`) OR dirty (non-empty `git status`)** —
    Step 4a was interrupted mid-authoring. **Resume Step 4a**, not Step 4b:
    re-enter the `edit-design` review loop so the adversarial gate and
    cold-read pass run and the design is committed before any plan derives
    from it. Re-entering the loop on an already-good design is idempotent
    and harmless, so this branch is safe even on a false alarm (e.g., a
    stray editor write left the file dirty).
- **`implementation-plan.md` exists, `design.md` does not** — read the
  plan's tier line (D18; the format is fixed in Step 4 / the
  aggregator-plan template). Two sub-cases, both keyed on the tier line
  already on disk, never on a new log read:
  - **Tier line says `lite` or `minimal`** — this is a no-design tier whose
    plan is already derived; the missing `design.md` is by design, not a
    sign of an interrupted Step 4a. This is a normal resume, not a Step-4
    entry: the drift / handoff / state routing above already handled it; do
    not re-run Step 4 and do not route to design authoring. Proceed to
    Step 2 only if the user explicitly asks to start a new aim against the
    same dir (rare).
  - **Tier line says `full` (or is absent / unreadable)** — a `full`-tier
    plan with no `design.md` is malformed (the design should have been
    authored and committed first); an absent tier line means the plan
    predates the tier scheme. Treat it as the **Both files exist** normal
    resume below and surface the inconsistency to the user rather than
    silently re-deriving — do not re-run Step 4.
- **Neither file exists** — fresh start. Proceed to Step 2 (aim), then
  Step 3 (research), then Step 4 (the tier classifier + adversarial gate,
  then per-tier Step 4a/4b). This also covers the narrow `/clear` window
  where Step 4's gate cleared but the plan stub was not written yet: with
  no `implementation-plan.md` on disk there is no tier line to read, so the
  resume correctly reads as a fresh start and Step 4's classifier re-runs,
  re-deriving the tier from the now-populated log through its existing
  sanctioned authoring read — no extra read site, S2 intact.
- **Both files exist** — the plan is already derived (`full` tier, design
  and plan both committed); this is a normal resume, not a Step-4 entry.
  The drift / handoff / state routing above already handled it; do not
  re-run Step 4. Proceed to Step 2 only if the user explicitly asks to
  start a new aim against the same dir (rare); the common case is the
  session has nothing new to plan.

This check has a defined resume path for every artifact combination, so a
**committed, clean** `design.md` with no plan is never a dead end — it
always routes to Step 4b, an uncommitted or dirty one routes back to
Step 4a to finish authoring, and a no-design tier with a derived plan
resumes normally without re-entering design authoring. The check runs
**after** the drift and handoff gates so a pending migration or handoff
resolves first (those can change what is on disk), and **before** the aim
prompt so a Step-4b resume does not re-ask for an aim already captured in
the design or the research log.

**Step 2 — Ask the user for the aim, then seed the research log.**

Skip this step when Step 1c auto-resumed into Step 4b (the aim is already
captured in the frozen `design.md` and the research log). Otherwise, after
you have finished reading the workflow documents, ask the user to describe
the aim and goal for this session. Do NOT proceed until the user provides
the aim. Wait for the user's response before starting any research or
planning work.

Once the user provides the aim, write the **research log's `## Initial
request`** — the verbatim aim — as the first durable Phase-0 action. The
`_workflow/plan/` directory already exists from Step 1b
(`mkdir -p .../plan`), so write the log directly: create
`docs/adr/<dir-name>/_workflow/research-log.md` (a `Write`, not a shell
command) with the five
sections `research.md` §The research log defines: `## Initial request`
(the verbatim aim, written once), and the empty `## Decision Log`,
`## Surprises & Discoveries`, `## Open Questions` continuous logs, plus
`## Baseline and re-validation` filled **only** on a workflow-modifying
branch. The log is created **unstamped** — it is on the `§1.6(f)` never-
stamped list (D19), so no line-1 `workflow-sha` comment is written and the
`§1.6(b)` paired-idiom does not run for it. Idempotent on resume: if the
log already exists (a prior Phase-0 session created it), leave its
`## Initial request` intact and append to the continuous logs only.

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
  challenges (`research.md` §The research log for the append cadence)
- Do **NOT** produce plan files, design documents, or track decompositions

Stay in research mode until the user explicitly asks to create the plan
(e.g., "create the plan", "let's plan this", "proceed to planning").

**Step 4 — Classify the tier, gate the research log, then transition to planning (Phase 1).**

Phase 1 is **tier-adaptive** (`planning.md` §Tier classification): a
one-line fix does not pay the ceremony a durability rework needs. Step 4
runs in three parts at the Phase 0 → 1 boundary, before any Phase-1
artifact is authored:

1. **Tier classification** — propose the `full` / `lite` / `minimal` tier
   from the now-rich research log, with the centrally-matched HIGH-risk
   categories, and let the user confirm.
2. **The adversarial gate** — run the relocated adversarial review on the
   research log as a gate (loop on blockers, gate on should-fix, no
   `skip`), domain-primed by the confirmed tier's matched categories.
3. **Per-tier transition** — branch to Step 4a (design-first, `full` only)
   or straight to Step 4b (plan + tracks, `lite`/`minimal`).

**Step 4 part 1 — The two-gate tier classifier.**

When the user asks to create the plan, before authoring anything, confirm
the research log captures the conversation's decisions (append any settled
but unlogged), then classify the change. Read the now-rich research log's
`## Decision Log`, `## Surprises & Discoveries`, and `## Open Questions` —
this is a sanctioned Step-4 authoring read (S2). Propose the tier from the
two orthogonal gates (`planning.md` §Tier classification, `conventions.md`
`§1.1` **tier gates** glossary):

| Gate | Question | Answers |
|---|---|---|
| Gate 1 | Does the change need a `design.md`? | yes / no |
| Gate 2 | Does the change span multiple tracks? | multi / single |

`design = yes` implies multi-track, so the two gates collapse to three
reachable tiers: `full` (design + multi), `lite` (no design + multi),
`minimal` (no design + single).

Gate 1's "needs a design" test reuses the HIGH-risk category list in
`risk-tagging.md` §Gate 1 reuse (change-level), read at the **change**
level: Gate 1 is yes only when one of those categories is **central to the
change's purpose**, not merely touched by one incidental edit. Record the
**centrally-matched** categories — they prime the adversarial gate's
lenses in part 2 and seed the Phase-4 durable carrier's lens set (D16).

Propose the tier and the matched categories to the user and **wait for
confirmation**. The user confirms or overrides the tier in either
direction, and may **add or drop an adversarial lens** explicitly at this
point (D16) — confirming the tier confirms the matched categories, so a
tier override may shift the lenses. A Gate-1-no change runs its gate
lens-free unless the user adds one. This is a human gate on the
artifact-shedding decision; do not proceed to part 2 until the tier and
the lens set are confirmed.

**Step 4 part 2 — The adversarial gate on the research log.**

Once the tier is confirmed, run the relocated adversarial review on the
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
    confirmed tier's Gate 1, plus any user-added lens — or (none) for a
    Gate-1-no change with no user lens
  - output_path: docs/adr/<dir-name>/_workflow/reviews/research-log-adversarial-iter<N>.md
    (one file per gate iteration, <N> starting at 1 — the <type>-iter<N>.md
    naming conventions-execution.md §2.5 §Third-scope review-file home fixes)
  - codebase_path: the repo root
  ```

**Model and effort (D14).** Pin the spawn's model on the `Agent` tool's
`model` field by the confirmed tier:

- `full` → `model: fable`
- `lite` / `minimal` → `model: opus`

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
- There is **no `skip`** — the log is not a track that can be dropped; a
  would-be `skip` is raised to a `blocker` so the change is re-justified in
  research before any artifact derives.

The gate's review files are ephemeral (they die at the Phase 4 cleanup);
the durable verdict carrier the Phase-4 consumers read is the research
log's own gate records, not these files (`conventions-execution.md` `§2.5`
§Third-scope review-file home).

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

**Step 4 part 3 — Per-tier transition to Phase 1.**

After the gate clears, branch on the confirmed tier:

- **`full`** — design-first, two sessions across a mandatory boundary
  (Step 4a then Step 4b), exactly as the rest of this Step describes.
- **`lite`** — no `design.md`. Author the aggregator plan and the
  multi-track files directly from the research log in a **single Phase-1
  session** (Step 4b only); the track files carry the full inline Decision
  Records, with no design seed to derive from.
- **`minimal`** — no `design.md`. Author the **shape-complete stub** plan
  (the stub spec lives in the Step-4b template below) and **one**
  self-contained track file from the research log in a **single Phase-1
  session** (Step 4b only).

The `full`-tier design-first split mirrors the boundary already enforced
between Phases A, B, and C:

- **Step 4a (design authoring, `full` only)** — author `design.md` via
  `edit-design`, run its review, and freeze it. The session ends when the
  design's review passes (or the user accepts open risks).
- **Step 4b (plan derivation)** — in a fresh `/create-plan` session for
  `full`, or the same Phase-1 session for `lite`/`minimal`, derive the
  Architecture Notes, Decision Records, and track files (from the frozen
  `design.md` in `full`; from the research log in `lite`/`minimal`).

**Design→plan session boundary and auto-resume (`full` tier only).** In
`full`, Step 4a ends the session once `design.md` is frozen and committed;
it does **not** flow straight into Step 4b. The user re-invokes
`/create-plan`, and the startup protocol auto-resumes into Step 4b when
**`design.md` is committed and clean and `implementation-plan.md` does not
exist** — the frozen design seed is on disk but the plan has not been
derived yet. The committed-and-clean test (not bare file presence) is what
proves the design is reviewed rather than abandoned mid-authoring; Step 1c
spells out the exact `git log` / `git status` check and the resume-Step-4a
fallback for an uncommitted or dirty design. This is checked after Step 1.5
(drift) and Step 1a (handoff) have cleared and before the aim prompt
(Step 2): a resume into Step 4b skips the aim prompt and the Phase 0
research loop, because the aim and research are already captured in the
frozen `design.md` and the conversation that produced it. When **neither**
file exists, `/create-plan` starts at Phase 0 research as usual; when
**both** exist, the plan is already derived and the session resumes via the
normal handoff / drift / state routing rather than re-running Step 4. The
resume path is never a dead end: a committed, clean frozen `design.md` with
no plan always routes to Step 4b plan derivation.

The `lite` and `minimal` tiers have **no `design.md`** and no session
boundary: their Step-4b plan derivation runs in the same Phase-1 session
that Step 4 part 1/2 ran in. Step 1c's tier-aware branch keeps an
interrupted no-design tier (plan on disk, no `design.md` by design) routing
to a normal resume rather than back into design authoring.

**Step 4a — Author the design first (`full` tier only).**

This sub-step runs in `full` only. In `lite`/`minimal` there is no
`design.md`; skip directly to Step 4b. When the user asks to create the
plan in `full` (and `design.md` does not yet exist):

First, read the design workflow document (deferred from Step 1):
- `.claude/workflow/design-document-rules.md` — design document rules,
  structure, and examples

Summarize the key research findings and decisions from the conversation, then
author `design.md` via the `edit-design` skill (`phase1-creation` kind) —
**not** direct `Edit` / `Write`. Under the relocated adversarial review
(D6), the decision/assumption challenge already ran on the research log at
Step 4 part 2's gate, so the `phase1-creation` review is now **cold-read
only** (see `edit-design/SKILL.md` § Workflow and
`design-document-rules.md` § Working / sync): the cold-read assesses
whether a fresh reader can build a working mental model and runs the
absorption-completeness cross-check (every load-bearing research-log
decision in scope appears as a seed D-record in `design.md`). The cold-read
is **gated behind the log-adversarial gate clearing** (S3): a `design.md`
draft cannot reach cold-read while a log-adversarial entry is open, so a
load-bearing decision surfaced while authoring the design is appended to
the log, re-challenged at the gate, and cleared before the cold-read
assesses comprehension — the same ordering the in-`edit-design` adversarial
pass used to give. Iterate until the cold-read passes (or the user accepts
open risks), then write the design document to
`docs/adr/<dir-name>/_workflow/design.md` using the structure below. The
design document must incorporate findings and decisions from the research
phase — it reflects the design choices discussed with the user.

Commit the frozen `design.md` (Step 5 carries the commit/push/draft-PR
mechanics), then **end the session.** Plan derivation resumes in a fresh
`/create-plan` session via the auto-resume condition above.

**Step 4b — Derive the plan and track files.**

In `full`, the startup protocol routes here on re-invocation when
`design.md` exists and `implementation-plan.md` does not (the design seed
the plan derives from). In `lite`/`minimal`, Step 4b runs in the same
Phase-1 session immediately after the Step 4 gate clears — there is no
`design.md`, so the **research log** is the seed the carriers absorb. Read
the planning workflow document (deferred from Step 1):
- `.claude/workflow/planning.md` — Phase 1 instructions:
  goal, tier classification, plan file structure, architecture notes
  format, track descriptions, scope indicators, checklist decomposition
  rules

Then derive the plan and track files. **The decision seed is tier-keyed**
(S2): in `full`, seed Decision Records from the frozen `design.md` seed's
D-records; in `lite`/`minimal`, Step-4b authoring is itself the research
log's sanctioned read point, so the track Decision Records absorb the log's
load-bearing decisions directly. The aggregator plan, track Decision
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
9. **Run the write-time cold-read on the track sections (Step-4b
   cold-read, every tier).** After the track files are written and before
   the Step 5 commit, spawn the cold-read sub-agent via the `Agent` tool
   (the same recipe the sibling `edit-design/SKILL.md` uses for its
   `phase1-creation` cold-read) with `target=tracks` to assess whether a
   cold reader can build a working mental model of the plan-at-start track
   sections, and to run the absorption-completeness cross-check (D8):

   - `subagent_type`: `general-purpose`
   - `description`: `"Step-4b cold-read (target=tracks)"`
   - `prompt`: the full content of
     `.claude/workflow/prompts/design-review.md`, with the `## Inputs` block
     at the top extended by these literal substitutions:

     ```
     - target: tracks
     - research_log_path: <abs path to the research log under _workflow/>
     - tier: <the confirmed tier — selects whether the full-tier fidelity criterion applies>
     - plan_dir: <abs path to the plan/ directory under _workflow/>
     - plan_path: <abs path to the implementation plan under _workflow/>
     - design_path: <abs path to the design doc under _workflow/>   # full only; omit this line in lite/minimal
     ```

   The `design_path` line is present in `full` only, so the reviewer can run
   the seed↔track fidelity check; omit it in `lite`/`minimal`. Supply **no**
   `output_path`: the Step-4b
   cold-read returns inline, exempt from the review-file rule the same way
   the Step-4a `phase1-creation` cold-read is (`prompts/design-review.md`
   § Output format). A `blocker` re-opens Step-4b derivation in the **same
   session** (the iterate loop mirrors `edit-design`); the written plan and
   tracks are then presented for the user's pre-persist confirmation, which
   is the presentation D15's review window opens at.

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

**The `design.md` template is authored in Step 4a, not here (and only in
`full`).** `lite` and `minimal` have no `design.md` — skip this template
and its reference block entirely. In `full`, `design.md` is seeded in the
earlier Step 4a session via `edit-design` (`phase1-creation`), which
carries its own
idempotency-guarded stamp directive and computes `$WORKFLOW_SHA` at that
session's HEAD. Because Step 4a and Step 4b are different sessions, the
design's stamp can differ from the plan / track stamps by however many
workflow-format commits landed between the two sessions. That asymmetry
is expected and benign: the drift gate's no-drift normalization
collapses the divergence on the next clean gate run, and the per-branch
migration reunifies the stamps. The design template below is reproduced
for the Step 4a author's reference; do not re-write `design.md` in Step
4b (it is frozen — `design-document-rules.md` Rule 15).

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

Write the implementation plan to
`docs/adr/<dir-name>/_workflow/implementation-plan.md` AND one track
file per planned track at
`docs/adr/<dir-name>/_workflow/plan/track-N.md` using the structures
below. **The plan template is tier-keyed:** `full` and `lite` write the
full aggregator plan (Goals, Constraints, Architecture Notes, Decision
Records, multi-track Checklist); `minimal` writes the **shape-complete
stub** (the dedicated stub template after the full one). The plan carries
strategic context plus a thin checklist; each track file carries that
track's detail spread across the four homes — `## Purpose / Big Picture`
(intro paragraph), `## Context and Orientation` (codebase state and any
track-level Mermaid diagram), `## Plan of Work` (prose sequence of edits
and additions), and `## Interfaces and Dependencies`
(in-scope/out-of-scope file boundaries, inter-track dependencies,
library/function signatures) — plus the full inline Decision Records in
`## Decision Log` (the track-canonical live carrier, D7). Keeping
per-track detail out of the plan keeps `/execute-tracks` startup context
small (see `.claude/workflow/conventions.md` `§1.2` for the directory
layout under `_workflow/` and the `§1.2` *Per-tier artifact set*, and
`conventions-execution.md` `§2.1` for the track-file shape and section
lifecycle).

**The tier line (D18).** Every tier's plan — the full aggregator and the
`minimal` stub alike — carries a tier line directly under
`## High-level plan`, so every fresh `/execute-tracks` session and the
Step 1c resume check read the confirmed tier and its matched categories
from the one artifact present in every tier:

```
**Change tier:** <full | lite | minimal> — matched categories: <comma-separated centrally-matched HIGH-risk categories, or "none">
```

Before writing either template, substitute the resolved 40-character
SHA into the `$WORKFLOW_SHA` placeholder on line 1.

**Full aggregator plan (`full` / `lite`).** In `lite` there is no design,
so omit the `## Design Document` link line:

```
<!-- workflow-sha: $WORKFLOW_SHA -->
# <Feature Name>

## Design Document
[design.md](design.md)
<!-- omit the two lines above in `lite` — no design.md exists -->

## High-level plan

**Change tier:** <full | lite> — matched categories: <comma-separated, or "none">

### Goals
<what this feature achieves and why>

### Constraints
<technical, performance, compatibility, or process constraints>

### Architecture Notes

#### Component Map
<Mermaid diagram + annotated bullet list>

#### D1: <Decision title>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this option won — trade-offs, constraints>
- **Risks/Caveats**: <known downsides or things to watch>
- **Implemented in**: Track X (step references added during execution)
<!-- The full live Decision Record is inline in the owning track's
     `## Decision Log` (D7). In `full`, the design.md seed keeps a copy as
     historical provenance; the plan's Architecture Notes carry the
     strategic DR view. -->

#### Invariants
<if applicable>

#### Integration Points
<if applicable>

#### Non-Goals
<if applicable>

## Checklist
- [ ] Track 1: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-1.md>
  > **Scope:** ~N files covering X, Y, Z

- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-2.md>
  > **Scope:** ~N files covering A, B
  > **Depends on:** Track 1

## Plan Review
- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of `/execute-tracks`

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md` in `full`; `adr.md` in `lite`)
```

**Shape-complete `minimal` stub (D1).** The `minimal` tier writes a stub
plan, not the full aggregator. The stub is the spec the resume state
machine reads, so it is **minimal but shape-complete** (S1,
`conventions.md` `§1.2` *Per-tier artifact set*): the
`workflow-startup-precheck.sh` script reads each section's first top-level
checkbox to derive state, so a stub with bare headings (no checkbox) would
strand resume in State 0. The stub carries exactly what the machinery reads
— a `## Plan Review` section with its decision checkbox, a glyph-valid
`## Checklist` with one track entry, a `## Final Artifacts` section with
its decision checkbox — plus the D18 tier line. There is no `## Design
Document` link (no design in `minimal`) and no Architecture Notes (the one
track file is canonical for the whole change). The per-track completion
episode is the one content block the stub later accumulates (written into
the `## Checklist` entry at Phase C; D1):

```
<!-- workflow-sha: $WORKFLOW_SHA -->
# <Feature Name>

## High-level plan

**Change tier:** minimal — matched categories: <comma-separated, or "none">

## Checklist
- [ ] Track 1: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-1.md>
  > **Scope:** ~N files covering X, Y, Z

## Plan Review
- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of `/execute-tracks`

## Final Artifacts
- [ ] Phase 4: Final artifacts (PR-description verdict summary; no `docs/adr/` entry — Gate 2 is the durable-ADR boundary)
```

Each track file (`plan/track-N.md`) is created with the four
plan-at-start homes (`## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`,
`## Interfaces and Dependencies`) populated, the track's full inline
Decision Records seeded into `## Decision Log` (the track-canonical live
carrier, D7 — see item 4 above), and the track-level
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
and library/function signatures relevant to this track.>
````

The `## Base commit` section is added by Phase B at session start
and is omitted from the Phase 1 skeleton. Full lifecycle for every
section above is tabulated in `conventions-execution.md` `§2.1`.

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
   Step 4 part 2 gate loop does.
2. **One mutation.** After the gate clears, the cleared `[decision]` items and
   the `[clarification]` items apply to the artifact in **one** mutation
   (through `edit-design` for `design.md`, through the Step-4b plan/track
   authoring for the plan and tracks). A decision-shaped finding surfaced
   **inside** the mutation exits to the log before any fix attempt; it is
   never auto-fixed in place, so the gate always sees it first.
3. **One cold-read, with loop-back.** One cold-read (the Step-4a or Step-4b
   cold-read, whichever the presented artifact uses) covers all the batch's
   changes. A decision-shaped cold-read finding **re-enters the gate step**
   (step 1) and the batch cannot close — nor the artifact re-present — while a
   log entry is open. S3 holds across the whole loop: no cold-read runs while a
   log-adversarial entry is open.

A budget-exhausted mutation escalates to the user as a failure, not as a
re-presentation: the artifact does not move mid-review.

**Escape hatch.** The queue is the default, not a hard gate. The user may ask
for immediate processing of a single blocking finding; that finding runs the
single-decision route (one gate run, one mutation, one cold-read for the lone
finding) at its full per-finding cost, and ends with a one-line note of what
moved: in chat, and in the handoff's queue block when the hold spans
sessions. The processed finding is then dropped from the in-session queue so
the review-done batch does not re-process it (the handoff's
`Escape-hatch findings already processed this hold` line is the cross-session
form of the same dedup rule).

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

- **`full`, end of Step 4a (design authoring)** — `design.md` is frozen but
  no plan exists yet. Commit the design (and the research log, if not yet
  committed) with the message `Add initial design`, push, and end the
  session. The draft PR is opened here (sub-steps 4-7 below) so the frozen
  design is visible to teammates before plan derivation; the auto-resume
  into Step 4b continues the same PR. **Draft-PR-exists guard.** A resumed
  Step 4a (Step 1c routed an interrupted-and-dirty 4a back through the
  `edit-design` loop) may have already pushed and opened the draft PR
  before the interruption. If `gh pr view` shows a draft PR already exists
  for this branch, skip the PR-open sub-steps (4-7) and only commit/push the
  re-frozen `design.md`. This mirrors the End-of-4b skip below.
- **`full`, end of Step 4b (plan derivation)** — the plan and track files
  now exist alongside the already-committed design. Commit them with the
  message `Add initial implementation plan`, push (the upstream and draft PR
  already exist from Step 4a, so skip the `-u` and the PR-open sub-steps),
  and end the session. **Idempotency guard** (mirrors the Step 1c "both
  files exist" guard): if `implementation-plan.md` is already committed and
  clean, the plan was persisted on a prior attempt; skip the commit and
  proceed to push/end.
- **`lite` / `minimal`, single Phase-1 session** — there is no `design.md`
  and no session boundary: the research log, the plan (stub in `minimal`),
  and the track files were produced in one session. Commit them together
  with the message `Add initial implementation plan`, push **with `-u`**
  (this is the first push on the branch, so the upstream and draft PR are
  opened here, sub-steps 4-7), and end the session. The same draft-PR-exists
  and idempotency guards apply: skip the PR-open sub-steps if `gh pr view`
  already shows a draft PR, and skip the commit if `implementation-plan.md`
  is already committed and clean.

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
6. Compose the PR body from the plan: `## Motivation` (the plan's
   Goals + Constraints — in `minimal`, the change's aim from the research
   log's `## Initial request` — distilled into prose; apply the Ephemeral
   identifier rule from `conventions-execution.md` `§2.3` to the body
   since PR titles and descriptions are durable), `## Plan` (one
   line per track from the checklist, no internal IDs — a single line in
   `minimal`), and a `## Status` line stating *"Draft — workflow
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
