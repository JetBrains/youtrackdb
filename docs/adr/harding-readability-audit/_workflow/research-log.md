# Research Log — harding-readability-audit (YTDB-1158)

## Initial request

Address YouTrack issue **YTDB-1158** — *"Harden readability-auditor slicing:
design-path fan-out is unenforced, whole-doc collapse under-catches"* — in
full. The user confirmed all three of the issue's concerns are in scope for
this branch:

- **Concern A (issue body) — slicing hardening.** The in-loop
  `readability-auditor` is documented as a range-sliced fan-out, but on the
  **design path** (`edit-design` Step 4 + `readability-auditor.md`) the
  slicing is unenforced: an orchestrator can run a single whole-doc spawn over
  a long `design.md`, spreading per-passage attention thin and under-catching
  genuine prose findings. The **track path** already has a deterministic
  partition (one spawn per `track-N.md`). Harden the design path the same way:
  a deterministic ~200-line partition on `##` / `# Part` boundaries, a
  minimum-slice floor keyed to document length (a doc over ~300–400 lines must
  produce ≥2 slices), and a verifiable spawn count. Separate the gate-A7
  "cost-lever" framing (which governs warm-up sequencing) from the slicing
  decision so "disable the warm-up" cannot be read as "skip slicing."

- **Concern B (comment 1) — stateful auditor across rounds.** The auditor is a
  stateless cold spawn, so the dual-clean loop re-samples the document rather
  than converging (evidence: finding counts 13 → 8 → 3 → 8; slice 4 returned 0
  findings one round, then 5 on byte-identical prose the next). Give the
  auditor per-loop state: a do-not-re-flag ledger of already-fixed findings
  plus orchestrator-accepted "calibrated holds," fed to later spawns.
  Constraint: must not defeat the cold-read guarantee — carry the held-set as
  an exclusion list, not as primed context that tells the reader what to
  conclude.

- **Concern C (comment 2) — file location.** All auditor and comprehension
  subagent files (params files, review files) should live in the **reviews
  directory**, not the plan directory.

This is a **workflow-modifying** branch (it edits `.claude/**`), so it routes
through the §1.7 staging discipline.

## Decision Log

<!-- Append-only. One entry per settled decision, each with **Why:** and
**Alternatives rejected:** fields. -->

### D1 (Concern A): Design-path slice partition is a prose orchestrator obligation

2026-06-25 [ctx=safe]. Port `/readability-feedback`'s partition rule into the
in-loop design path as a mandatory, deterministic **prose** rule (no helper
script): partition `design.md` into ~200-line windows aligned on `##`/`# Part`
boundaries, cap ~6 windows (windows grow past 200 only when the cap binds, i.e.
docs over ~1200 lines), spawn exactly one auditor per window. The in-loop
design path audits only `design.md` (mechanics is skip-review), so the
companion-file clause does not apply. The minimum-slice floor is stated as a
hard invariant — the partition never emits a single whole-doc slice for a doc
above ~300 lines — rather than a separate knob (a 200-line window already
forces ≥2 slices above ~250). The gate-A7 warm-up is severed from slicing: it
governs only the *sequencing* of the N>1 spawns and never reduces N to 1, so
"disable the warm-up" means "pay N cold prefixes," never "run one whole-doc
spawn."

**Verifiable count without a script (resolves gate A4).** The issue's point-3
asks for a "verifiable spawn count"; it explicitly accepts "a stated
orchestrator obligation" as an alternative to a check. So the orchestrator
computes the expected slice count deterministically from values it already
holds (total line count, the ~200-line window, the ~6 cap), spawns exactly that
many auditors, and self-checks `slices_spawned == expected_slice_count`,
surfacing a mismatch as a wiring error. This is a prose check, no new
machinery, and it pairs with the D2 agent-side guard (which catches the
degenerate collapse-to-one-slice case): together they satisfy the issue's
verifiable-count clause at the obligation level.

- **Why:** This is the rule that produced the 5-slice fan-out the issue cites
  as catching the misses, so it is proven. Pure prose matches the track path's
  existing style ("one spawn per `track-N.md`") and avoids new script +
  test machinery.
- **Alternatives rejected:** A helper script emitting the slice ranges (truly
  deterministic, free spawn-count verifiability, single shared definition for
  `/readability-feedback` and the in-loop path) — rejected for lightness; the
  user chose the prose obligation over new machinery.

### D2 (Concern A): File distribution and an agent-side whole-doc guard

2026-06-25 [ctx=safe]. Distribute the rule across files rather than a single
canonical home: `edit-design` Step 4 § auditor carries the **operative**
partition algorithm (the orchestrator reads it when spawning, so the algorithm
lives where it acts); `readability-auditor.md` turns "Range-sliced fan-out"
from description into a hard requirement **and** adds a guard, made computable
per gate A1: the orchestrator's partition step (D1) is the **primary**
enforcement (it spawns exactly the computed count, ≥2 above the floor), and the
agent guard is a **secondary** detector. Because the cold agent's S1 read-scope
bars it from learning the document's total length, the guard is only computable
if the orchestrator passes it the slicing metadata, so the auditor params file
gains two fields — `slice_count` and `total_lines` (both constant across a
round's fan-out; slicing metadata, not conclusion-priming, so S1-safe). The
agent then flags a wiring error only when `slice_count == 1 AND total_lines >
~300` (the floor) — a legitimate single-slice short doc under the floor is not
a collapse and does not fire the guard. `create-plan` Step 4b item 9
cross-references the shared principle
(deterministic partition, one spawn per slice, no whole-doc collapse; unit
differs — per-file on the track path vs per-window on the design path);
`design-document-rules.md` keeps any cold-read-mechanics slicing statement in
sync by reference; `/readability-feedback` cross-references the canonical
statement so the standalone tool and the in-loop path cannot drift on window
size or cap.

- **Why:** The orchestrator does not read the agent's `.md`, so the partition
  algorithm must live in `edit-design` Step 4 where the orchestrator acts; the
  agent guard recovers most of the issue's point-3 "detectable, not silent"
  goal that the script would have provided; cross-references prevent drift
  across the sibling files without duplicating the rule.
- **Alternatives rejected:** A single canonical home inside
  `readability-auditor.md` — rejected because the orchestrator never reads the
  agent file, so the operative algorithm cannot live there alone. Duplicating
  the full rule verbatim in each file — rejected for drift risk.

### D3 (Concern B): Cross-round settled-state lives orchestrator-side (B1)

2026-06-25 [ctx=safe]. The auditor's cross-round state lives entirely with the
orchestrator. The auditor is never handed the held-set or told which passages
are accepted; it reads its slice plus anchors **fully cold** every spawn. The
orchestrator holds the settled-state, decides which sections to re-spawn, and
filters the returned findings.

- **Why:** Resolves the issue's "tension to resolve" by construction — with
  zero state on the agent there is no conclusion-priming, so the cold-read
  guarantee (the auditor's whole value) is strictly intact.
- **Alternatives rejected:** Exclusion-list-in-spawn (B2) — pass a
  `do_not_reflag` list into each later-round spawn. Rejected: it primes the
  reader ("these passages are blessed"), the conclusion-priming the issue
  warns against, and the per-round-varying params bust the shared-prompt
  cache the fan-out relies on.

### D4 (Concern B): Section-keyed settled-state, not a passage do-not-re-flag list

2026-06-25 [ctx=safe]. The mechanism is section-keyed settled-state, a reframe
of the issue's "do-not-re-flag list" proposal that attacks the root cause
(re-rolling unchanged prose through a fresh cold spawn) rather than the symptom
(re-flags of individual passages). Rules:

- Track settled-state per **section** (`##`/`# Part` block), keyed on section
  identity + a content hash — never line ranges, because the doc grows between
  rounds and the deterministic partition (D1) can regroup sections, so any
  line-keyed memory breaks; section identity survives re-partitioning.
- A section's hashed input is its own text **plus** whichever standing anchors
  exist, because the auditor reads those too — an anchor edit re-opens
  dependent sections. On `target=design` that is `## Overview` (always present)
  plus `## Core Concepts` **when present** (Core Concepts is seeded only
  conditionally — when the doc has Parts or ≥3 new domain terms — per gate A3,
  and the auditor already resolves anchors on demand, so it tolerates an absent
  Core Concepts). On `target=tracks` it is the plan Component Map plus each
  track's `## Purpose / Big Picture`, whichever exist.
- A section is **settled** when it has no open finding the orchestrator intends
  to act on — either it returned clean, or its only findings were accepted as
  calibrated holds.
- **Settled + unchanged section** → the orchestrator drops all its findings
  (and may skip re-spawning the slice that covers only such sections — a cost
  optimization; the filter would drop the findings anyway). This kills the
  clean→dirty oscillation on byte-identical prose.
- **Changed section** → re-audit fresh; drop findings whose verbatim quote is
  an accepted hold that still appears, keep genuinely new findings; re-evaluate
  settled-state.
- A **calibrated hold** is a deliberate orchestrator decision to accept a
  dense-but-followable should-fix rather than send it to the author, recorded
  with the verbatim quote and a one-line calibration reason — not a bulk
  dismiss. The backstop against over-accepting **prose** holds to force
  convergence is the **user veto at the D15 presentation**, where the held set
  is surfaced (resolves gate A5): the de-warmed comprehension gate runs no prose
  AI-tell axis (D9/S4) and the only prose owner is the very auditor the hold
  suppresses, so the comprehension gate is **not** a prose-hold backstop. The
  comprehension gate and the iteration-budget escalation remain backstops only
  for the comprehension/structural and decision-shaped axes (a decision-shaped
  hold re-opens the S3 gate).

- **Why:** Section-keying is robust to the re-partitioning D1 introduces; the
  anchor-hash subtlety prevents a stale "settled" verdict after an anchor
  rewrite; the unified "settled" notion (clean-or-held) collapses the issue's
  two carry-forward sources (fixed findings + accepted holds) into one
  mechanical filter the orchestrator runs without re-reading the doc.
- **Alternatives rejected:** The literal passage-level do-not-re-flag list from
  the issue comment — rejected because a clean slice (round 7's 0 findings)
  leaves no quotes to carry forward, so a passage list cannot suppress the
  clean→dirty oscillation that is the dominant variance source.

### D5 (Concern B): The convergence fix covers both the design and track paths

2026-06-25 [ctx=safe]. The same stateless-cold-auditor loop runs on the track
path (`create-plan` Step 4b item 9 spawns the same `readability-auditor` agent
each round), so the convergence fix applies to both paths, not the design path
alone. The mechanism (D3/D4) is identical; only two parameters differ, mirroring
the A slicing split: the settled-state key (per `##`/`# Part` section on the
design path; per track file on the track path) and the standing-anchor set
(`## Overview` + `## Core Concepts` for `target=design`; the plan Component Map
+ each track's `## Purpose / Big Picture` for `target=tracks`). State the
convergence mechanism canonically in `edit-design` Step 6 (parameterized by the
settled-key and anchor set) and have `create-plan` Step 4b item 9
cross-reference it with the track-path parameters — symmetric with the D2 home
for A, and consistent with create-plan Step 4b already deferring to
edit-design's loop contracts. **Track-path anchor stability (gate A7):** on the
track path, `create-plan` Step 4b items 1–8 settle the plan Component Map and
the track skeletons *before* item 9's dual-clean loop runs, so the standing
anchors the settled-state hash folds in are byte-stable for the loop's
duration; the cross-reference states this so a `lite`/`minimal` reader does not
assume the Component Map is still moving while the loop iterates.

- **Why:** The track path is the same agent and the same round structure, so it
  carries the identical defect (more exposed, with N track files re-rolled per
  round); a single canonical statement referenced by both paths prevents the
  two loops from drifting on the convergence rule.
- **Alternatives rejected:** Fixing only the design path — rejected, it leaves
  the track-path loop chasing the same variance. Restating the full mechanism
  in both `edit-design` and `create-plan` — rejected for drift risk.

### D6 (Concern C): All Phase-1 authoring-loop files move to `_workflow/reviews/`

2026-06-25 [ctx=safe]. Move every per-spawn params file (author,
readability-auditor, absorption/fidelity, comprehension) and every review
output file out of `_workflow/plan/` into the plan-scoped `_workflow/reviews/`
directory. `plan/` then holds only `track-N.md` artifacts. Target is the
plan-scoped `_workflow/reviews/` (not the track-anchored `plan/track-N/reviews/`)
because the design path runs in Step 4a before any `plan/track-N/` exists and
the author + comprehension spawns operate on the whole plan, so a track-anchored
home cannot host them uniformly. Generalize `conventions-execution.md` §2.5
*Third-scope review-file home* from "the Phase-0→1 gate's files" to "Phase-1
plan-scoped review scaffolding" so the home covers the authoring loop too.
Execution-phase review files (Phase 2/3, `track-review`) keep their existing
track-anchored home — C touches only the Phase-1 authoring loop. The new
per-slice params (D1) and settled-state scaffolding (D4) inherit this home.

- **Why:** The primary value on **both** paths is decluttering `plan/` so it
  holds only `track-N.md` artifacts. The secondary "resume glob reads one
  location" benefit applies **only to the design path** (resolves gate A2): the
  `edit-design` Step 6 dual-clean loop has a mid-loop resume round-count glob
  that re-derives the round from the latest per-round params files, so moving
  those files moves what that glob reads. The `create-plan` Step 4b track-path
  loop has **no** resume round-count glob at all (a pre-existing gap, see the
  discovery below), so the move's track-path value is solely `plan/`
  de-pollution, not glob simplification. The move is forward-compatible: were a
  track-path resume glob ever added, it would read `_workflow/reviews/`
  consistently with the design path.
- **Alternatives rejected:** Moving only the literal "auditor and comprehension"
  files named in the comment — rejected because the author and absorption/
  fidelity params files also pollute `plan/` and the design-path resume glob
  reads all of them, so a partial move leaves the mess and splits the glob.
  Adding a track-path resume round-count glob as part of this change — rejected
  as out of scope; the track-path resume gap is pre-existing and orthogonal to
  the file-location concern (C), and this change does not worsen it.

Scope: `edit-design` Step 4 (params + comprehension `output_path`) and Step 6
(resume round-count glob), `create-plan` Step 4b item 9 (params), and
`conventions-execution.md` §2.5. The agent files are path-agnostic (they read
the params path the spawn prompt names), so they need no change.

### D7 (meta): Tier is `full`; §1.7 routing is full staging, not the opt-out

2026-06-25 [ctx=safe]. Resolves gate A6 (the load-bearing tier + §1.7 open
question). **Tier = `full`** (user-confirmed): Concern B is a genuine mechanism
design (section-keyed settled-state, the anchor-hash subtlety, the populate/drop
rules) that warrants a `design.md`. Centrally-matched HIGH-risk category =
`Workflow machinery` (verbatim) — the change edits a load-bearing control-flow
protocol (the dual-clean review-iteration loop). **§1.7 routing = full staging**
(`s17` = workflow-modifying), **not** the §1.7(k) prose-rule opt-out.

- **Why:** §1.7(k) criterion 2 disqualifies a plan whose edited files a running
  phase reads as **executable procedure**, explicitly naming "the
  step-implementation orchestration loop." This change's core edits are the
  dual-clean **orchestration loop** (`edit-design` Step 4/6, `create-plan`
  Step 4b) plus the resume glob — executable procedure, not judgment-layer
  prose. So the opt-out does not qualify (the marker is per-plan, and any
  executable-procedure edit fails criterion 2). Staging is also the safe
  choice: editing the loop live would let this branch's own Phase 4
  design-final authoring (and Phase 3C re-reads) run the half-modified loop —
  the exact destabilization staging prevents (the I6 invariant).
- **Consequence (accepted):** implementation edits land under
  `_workflow/staged-workflow/.claude/...` and a Phase 4 promotion commit copies
  them live. This branch's own Phase 1 authoring (Step 4a `design.md`, Step 4b
  tracks) and all later phases run the **live develop-state** (unmodified) loop,
  so the branch cannot dogfood its own fixes during its own planning — the
  standard staging trade-off (same as the understandable-design branch).
- **Alternatives rejected:** The §1.7(k) prose-rule opt-out (edit live, gain
  self-application) — rejected: it fails criterion 2 because the edited
  orchestration loops are executable procedure, and live-editing would
  destabilize the branch's own later phases.

### D8 (Concern B): Drop calibrated holds — settled = returned-clean only; the tail is the existing iteration-budget + S5

2026-06-25 [ctx=safe]. Supersedes the calibrated-hold rules in D4. Remove the
calibrated-hold mechanism and its D15-veto backstop from the design. A section is
**settled** only when it returned clean (no open finding); there is no
accept-as-held path. The settled-state filter (D3/D4) still kills the dominant
clean→dirty oscillation: a section that returned clean and is unchanged has its
re-flags dropped. The never-clean tail — a section the cold auditor never returns
clean on because the residual is irreducibly dense but acceptable prose (e.g.
floor vocabulary the audience already knows) — is bounded by `iteration_budget`
(default 3) and exits through the **existing** S5 user-is-the-gate path: the
orchestrator applies the cheap unambiguous fixes and the user accepts or pushes
back on the residual. No separate hold mechanism, no held-set surfaced at the D15
presentation, no S4-one-owner backstop argument.

- **Why:** Convergence (termination) is already guaranteed by the settled-state
  filter (kills the oscillation on clean sections) plus the `iteration_budget`
  cap and the S5 escalation that handles budget exhaustion. Calibrated holds were
  a droppable early-exit-and-audit-trail layer, not a convergence requirement,
  and their backstop (the D15 user veto, leaned on because S4 leaves the prose
  axis with no second catcher) added conceptual surface a reader gets lost in.
  Much of the tail residual the holds would record is cold-spawn variance, not
  real defects, so dignifying it with a verbatim-quote-plus-reason ritual is the
  fragility. Dropping holds matches the lightness call already made in D1 (prose
  over a helper script); S5 already owns the accept-the-residual decision the
  holds duplicated.
- **Alternatives rejected:** Keep calibrated holds (D4 as originally written) —
  rejected: it lets the loop self-terminate early on acceptable-density docs and
  records a per-finding accept trail, but at the cost of the hold concept, the
  D15-veto-as-only-backstop plumbing, and the S4 backstop argument; the existing
  iteration-budget + S5 already terminate the loop without it.

## Surprises & Discoveries

<!-- Append-only. Codebase / workflow realities surfaced during exploration. -->

- 2026-06-25 [ctx=safe] **Design-path auditor spawn genuinely carries no slice
  rule.** `edit-design/SKILL.md` Step 4 §"Spawning the per-round auditor and
  second check" (lines 676–682) says only: "is range-sliced: each slice gets
  its own spawn whose params file carries `target=design`, `target_path`, and
  the slice `range`." No slice count, no boundary rule, no floor against a
  single whole-doc slice. Confirms Concern A's claim exactly.
- 2026-06-25 [ctx=safe] **Track and design paths use different partition
  models.** The track path (`create-plan` Step 4b item 9) partitions
  per-file: one auditor spawn per `plan/track-N.md` with a **whole-file**
  `range`. The issue proposes ~200-line windows on `##`/`# Part` boundaries
  for the design path (one `design.md`, not N files). So the two paths cannot
  literally share one rule — they share the *principle* (deterministic
  partition, one spawn per slice, no whole-doc collapse) but differ on the
  partition unit (per-file vs per-window).
- 2026-06-25 [ctx=safe] **Step 6 asserts monotone convergence; the auditor has
  no cross-round memory.** `edit-design` Step 6 (lines 832–837) claims the loop
  "moves monotonically toward dual-clean — typically one or two rounds."
  Per-round state (round, budget, `flagged_passages`) lives in orchestrator
  working memory (lines 821–823); the author re-grounds only flagged passages,
  but each round's auditor is a fully cold spawn with no do-not-re-flag set, so
  it re-litigates settled dense prose. This matches the 13→8→3→8 evidence in
  comment 1.
- 2026-06-25 [ctx=safe] **The proven partition rule already exists in
  `/readability-feedback`.** Its Procedure step 2: "Split the doc into
  ~200-line ranges on `##` / `# Part` boundaries; give each companion file
  (`design-mechanics.md`) its own range. Cap at ~6 sub-agents." This is the
  rule that produced the 5-slice fan-out the issue cites as catching the
  misses. Concern A is largely porting this into the in-loop `edit-design`
  design path, which today has no equivalent. Boundary alignment matters: the
  in-loop auditor reads its slice plus the Overview/Core Concepts standing
  anchors, so aligning slices on `##`/`# Part` keeps sections whole.

- 2026-06-25 [ctx=safe] **Params files and review outputs currently live in
  `_workflow/plan/`.** `edit-design` Step 4 writes one params file per spawn
  "under `_workflow/plan/`" (line 524); the comprehension gate's
  `phase4-creation` `output_path` is also "under `_workflow/plan/`" (line 624).
  The canonical review-file home is the track-anchored
  `plan/track-N/reviews/` (`conventions-execution.md` §2.1 Review-file
  lifecycle); the Phase-0→1 gate uses the plan-scoped `_workflow/reviews/`
  (§2.5 Third-scope review-file home). Concern C relocates the
  auditor/comprehension files out of `plan/` into a reviews directory.

- 2026-06-25 [ctx=safe] **The track path runs the identical stateless
  cold-auditor loop.** `create-plan` Step 4b item 9 calls its loop "the
  track-path analog of the `edit-design phase1-creation` loop, parameterized to
  `target=tracks`," and spawns the **same** `readability-auditor` agent (one
  cold spawn per `track-N.md` each round, same dual-clean structure, same
  `iteration_budget`). So the convergence defect (Concern B) is not
  design-path-only — it hits the track path too, and is arguably more exposed
  there (N track files each re-rolled per round). The absorption half does not
  oscillate (coverage-matching is near-deterministic), so only the readability
  auditor needs the fix, on both paths.

- 2026-06-25 [ctx=safe] **The track-path Step-4b dual-clean loop has no mid-loop
  resume mechanism (pre-existing).** `edit-design` Step 6 has an explicit
  "Resume after a mid-loop context-clear" block that re-derives the round count
  from the latest per-round params files; the `create-plan` Step 4b track-path
  loop writes the same per-spawn params but has **no** equivalent resume
  round-count glob. Surfaced by gate A2. This is orthogonal to Concern C
  (file location) and out of scope for this change — C relocates the files but
  does not add a track-path resume glob; the move is forward-compatible if one
  is ever added.

- 2026-06-25 [ctx=info] **Live first-hand reproduction of Concern B's
  oscillation on this branch's own `design.md`.** The Phase-1 `phase1-creation`
  dual-clean loop (running the **live**, unfixed readability loop per D7) did
  not converge across its 3-round budget: genuine readability-finding counts
  went **11 → 10 → 12** — non-monotone, with brand-new passages flagged each
  round (e.g. "the issue" unglossed, "Phase 3C", "busts", block-paragraph
  density that round 2 did not raise) on prose of roughly stable quality.
  Absorption stayed clean every round (0 findings, 7/7 coverage). This is the
  exact stateless-cold-spawn re-roll the YTDB-915 evidence in the issue
  describes (13→8→3→8), reproduced independently here — direct validation that
  Concern B's fix is needed. At budget exhaustion (all should-fix, 0 blockers)
  the orchestrator took the S5 user-is-the-gate path: applied the cheap
  unambiguous prose fixes in a final targeted pass and accepted the residual
  **deep-workflow-term density** as calibrated holds (see below).

- 2026-06-25 [ctx=info] **Calibrated holds accepted on `design.md` (budget
  exhausted).** Held (not fixed), with reason — each term is defined in the
  doc's own `### Decisions & invariants` footers and the workflow docs, and is
  floor vocabulary for the design's actual audience (workflow maintainers), so
  an inline gloss of every one would bloat an ephemeral artifact: the
  `S3 freeze-order gate`, `log-adversarial entry` (glossed once in round 3; the
  cold spawn re-flagged it — variance), the `Phase-0→1 adversarial gate`,
  `Phase 3C`, and the `absorption half` / coverage-matching aside. These are
  surfaced here as the held set (the D4 practice: holds recorded with a reason);
  the user can veto any at the design presentation.

- 2026-06-25 [ctx=safe] **This branch cannot dogfood its own fixes (staging
  trade-off).** Per D7 the branch stages, so its own Phase 1 authoring and all
  later phases run the live develop-state (unmodified) readability loop — the
  one with the bugs this branch fixes. The fixes land in the staged mirror and
  promote at Phase 4. So this branch's own design/track authoring exhibits the
  old unenforced-slicing and oscillation behavior; that is expected and
  accepted, the same trade-off the understandable-design branch took.

## Open Questions

<!-- Append-only. Unresolved questions carried toward planning. -->

- 2026-06-25 [ctx=safe] **(A) Exact deterministic partition for the design
  path.** ~200-line windows aligned to `##`/`# Part` boundaries — but the
  precise rule (how to handle uneven sections, the minimum-slice floor keyed
  to length, ≥2 slices above ~300–400 lines), and whether the spawn count is a
  stated orchestrator obligation or a verifiable check. Keep the warm-up
  cost-lever framing separate from the slicing decision.
- 2026-06-25 [ctx=safe] **(B) Where the do-not-re-flag held-set lives.**
  Orchestrator-filtered (orchestrator drops re-flagged settled passages) vs an
  explicit exclusion list passed into each later spawn. How to represent
  "calibrated holds" the orchestrator accepted. Keep the *read* cold — carry
  state only as the settled/held set, never as primed conclusions. Whether B
  is large enough to be its own track/sub-task.
- 2026-06-25 [ctx=safe] **(C) Which reviews dir for the relocated files.**
  Design-path authoring runs in Step 4a before any `plan/track-N/` exists, so
  `_workflow/reviews/` (plan-scoped) is the natural home there; the track path
  could use `plan/track-N/reviews/` or `_workflow/reviews/`. Decide the target
  and whether it covers both params files and output files.
- 2026-06-25 [ctx=safe] **(meta) Tier and §1.7 routing.** Concern B's stateful
  mechanism likely warrants a `design.md` (→ `full` tier); these are
  judgment-layer prose-heavy workflow files, so the §1.7(k) prose-rule
  opt-out vs full staging is a live choice. To settle at Step 4.

- 2026-06-25 [ctx=safe] **RESOLVED.** All four open questions above are resolved
  into the Decision Log: (A) → D1/D2 (prose partition + verifiable-count
  obligation + agent guard); (B) → D3/D4/D5 (orchestrator-side section-keyed
  settled-state, both paths); (C) → D6 (`_workflow/reviews/`, all per-spawn
  files); (meta) → D7 (`full` tier, full §1.7 staging). None carried unresolved
  into Phase-1 authoring.

## Baseline and re-validation

<!-- Workflow-modifying branch: this section anchors the develop baseline of
the touched workflow files so rebase drift is detectable. -->

**Baseline anchor:** branch forked from `develop` at `1065c173add` (HEAD ==
fork point at research time; no branch commits yet). Re-validate the touched
files against `develop` on any rebase before the Phase 4 promotion (§1.7(f)).

**Touched-file set (firmed across A/B/C):**
- `.claude/skills/edit-design/SKILL.md` — Step 4 design-path partition rule
  (A: D1/D2); Step 4 params + `output_path` → `_workflow/reviews/` (C: D6);
  Step 6 convergence mechanism canonical home (B: D4/D5) + resume round-count
  glob → `_workflow/reviews/` (C: D6).
- `.claude/agents/readability-auditor.md` — "range-sliced" becomes a hard rule
  + whole-doc-refusal guard (A: D2); cold-read-guarantee note that settled-state
  is orchestrator-side (B: D3). Path-agnostic on file location (C: no change).
- `.claude/skills/create-plan/SKILL.md` — Step 4b item 9: cross-reference the
  shared slicing principle (A: D2) and the convergence mechanism with track-path
  params (B: D5); params → `_workflow/reviews/` (C: D6).
- `.claude/workflow/conventions-execution.md` — §2.5 generalize the third-scope
  review-file home to Phase-1 plan-scoped review scaffolding (C: D6).
- `.claude/workflow/design-document-rules.md` — keep any cold-read-mechanics
  slicing statement in sync by reference (A: D2).
- `.claude/skills/readability-feedback/SKILL.md` — cross-reference the canonical
  partition statement so the standalone tool and the in-loop path cannot drift
  (A: D2).

## Adversarial gate record

<!-- The Phase-0→1 adversarial gate appends one verdict heading per iteration here. -->

### Adversarial review of this log (2026-06-25) — NEEDS REVISION: 2 blocker, 4 should-fix, 1 suggestion
Iteration 1. Review file: `_workflow/reviews/research-log-adversarial-iter1.md`.
Blockers A1 (D2 guard uncomputable by the cold agent) and A2 (D6 resume-glob
rationale vacuous for the track path); should-fix A3 (anchor-hash assumes
Core Concepts always present), A4 (D1 drops the issue's verifiable-count
requirement), A5 (comprehension gate is not a prose-hold backstop), A6 (tier +
§1.7 routing must resolve into the Decision Log). Suggestion A7 (track-path
anchor stability). All addressed by the D1–D7 revisions below.

Gate spawned on `opus` (D14 pins `fable` for `full` tier; `fable` unavailable in
this environment — documented env degradation, not a downgrade decision).

### Adversarial review of this log (2026-06-25) — PASS
Iteration 2 (verdict-producer variant). Review file:
`_workflow/reviews/research-log-adversarial-iter2.md`. All 7 prior findings
(A1–A7) VERIFIED against the revised D1–D7; 0 blocker, 0 unaddressed should-fix,
0 new findings. Gate clears — Phase-1 artifacts may now derive.
