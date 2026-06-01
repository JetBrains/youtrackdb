# Handoff: inline replan — add Track 4 (staged-copy review delta-scoping)

**Paused:** 2026-06-01
**Phase:** C (post-Track-1-completion inline replan, deferred)
**Context level at pause:** info (35%, below the formal warning trigger; deferred by explicit user choice so the prose-heavy design revision runs with full budget rather than risking a half-reframed design.md across the 40% band)
**Branch:** ytdb-1038-review-gate-for-workflow
**HEAD:** cfd3210b89cb957596028edbc51732cd9def93b7 "Mark Track 1 (selection-side staging awareness) complete"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `implementation-plan.md` — Track 1 is `[x]` (collapsed episode); Tracks 2, 3 are `[ ]`; `## Plan Review` is `[x]`. D1–D4 in Architecture Notes.
- `plan/track-1.md` — Track 1 complete (all four Progress phases `[x]`), episodes + Outcomes recorded.
- `design.md` — frozen spec with three concern sections: Selection-side (D1), Read-side (D2/D3), Phase A criteria (D4), plus Consistency invariants and self-application. Overview frames the work as "three ways" the machinery goes stale / "closes all three" / "three pieces, one per issue".
- `design-mutations.md` — 3 mutations logged from Phase 1/2.

## Pending decision (RESOLVED — execute, do not re-ask)

The Phase C self-improvement reflection surfaced a workflow gap. The user
directed it become a **new Track 4 on this branch** rather than a separate
YouTrack `dev-workflow` issue, and chose the fix approach. All parameters are
fixed; the next session executes the inline replan, it does not re-decide.

Resolved parameters:
- **Add Track 4: Review-target delta-scoping for staged copies**, folded under
  the YTDB-1038 review-side-staging-awareness umbrella (no new YouTrack issue).
- **Fix approach (D5) = orchestrator pre-stages the delta.** In the Phase C
  diff-staging step and the high-risk Phase B step-review setup, detect a
  changed file that is a freshly-created staged copy (matches the anchored
  prefix `docs/adr/<dir>/_workflow/staged-workflow/(\.claude/…)`, is a
  new-file add in the reviewed range, and has a live counterpart), and
  additionally stage a `diff <live> <staged>` delta file; the canonical
  reviewer context block points reviewers at that delta with a "scope findings
  to this delta; the rest is verbatim-copied live content" note. The rejected
  alternative was reviewer-side self-diffing (lighter on the orchestrator, less
  deterministic across the fan-out).
- **Depends on:** Track 2. Both edit `track-code-review.md` (the §Context
  passed to all sub-agents block) and `step-implementation.md` sub-step 4;
  sequencing Track 4 after Track 2 lets it layer on the read caveat and avoids
  staged-copy conflicts. Track order becomes 2 → {3, 4} (3 and 4 both depend on
  2; independent of each other).
- **Scope:** ~2–3 steps.

## The finding (rationale, so the replan need not re-derive it)

On a workflow-modifying plan, a track's deliverable is a staged copy under
`_workflow/staged-workflow/.claude/...`. When the copy is first created inside
a reviewed commit range, the cumulative diff shows it as a whole-file add,
even though it is a copy of an already-live, already-reviewed file plus a small
edit. Phase C review (and high-risk Phase B step review) hand the reviewer that
whole-file add with no signal that only the delta-vs-live is the real target,
so reviewers spend effort on already-promoted content and risk phantom findings
or scope creep into live machinery. This Phase C session needed it
hand-injected: the orchestrator generated staged-vs-live delta-diffs and wrote
an out-of-scope guard into all five reviewer prompts. The three fixes this
branch already ships (1032 selection, 1038 read caveat, 1046 Phase A criteria)
do not cover this — 1038 routes reads of a *referenced rule* through staged
precedence; this is about scoping the *changed file* itself. So the gap
survives this branch's other fixes and recurs on every future
workflow-modifying plan whose track first-creates a staged copy in range.

Cost-benefit (recorded for the D5/track rationale): `load_cost = 1.5 para × 2
(phase-doc) = 3`; `population_in_horizon = 6` (Phase C of workflow-modifying
tracks that first-create a staged copy in range; ~3–5 such plans in the
horizon); `self_fix_cost = 3 × 6 = 18`; `ratio = 6×`.

## Resume notes

**Next action on resume (BEFORE normal state evaluation):** execute the inline
replan per `inline-replanning.md` to add Track 4, using the resolved parameters
above. This is Case 1 (New track) in `inline-replanning.md` §Updating plan and
track files. Concrete steps:

1. **design.md (via the `edit-design` skill — mutation discipline, NOT raw
   Edit).** Add the delta-scoping concern coherently. Because the design's
   Overview / Core Concepts / Class Design carry the counts "three ways" /
   "seven load-bearing ideas" / "three additions, three reach", a fourth
   concern (or a second facet of the read-side concern) ripples into those
   sections. Decide framing first: either a new top-level
   `## Review-target delta-scoping for staged copies` section parallel to the
   other three (Overview becomes "four"), or a second facet inside
   `## Read-side staging awareness` (Overview's read paragraph expands, count
   stays "three" — better matches "part of YTDB-1038"). Author D5's design
   prose (TL;DR + mechanism: orchestrator pre-stages delta-vs-live; Edge cases;
   References → D5, the relevant invariant, YTDB-1038). Touch the Overview and,
   as the structural preview flags them, Core Concepts / Class Design counts.
   Run each as its own `edit-design` mutation (direct `content-edit` /
   `section-add`; no `design-mechanics.md` companion exists, so the working/sync
   loop does not apply).
2. **implementation-plan.md.** Add Decision Record **D5** to Architecture
   Notes (format per `inline-replanning.md`, `**Full design**` → the design
   section from step 1). Add a thin Track 4 checklist entry (title + intro
   paragraph + `**Scope:** ~2–3 steps` + `**Depends on:** Track 2`). Reframe
   the Goals "closes all three" / "three pieces" wording to include the
   delta-scoping fix under YTDB-1038. Update Component Map / Integration Points
   / Invariants if the framing warrants (a delta-scoping invariant is optional;
   judge during authoring). **Reset `## Plan Review` to**
   `- [ ] Plan review (consistency + structural) — autonomous; runs as the first
   phase of /execute-tracks` so the next session re-runs State 0 against the
   revised plan.
3. **plan/track-4.md.** Create the full 14-section ExecPlan track file (see
   `conventions-execution.md §2.1` *Track file content*). `## Base commit` =
   HEAD at the replan commit. `## Progress` pre-seeds the four phase
   checkpoints. The in-scope files are `track-code-review.md` (Phase C Startup
   diff-staging step 7 + §Context passed to all sub-agents) and
   `step-implementation.md` (sub-step 4 high-risk step-review setup); all edits
   stage under `_workflow/staged-workflow/.claude/...` per §1.7. Consider
   delegating the authoring to a sub-agent to conserve context.
4. **Structural-review preview** (advisory, not the gate) per
   `inline-replanning.md` step 4 — spawn the structural-review sub-agent on the
   revised plan. Iterate ≤3× on structural blockers.
5. **Commit + push** the replan as one Workflow update commit
   (`Inline replan after Track 1 — add Track 4`), staging
   `implementation-plan.md`, `plan/track-4.md`, `design.md`, and
   `design-mutations.md` (the `edit-design` log). Then end the session; the
   following `/execute-tracks` enters State 0 and re-reviews the revised plan.

- **Do NOT redo:** Track 1 (complete, `[x]`); the self-improvement reflection
  (already run — this finding is its output, now being actioned as Track 4, so
  no new YouTrack issue is filed for it).
- **Self-application note (§1.7(h)):** Track 4 edits live review machinery that
  this branch stages, so its own Phase A/C reviews still need the orchestrator
  to hand-inject staging guidance during execution — same carve-out as Tracks
  1–3.
