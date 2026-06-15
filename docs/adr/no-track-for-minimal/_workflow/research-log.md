# Research Log — no-track-for-minimal

## Initial request

Restructure the relationship between `implementation-plan.md` and the
per-track files so the plan is **only a mirror/summary of the tracks'
content**. Sections that today live in the plan (`### Constraints`, and
"probably other sections") should live canonically in the track files;
`implementation-plan.md` summarizes them so an agent never needs to open a
track file to get the strategic picture.

Scope the plan's content to exactly two purposes: (1) what the next agent
session needs to continue work on the next track, and (2) what it needs to
assess impact on the rest of the implementation plan. Everything else in
the plan is wiped out.

Subsumes two filed issues:
- **YTDB-1123** (Feature) — script-maintained phase-ledger for resume-state
  detection; drop the stub `implementation-plan.md` in the `minimal` tier
  (relocate the D18 tier line → ledger and the per-track completion episode
  → track file first).
- **YTDB-1125** (Bug) — the `minimal` stub has no `### Constraints` home for
  the §1.7 markers (workflow-modifying §1.7(b) / prose-rule opt-out
  §1.7(k)). Under the mirror model the marker lives canonically in the
  track file and the plan summarizes it.

Branch renamed `no-track-for-lite` → `no-track-for-minimal` at session start
(the work targets the `minimal` tier, not `lite`).

## Decision Log

### [2026-06-15T08:10:20Z] [ctx=safe] Plan as a derived mirror of tracks; tracks are canonical
Track files own all detailed content (`### Constraints`, Decision Records,
interfaces, §1.7 markers). `implementation-plan.md` stops owning any of it
and becomes a derived summary scoped to two jobs: next-track continuation
and cross-track impact assessment. Working reconciliation of the user's two
phrasings ("summarize so no agent reads tracks" vs "only what the next
session needs"): the plan is a thin summary sized to those two needs, not a
full reproduction; an agent opens a track file only when it works that track.
- **Why:** removes the dual-ownership drift the §1.6 stamp gate exists to
  catch; one canonical home per fact.
- **Alternatives rejected:** keep plan canonical for Architecture Notes
  (status quo) — leaves two writers of the same fact.

### [2026-06-15T08:10:20Z] [ctx=safe] Tier scope: minimal drops the plan; lite/full keep a thinned plan
`minimal` (one track) drops `implementation-plan.md` entirely — a one-track
plan is a mirror of itself. `lite`/`full` keep `implementation-plan.md`
thinned to the next-session + cross-track-impact summary.
- **Why:** the cross-track-impact purpose is vacuous with one track, and
  next-track continuation degenerates to "the one track".
- **Alternatives rejected:** all tiers keep a (one-track) summary plan;
  minimal-only change leaving lite/full untouched. User picked drop+thin.

### [2026-06-15T08:10:20Z] [ctx=safe] Phase-ledger in scope, ledger-authoritative for resume state
Introduce a script-maintained phase-ledger under `_workflow/`, written at
each phase boundary, read by `determine_state` instead of parsing plan
checkboxes. Single source of truth = the ledger (checkboxes become display
only). Lets `minimal` drop the plan with no resume regression (YTDB-1123).
- **Why:** dropping the minimal plan removes the artifact `determine_state`
  parses today; resume state needs a non-plan home.
- **Alternatives rejected:** keep a tiny resume artifact the script parses
  (defers the ledger; limits how far minimal shrinks). User picked ledger.

### [2026-06-15T08:10:20Z] [ctx=safe] Branch-level facts live in the ledger, uniform across tiers
Tier line + matched categories + §1.7 mode marker all live in the ledger,
one machine-read location in every tier. Per-track `### Constraints` stays
for per-track constraints only.
- **Why:** "this branch stages" and "the change is tier X" are whole-change
  properties; no single track owns them in multi-track lite/full. One fixed
  home for the implementer §1.7(c) gate, the §1.7(l) re-point, and the five
  tier-line readers. Unifies YTDB-1123 and YTDB-1125.
- **Alternatives rejected:** per-tier homes (tier line → ledger; §1.7 marker
  → plan `### Constraints` in lite/full, track `### Constraints` in minimal,
  i.e. the issues as filed) — scatters the marker across two locations by
  tier. User picked uniform ledger.

### [2026-06-15T08:10:20Z] [ctx=safe] Section dispositions for the thinned lite/full plan
- `### Goals`: dropped (read only by structural bloat check; aim lives in the
  research log `## Initial request` + PR `## Motivation`).
- `### Architecture Notes`: DRs are track-canonical (D7) so the plan stops
  carrying them; lite/full keep only a thin cross-track Component Map
  (impact view). `full` keeps `design.md` as the seed. Minimal: none.
- Completion episode: canonical in the track file; lite/full Checklist keeps
  a one-line summary + pointer; minimal writes it to the track file only.
- `## Checklist`: stays in lite/full (cross-track nav + ordering + deps);
  gone in minimal.
- **Why:** each fact gets one canonical home; the plan retains only the
  cross-track summary the next session needs.

### [2026-06-15T08:10:20Z] [ctx=safe] Ledger = append-only event log; orchestrator writes via a script subcommand
Append-only event log under `_workflow/`. Each entry: ISO timestamp,
`[ctx=...]`, phase, optional active track, optional field update. Readers
take the LATEST value of any field (handles mid-flight tier upgrade). Branch
fields (tier + matched categories, §1.7 mode) set at first entry, overridable
by later append. `determine_state` reads the tail; within-track sub-state
still comes from the track file's `## Progress`/`## Concrete Steps`. Crash
safety: atomic append + the interrupted-write reconciliation `## Progress`
already uses.
- **Writer:** the orchestrator calls a new `workflow-startup-precheck.sh`
  subcommand (e.g. `--append-ledger phase=C track=1`) at the same points it
  flips checkboxes today. Atomic append + format in one tested place.
- **Why:** aligns with YTDB-1123 ("append one entry per boundary; read the
  last"); single tested write path; phase boundaries stay explicit in prose.
- **Alternatives rejected:** current-state file rewritten each boundary
  (needs in-place atomic rewrite); script infers boundaries autonomously
  (script must reconstruct orchestrator actions). User picked event-log +
  orchestrator-invoked subcommand.

### [2026-06-15T08:59:15Z] [ctx=safe] Step 1c resume routing rewired to the ledger (not plan presence)
`create-plan` Step 1c disambiguates resume on `implementation-plan.md` /
`design.md` presence today; a plan-less `minimal` resume would hit the
"Neither file exists — fresh start" branch and re-run Step 4 (research +
tier + gate) — the resume regression the ledger exists to prevent. Fix:
Step 1c reads the LEDGER (ledger present + tier line readable) to route a
`minimal` resume to its correct state; for `lite`/`full` plan-presence stays
the signal (the plan still exists). The `plan/track-1.md` glob is the
secondary signal for the `minimal` resume target.
- **Why:** closes the fourth, unsurveyed consumer of plan presence the
  three-consumer dissolution map missed.
- **Surfaced by:** adversarial gate iter1 A1 (blocker).

### [2026-06-15T08:59:15Z] [ctx=safe] minimal→lite/full ESCALATE materializes the dropped plan/design
`inline-replanning` is a tier-line WRITER, not just a reader: the ESCALATE
tier-upgrade rewrites the tier line and a `lite`→`full` upgrade writes a new
design seed. Under the drop-the-minimal-plan decision, a `minimal`→lite/full
upgrade must MATERIALIZE the `implementation-plan.md` (and `design.md` for
`full`) the minimal tier never had. So the upgrade carrier: writes the
upgraded tier as a ledger event AND creates the now-required plan/design.
- **Why:** the destination tier needs artifacts the source minimal tier
  lacks; the "five tier-line readers → ledger" routing under-specified the
  writer/materialization side.
- **Surfaced by:** adversarial gate iter1 A2 (should-fix).

### [2026-06-15T08:59:15Z] [ctx=safe] This branch is workflow-modifying (§1.7(b)); it stages — not §1.7(k)-eligible
no-track-for-minimal moves the resume-state field (plan checkboxes → ledger)
and adds a track-file section (`## Invariants & Constraints`); each
independently fails §1.7(k) criterion 1, so the branch is staging-bound
under §1.7(b). All `.claude/**` edits (precheck.sh, create-plan SKILL,
conventions.md, planning.md, the five tier-line-reader prompts,
track-code-review, mid-phase-handoff, inline-replanning) stage under
`_workflow/staged-workflow/`; the derived plan's `### Constraints` carries
the §1.7(b) marker; implementer/reviewer steps use staged-read precedence.
This branch runs under the CURRENT (develop) workflow while shipping the new
one, so its own plan/track artifacts follow today's format (full aggregator
plan, §1.7 marker in plan `### Constraints`).
- **Why:** the §1.7 mode is determined by the criteria, not free; stating it
  stops the design deriving under a live-edit assumption. Consistent with the
  MEMORY hidden-research-log finding (SKILL.md-touching prose is staging-bound).
- **Surfaced by:** adversarial gate iter1 A3 (should-fix).

### [2026-06-15T08:10:20Z] [ctx=safe] Phase-2 audit trail → a new dedicated plan-review document
The consistency/structural audit summary that today overwrites the plan's
`## Plan Review` section moves to a new `_workflow/` document (working name
`plan-review.md`). Split: review *state* (passed/pending) → ledger (the
resume hot path); review *fact + audit summary* → the new doc (cold record,
rarely read by agents during development — mostly states the fact). The
plan's `## Plan Review` section is removed. Uniform across tiers: minimal
gets the doc too even though it has no plan. `/review-plan` re-runs append
their verdict there. Phase-4 folds the verdict into `adr.md` / the
minimal PR-description summary as today; the doc dies in Phase-4 cleanup.
- **Why:** keeps audit content out of the thinned plan; gives minimal a
  review-fact home without resurrecting a plan; separates hot-path state
  (ledger) from cold record (doc).
- **Open sub-details:** exact filename; single file vs per-iteration
  (`reviews/plan-review-iter<N>.md` like the §2.5 review-file convention);
  whether it carries a §1.6 stamp (likely follows the review-file rule, not
  the plan rule).
- **A5 (gate iter1, suggestion) — considered, held pending user confirm:**
  the reviewer proposed folding the audit into a ledger `review=passed` event
  (zero new artifact). Held because the Phase-2 audit summary is multi-line
  review prose (consistency + structural findings, auto-fixes, escalations),
  not a one-liner; embedding it in the append-only ledger would bloat the
  tail `determine_state` greps. **User confirmed (gate iter1): keep the
  separate doc.**

### [2026-06-15T08:10:20Z] [ctx=safe] Mid-phase-handoff: file unchanged; the two plan-anchored secondary markers → ledger
Two distinct senses of "handoff": (1) the handoff FILE `_workflow/handoff-*.md`
— unchanged, never lived in the plan, stays the authoritative pause signal
via the `ls handoff-*.md` scan; (2) the defense-in-depth secondary `**PAUSED**`
marker written into the natural progress file (mid-phase-handoff §Secondary
marker). Only two rows of that table are affected: Phase-2/State-0 (beneath
`## Plan Review`) and Phase-4 (beneath `## Final Artifacts`) — both sections
removed. Route those two to the ledger as a `paused` event line. A/B/C stays
in the track `## Progress`; Phase 0/1 and ad-hoc stay "none".
- **Why:** uniform across tiers (minimal has no plan); strictly stronger —
  the ledger is machine-read on resume, so a `paused` event is read by
  `determine_state`, not just left as a human cue.
- **Ripple:** §Secondary marker's recovery grep (`grep -rn '^\*\*PAUSED '`)
  must also cover the ledger, or the ledger's paused event keeps the
  greppable `**PAUSED ` prefix.

### [2026-06-15T08:10:20Z] [ctx=safe] Completeness audit of plan-exclusive sections — every section homed
Walked every section the plan carries today against a new home. Closeouts:
- Per-track Constraints AND Architecture-Notes Invariants → a single combined
  NEW track section `## Invariants & Constraints` (15th section). Testable
  technical/performance/compatibility constraints and invariants are the same
  concept ("X must remain true, backed by a test"), so they share one home
  rather than two. Process-only constraints (non-testable — "use the IDE
  refactor engine", "no merge commits") → `## Context and Orientation`, or the
  Decision Log when they are a real decision. In `full`, cross-cutting
  invariants still seed from design.md.
  - **Why:** collapses two near-identical concepts into one canonical home; a
    dedicated heading keeps "what binds this track" discoverable for reviewers.
  - **Alternatives rejected:** separate `## Constraints` section + Invariants
    in `## Validation and Acceptance` (scatters two like concepts); fold both
    into `## Validation and Acceptance` (mixes binding constraints with
    acceptance criteria, less discoverable).
- Integration Points → track `## Interfaces and Dependencies` (entry points,
  SPIs, callbacks already belong there).
- Non-Goals → research log + PR `## Motivation` (+ `full` design.md); per-track
  out-of-scope already lives in track `## Interfaces and Dependencies`.
- Feature name in minimal → track-1 H1 + PR title (no plan).
Result: no plan-exclusive section is left homeless.

## Surprises & Discoveries

### [2026-06-15T08:10:20Z] [ctx=safe] The plan's three "machinery" justifications all dissolve
`planning.md:170` and `conventions.md:215` justify keeping the plan in every
tier because "the resume state machine, the drift gate, and Phase-2 routing
keep working unchanged". Consumer map shows all three dissolve:
- Resume state machine (`determine_state`, precheck.sh ~1439) reads only
  three checkbox tokens + one track number → replaced by the ledger.
- Drift gate (`detect_drift`, ~488) folds the stamp of ANY `_workflow/**`
  artifact; the always-present stamped `track-1.md` satisfies the fold alone
  (`design.md` is already optional/absent in minimal).
- Phase-2 routing reads the D18 tier line, not the `## Plan Review`
  checkbox; structural pass is dropped entirely in minimal, consistency runs
  only track-vs-code + tier-line-presence.
So nothing in the machinery independently requires the minimal stub once the
ledger owns phase state and the tier line.

### [2026-06-15T08:10:20Z] [ctx=safe] §1.7 markers and the tier line are BRANCH-level facts, not per-track
YTDB-1125 proposes the §1.7 marker → track `### Constraints`. But "this
branch stages / opts out" and "the change is tier X" are properties of the
whole change, not one track. In multi-track lite/full no single track owns
them. Consumers that read them — implementer §1.7(c) gate, Phase-3A §1.7(l)
criteria-switch (technical/risk/adversarial-review.md), the five tier-line
readers — want one fixed location. The ledger is the natural home: present
in every tier, machine-read, already the branch-level state file. This
unifies YTDB-1123 (tier line → ledger) and YTDB-1125 (marker home) under one
model rather than scattering homes per tier.

## Open Questions

Strategic design settled; the following are design-phase (Phase-1) detail,
not blocking the tier classification or the design transition:

- [2026-06-15T08:10:20Z] [ctx=safe] Exact ledger event vocabulary and field
  grammar the `--append-ledger` subcommand writes and `determine_state`
  greps; atomic-append mechanism (temp-file + rename vs `>>` + reconciliation
  read). Strawman agreed (event log, last-occurrence-wins).
- [2026-06-15T08:10:20Z] [ctx=safe] plan-review doc: exact filename; single
  file vs per-iteration; stamped or not (likely follows the review-file rule).
- [2026-06-15T08:59:15Z] [ctx=safe] §1.6(f) stamp status: ledger SETTLED —
  unstamped, follows the `research-log.md` precedent, not added to the §1.6(h)
  walk (A4). Only the plan-review-doc stamp status stays open, and A5 may
  dissolve it.
- [2026-06-15T08:59:15Z] [ctx=safe] Full consumer-update ripple (becomes the
  track decomposition): precheck.sh (`determine_state` reads ledger + new
  `--append-ledger` subcommand + tests), create-plan SKILL (drop minimal
  stub, thin lite/full templates, add track `## Invariants & Constraints`,
  seed ledger, **rewire Step 1c resume routing → ledger** [A1]), §1.7(c)/
  §1.7(l) marker reads → ledger, the five tier-line readers → ledger,
  **inline-replanning minimal→lite/full ESCALATE materializes plan/design +
  writes tier event** [A2], track-code-review episode → track file,
  mid-phase-handoff secondary markers → ledger, conventions.md (§1.2 per-tier
  set, glossary, §1.6(f)/(h), §1.7(b)/(k)), planning.md.

## Adversarial gate record

### Adversarial review of this log (2026-06-15T08:59:15Z) — NEEDS REVISION: 1 blocker, 2 should-fix, 2 suggestions
See `_workflow/reviews/research-log-adversarial-iter1.md`. Resolved in the
Decision Log / Open Questions above: A1 (Step 1c rewire → ledger), A2
(minimal→lite/full ESCALATE materialization), A3 (branch §1.7(b) staging
mode), A4 (ledger unstamped). A5 (fold plan-review doc into a ledger event)
resolved: user kept the separate doc with the strengthened size rationale.

### Adversarial review of this log (2026-06-15T09:18:42Z) — PASS
Iter 2 (verdict-producer): A1–A4 VERIFIED, A5 REJECTED (held owner decision,
rationale sound). No new findings; CONTRACT_OK. Gate cleared — design
authoring (Step 4a) unblocked. See
`_workflow/reviews/research-log-adversarial-iter2.md`.
