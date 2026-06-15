<!--MANIFEST
dimension: workflow-instruction-completeness
iteration: 1
target: "Track 1 — the phase ledger, the new artifact model, and the authoring surface (6c2e0b5f68..HEAD)"
evidence_base: { certs: 3 }
cert_index: [C1, C2, C3]
flags: []
findings:
  - { id: WI1, sev: should-fix, anchor: "wi1-lite-full-active-track-re-derivation-diverges-from-the-script-ledger-track", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md:278-330", cert: C1, basis: judgment }
  - { id: WI2, sev: should-fix, anchor: "wi2-minimal-track-file-written-before-ledger-seed-strands-a-durable-track-file-as-fresh-start", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md:224-233", cert: C2, basis: judgment }
  - { id: WI3, sev: suggestion, anchor: "wi3-phase-a-resume-bullet-framed-checklist-only-no-minimal-complement", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md:305-314", cert: C3, basis: judgment }
-->

## Findings

### WI1 [should-fix] lite/full active-track re-derivation diverges from the script's ledger track

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md` (line 278-330)
- Axis: phase output → next-phase input
- Cost: the script computes the State-C sub-state from the ledger's `track` value, but the agent re-derives the displayed/operated active track from the plan Checklist — two sources with no tie-breaker, so a divergence routes the wrong track's sub-state

The script's `determine_state_from_ledger` (`workflow-startup-precheck.sh`
lines 1775-1792) resolves `phase=C` by reading the ledger `track` value
(defaulting to `1`), locating `plan/track-${track}.md`, and computing
`C_SUBSTATE` from *that* file's `## Progress`. The emitted `state.substate` is
therefore bound to the **ledger's** active track.

Track 1's edited `workflow.md` step 5 then tells the agent to re-derive the
active track by a *different* source for `lite`/`full`:

> **`lite`/`full`** (a plan exists): walk the plan's `## Checklist` for the
> first `[ ]` track, **the same walk the script used.**

The "the same walk the script used" claim is no longer true for any branch that
has a ledger — which, post-merge, is every new `lite`/`full` branch, since
`create-plan` now seeds the ledger at Phase 1 in every tier (SKILL.md
"Seed the phase ledger (every tier; D6/D10)"). The script took the ledger path
(ledger present ⇒ `determine_state_from_ledger` returns, the legacy checklist
walk in `determine_state` never runs). So the substate came from
`ledger.track`, while the agent's active track comes from the
first-`[ ]`-Checklist walk. In the normal flow these agree (the orchestrator
appends `track=N` at the same boundary it would flip track N-1 to `[x]`), but
the spec names no authority when they disagree — e.g. a missed Checklist flip,
or a ledger `track=2` append landing before the track-1 Checklist box is
flipped. The agent would then present/operate track 1 (first `[ ]`) while the
`steps-partial` / `review-done-track-open` sub-state it routes on was computed
from track 2's `## Progress`.

This is Track-1-owned: both the script's ledger-track read and the
`workflow.md` step-5 re-derivation prose are edited in this diff. (The active
track *number* is genuinely not in `state` — there is no `state.track` field —
so the agent must re-derive it; the gap is that the re-derivation source is
unaligned with the substate source and unranked.)

Suggestion: make the ledger the authority for `lite`/`full` too — instruct the
agent to read the active track from the ledger `track` tail (the same value the
script used), and treat the Checklist first-`[ ]` walk as a cross-check that, on
mismatch, surfaces an inconsistency to the user rather than silently preferring
one. Either drop the "the same walk the script used" claim or re-point the
`lite`/`full` re-derivation at the ledger tail so the displayed track and the
routed sub-state always name the same track.

### WI2 [should-fix] minimal track file written before ledger seed strands a durable track file as a fresh start

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (line 224-233)
- Axis: cleanup and idempotency
- Cost: a `minimal` Phase-1 session interrupted between the track-file write and the ledger seed routes to the fresh-start branch on resume and re-derives + re-Writes `plan/track-1.md`, clobbering partially-authored Phase-1 content

Track 1 fixes the Phase-1 write order so the ledger seed lands **after** the
track file:

> **Seed the phase ledger (every tier; D6/D10).** After the track files (and,
> in `lite`/`full`, the plan) are written and before the Step 5 commit, seed
> the phase ledger … (SKILL.md line 986)

and the seed itself sits after the Step-4b cold-read sub-agent spawn (SKILL.md
step 9, line 686). That leaves a real window in `minimal`: `plan/track-1.md` is
on disk, the ledger is not yet written, and a context-full `/clear` or crash
(plausible across a sub-agent spawn) ends the session there.

On resume, Step 1c's `minimal` branch does not match — it requires "ledger
present with `tier=minimal`" (SKILL.md line 215), and no ledger exists. The
only branch left is the fresh-start branch:

> **Neither `implementation-plan.md` nor `design.md` exists, and the ledger is
> absent (or present with no `tier`/`minimal` track file yet)** — fresh start.
> … This also covers the narrow `/clear` window where Step 4's gate cleared but
> no artifact was written yet: with no plan, no `design.md`, and no seeded
> ledger tier on disk **there is no resume signal, so the resume correctly reads
> as a fresh start** … (SKILL.md lines 224-233)

The rationale "no artifact was written yet … nothing durable was produced" is
the `lite`/`full` reasoning, where the plan is the durable Phase-1 artifact.
But in `minimal` the durable Phase-1 artifact is `plan/track-1.md`, and it *was*
written — the seed simply had not run. Routing this to a fresh start re-runs
research, tier classification, and the gate, then re-authors `plan/track-1.md`
via the Step-4b Write, overwriting any work already on disk. The Track-1
deliverable that introduces the no-plan `minimal` model (D2/D10) and reorders
the seed (D6) owns this case; the fresh-start prose enumerates a degenerate
input it now mis-classifies.

Suggestion: guard the fresh-start branch on `plan/track-1.md` absence in
`minimal`, or add an explicit Step 1c case: "ledger absent **but
`plan/track-1.md` present**, no plan, no design — a `minimal` Phase-1 session
interrupted before the ledger seed: resume by seeding the ledger (`phase=0`,
`tier=minimal`) and continuing, not by re-authoring the track file." Cheaper
alternative: state the seed must run **before** the track-file write (seed
first, then author), so the ledger is the earliest durable Phase-1 artifact and
its presence/absence is an honest fresh-start signal.

### WI3 [suggestion] Phase-A resume bullet framed checklist-only, no minimal complement

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md` (line 305-314)
- Axis: conditional branch coverage
- Cost: the `phase == "A"` resume description reads as checklist-only, with no statement of what State A means for plan-less `minimal`, where there is no Checklist

Step 5's preamble now gates the active-track re-derivation by tier (lines
282-290), correctly noting `minimal` has no Checklist and the active track is
`track-1` by construction. But the `phase == "A"` bullet body itself was not
re-framed:

> **`phase == "A"`** — the first `[ ]` track has no track file yet
> (pre-Phase-A; rare, since `/create-plan` writes every track file at Phase 1
> …). (line 305-308)

"the first `[ ]` track" is Checklist vocabulary. In `minimal` there is no
Checklist; State A arises from the script's ledger path when `phase=C`
(or `phase=A`) is recorded but `plan/track-1.md` is missing
(`determine_state_from_ledger`, lines 1786-1790). A reader following the
`minimal` branch reaches a bullet whose body assumes the artifact the branch
just said does not exist. The preamble's tier gate keeps this from stranding
the agent, so it is a polish gap, not a dead end.

Suggestion: add one clause to the `phase == "A"` bullet — "(in `minimal`, the
ledger recorded a phase before `plan/track-1.md` was written; the active track
is `track-1` per the preamble)" — so the State A description holds in both the
Checklist and the no-Checklist tier.

## Evidence base

#### C1 — workflow.md step 5 `lite`/`full` re-derivation vs script ledger-track source (WI1)

Refuted the "harmless" reading by tracing both sources. Script: `phase=C` ⇒
`ledger_tail_value "track"` ⇒ `determine_c_substate "$plan_dir/_workflow/plan/track-${track}.md"`
(`workflow-startup-precheck.sh` lines 1779-1785), so `state.substate` is bound
to the ledger track. Confirmed the ledger path pre-empts the legacy checklist
walk: `determine_state` returns early when `determine_state_from_ledger` succeeds
(lines 1810-1812), and it succeeds whenever a ledger file exists (line 1754).
`create-plan` seeds the ledger in every tier at Phase 1 (SKILL.md line 986), so
every post-merge `lite`/`full` branch takes the ledger path. Against that, the
edited `workflow.md` step 5 tells the agent to re-derive via the Checklist
"the same walk the script used" (line 285) — a source the script no longer uses
on the ledger path. No tie-breaker prose exists in step 5 for a ledger-track ≠
first-`[ ]`-Checklist-track mismatch. Both edited surfaces are in this diff ⇒
Track-1-owned. Finding survives.

#### C2 — minimal Phase-1 interrupt window between track-file write and ledger seed (WI2)

Confirmed the write order from the edited SKILL: track files written (step 8),
Step-4b cold-read sub-agent (step 9, line 686), then "Seed the phase ledger …
After the track files … are written" (line 986) — the seed is strictly after
the track-file write and after a sub-agent spawn. Confirmed Step 1c's `minimal`
branch requires a present ledger with `tier=minimal` (line 215), so a
no-ledger-yet `minimal` branch with `plan/track-1.md` on disk falls through to
the fresh-start branch (lines 224-233), whose rationale ("nothing durable was
produced") holds for `lite`/`full` (plan = durable artifact) but not for
`minimal` (track file = durable artifact, already written). Re-running Step 4b
re-Writes `plan/track-1.md`. The model change (D2/D10) and the seed reorder (D6)
are Track-1 deliverables ⇒ Track-1-owned. Finding survives.

#### C3 — Phase-A bullet checklist-only framing (WI3)

Confirmed the `phase == "A"` bullet body (lines 305-308) uses "the first `[ ]`
track" framing while the step-5 preamble (lines 282-290) already establishes
`minimal` has no Checklist. The preamble's tier gate prevents stranding, so this
is a documentation-completeness polish, not a dead-end ⇒ suggestion. Both lines
are in the Track-1 `workflow.md` edit. Finding survives at suggestion severity.
