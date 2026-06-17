<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

(No new findings. The revision introduced no new blocker, should-fix, or
suggestion. The six new `### 2026-06-17T07:37Z` Decision Log entries and the two
RESOLVED annotations on the Open Questions are internally consistent and
coherent with the live `.claude/**` machinery; none breaks a live reference or
contradicts another decision in the log.)

## Evidence base

#### A1 — auditor owns § Prose AI-tell on every prose-checked surface; design-review.md drops it — VERIFIED
- **Prior finding**: the OQ "Fate of design-review.md's prose axes" was still
  open; the central wiring change (which surfaces lose § Prose AI-tell to the
  auditor) was unspecified, risking dilution returning on the unclaimed
  `target=tracks` surface.
- **Revision**: new Decision Log entry "The auditor owns the § Prose AI-tell axis
  on every prose-checked surface; design-review.md drops it (resolves the 13:55Z
  prose-axis open question; gate A1)" (research-log.md:85-114). The OQ at
  research-log.md:825 is now annotated `RESOLVED 2026-06-17T07:37Z, see Decision
  Log`.
- **Verification against live machinery**:
  - The four prose-checked surfaces are enumerated: `phase1-creation`,
    `phase4-creation`, `create-plan` Step 4b `target=tracks` (the explicit three),
    plus the `design-sync`/non-creation "4th surface" handled by the
    one-owner-per-surface principle with the `edit-design` wiring deferred to
    Step 4a under the same invariant. This matches the live applies-to set:
    `prompts/design-review.md` § Prose AI-tell additions (lines 189-195) names
    `phase1-creation`, `phase4-creation`, `design-sync`, AND `target=tracks` —
    exactly the four the entry now assigns to the auditor.
  - The entry's split is coherent with the live file: the auditor takes the
    entire § Prose AI-tell axis plus the prose-anchored § Human-reader items
    (the ones 15:30Z assigned it); `design-review.md` keeps the 7 comprehension
    questions, the structural-findings set, and the whole-doc § Human-reader
    items (navigability + the structural "does the Overview name a reader" half
    of audience-fit). Live `design-review.md` § Human-reader additions apply only
    to the three design kinds (lines 169-184), so dropping the prose-anchored
    half there and keeping the structural/whole-doc half is consistent; § Prose
    AI-tell, which uniquely also ran on `target=tracks` (lines 189-195, 247-250),
    is fully transferred — closing the track-path dilution wrinkle the prior
    finding flagged.
  - The invariant ("no surface ever runs § Prose AI-tell on both the auditor and
    the warm reviewer") is stated and holds across all four surfaces including
    the deferred `design-sync` wiring.
- **Verdict**: VERIFIED — OQ resolved into the Decision Log, four-surface
  enumeration explicit, invariant coherent with the live surfaces.

#### A2 — dual-clean loop convergence argument; cost waived to planning — VERIFIED
- **Prior finding**: OQ loop-topology sub-part (b) (cost/convergence under the
  cap) was open; the dual-clean loop is a tighter convergence claim than the
  live single-axis loop and the log offered only a hand-wave.
- **Revision**: new entry "Dual-clean loop convergence argument; detailed cost
  bound waived to planning (resolves the 13:55Z loop-topology open question
  sub-part b; gate A2)" (research-log.md:116-143); OQ at research-log.md:837 now
  annotated `sub-part (b) cost/convergence RESOLVED`.
- **Verification**: the convergence argument is specific to the **two-check**
  loop, not the single-axis one: it argues the readability auditor and the
  absorption agent re-open the loop for **disjoint** reasons and are not
  adversarially coupled (a density fix adds code-accurate prose without dropping
  a decision; a dropped-decision fix is new prose the next round polishes
  normally), so neither check's fix re-triggers the other in a cycle and the loop
  moves monotonically to dual-clean (1–2 rounds typical). Cap-exhaustion is bound
  to today's live `edit-design` Step 6 behavior (freeze with open findings,
  escalate to the user as the gate). The exact worst-case token figure is
  **explicitly waived to planning/decomposition** as out of the gate's scope. The
  waiver is explicit and the argument addresses the dual-clean shape.
- **Verdict**: VERIFIED — convergence argument is dual-clean-specific; cost waiver
  explicit.

#### A3 — per-round absorption justified by range-slicing, not refuted by "auditor never deletes" — VERIFIED
- **Prior finding**: 14:32Z rejected the cheaper single-absorption check on a
  thrash argument that its own 14:18Z premise ("the auditor never requests
  deletions — it flags prose, not content") appeared to defuse.
- **Revision**: new entry "Per-round absorption is justified by the auditor's
  range-slicing, not refuted by 'auditor never deletes' (strengthens 14:32Z; gate
  A3)" (research-log.md:145-158).
- **Verification**: the entry names the actual load-bearing failure — the
  readability auditor is **range-sliced** (cites 15:30Z / 16:00Z: the
  `readability-feedback` fan-out reads ~200-line slices with no whole-doc view),
  so a later round's restructure can **move** a decision's prose across slice
  boundaries (into a gap, or reworded out of recognizability) — a drop with no
  agent "deleting" anything. A single before-loop absorption check cannot
  re-catch this post-restructure cross-slice drop; the per-round whole-doc
  absorption check can. This is consistent with the 15:30Z range-sliced-auditor
  fact (research-log.md:692-717) and the 16:00Z ~200-line fan-out (research-log.md
  references); it confronts the 14:18Z "never deletes" premise directly by
  showing the drop is not a deletion. The cross-slice-drop scenario is named as
  the reason per-round beats single-check, exactly what the prior finding's
  Proposed fix asked for.
- **Verdict**: VERIFIED — cross-slice-drop named, consistent with the
  range-slicing facts.

#### A4 — de-warm extends S2 to name the absorption agent; S2 prose edit bound as a deliverable — VERIFIED
- **Prior finding**: the de-warm's "no third S2 read site" claim rested on a
  reinterpretation of S2; live S2 names the author/consistency-review sites, not
  a separate warm absorption spawn; the implied S2 edit was under-stated.
- **Revision**: new entry "The de-warm extends S2 to name the absorption agent as
  a sanctioned reader; the conventions.md S2 edit is a stated deliverable
  (strengthens 14:05Z / 14:12Z; gate A4)" (research-log.md:160-175).
- **Verification against live `research.md` S2**: live S2 (research.md:116-119)
  reads verbatim "the log is read for decision *content* in exactly two places:
  at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2
  consistency review (as a cross-check)." The new entry quotes this accurately,
  states explicitly that the branch **extends** S2's "Step 4a/4b authoring read"
  to include the distinct warm absorption agent as a sanctioned reader, and binds
  updating S2's prose in `conventions.md` as a **stated deliverable** (already on
  the 14:50Z files-of-change list "conventions.md S2/S3 prose", now bound to this
  decision rather than left implicit). The rationale ("prevents a latent S2
  violation if a later reader or the Phase-2 consistency review reads S2
  literally") matches the prior finding's concern. The edit is now an explicit
  deliverable, not an implicit reinterpretation.
- **Verdict**: VERIFIED — de-warm extends S2 explicitly; the S2 prose edit is
  bound as a deliverable, consistent with live S2 text.

#### A5 — workflow-prose Phase-3 dogfood target added — VERIFIED
- **Prior finding**: the 17:40Z dogfood targets (`readability-feedback` on this
  design; `transactional-schema` design.md) never exercise the loop on
  **workflow prose** — the branch's actual domain.
- **Revision**: new entry "Add a workflow-prose Phase-3 dogfood target
  (strengthens 17:40Z; gate A5)" (research-log.md:177-190).
- **Verification**: the entry adds a Phase-3 validation point — run the
  implemented routine against a known-dense **workflow-prose** artifact: a prior
  workflow branch's `design-final.md` (names `plan-slimization` or
  `no-track-for-minimal`) **or** a `conventions.md §1.7` section — alongside the
  `transactional-schema` storage-domain cross-check, and validates "on workflow
  prose **before** promotion, not only post-promotion." The named targets are
  genuinely workflow-prose and exist on develop:
  `docs/adr/no-track-for-minimal/design-final.md` and
  `docs/adr/plan-slimization/...` are present (git ls-tree develop), and
  `conventions.md` carries a real `§1.7` section (20 §1.7 hits). The added target
  is genuine workflow prose, addressing the prior finding's domain mismatch.
- **Verdict**: VERIFIED — workflow-prose Phase-3 dogfood target added; targets
  are genuine workflow prose.

#### A6 — boundary collapse bound as a staged auto-resume-contract change with a hard by-reference requirement — VERIFIED
- **Prior finding**: collapsing the 4a/4b boundary understated that it changes
  the create-plan auto-resume contract (a staged, execution-procedure / schema
  change, not §1.7(k)-opt-out-eligible) and the hard by-reference dependency.
- **Revision**: new entry "Collapsing the 4a/4b boundary is a staged
  auto-resume-contract change with a hard by-reference requirement (strengthens
  17:25Z; gate A6)" (research-log.md:192-210).
- **Verification against live machinery**:
  - The entry classifies the collapse as a staged **execution-procedure** change
    under §1.7(b), explicitly **not** a §1.7(k) opt-out-eligible prose edit,
    because "resume-state routing is exactly the schema a running phase reads,
    which §1.7(k) keeps in staging." This is correct against live
    `conventions.md §1.7(k)` (lines 1351-1352, 1365-1367): the staged-schema class
    is named as "a track-file section, a **resume-state field**, the drift-gate
    format, the stamp format", and opt-out criterion 1 is "changes no
    `_workflow/**` artifact schema — no … resume-state field … moves." The
    create-plan resume routing keyed on `design.md` committed+clean → 4b vs
    uncommitted/dirty → 4a (live create-plan/SKILL.md:176-204) is exactly such a
    resume-state contract.
  - Crash-recovery re-spec is bound: "the auto-resume path must fire only on a
    dirty/absent plan after a committed-clean design; Step 1c becomes
    crash-recovery-only." This matches the live Step 1c routing (the happy path no
    longer crosses the boundary; only crash recovery resumes into 4b).
  - By-reference is bound as a **hard requirement** ("not 'adopted anyway'"): if
    any author sub-agent returns more than a thin summary, the combined session
    re-accumulates the design + plan context the boundary prevented, regressing
    context isolation; if by-reference cannot hold, the boundary is retained.
    This directly answers the prior finding's concern that by-reference was stated
    as an "anyway" rather than a load-bearing dependency.
- **Verdict**: VERIFIED — both the staged-contract classification and the hard
  by-reference requirement bound; coherent with live create-plan routing and
  §1.7(k).

#### A7 / A8 / A9 — suggestion refinements folded in — VERIFIED
- **Prior findings**: A7 (fan-out warm-up timing may need author-vs-auditor
  specificity), A8 (phase4 episode-primary fidelity residual too narrow), A9
  (reconstructibility upper bound loosely defined).
- **Revision**: single entry "Suggestion refinements folded in (gate A7, A8, A9)"
  (research-log.md:212-229).
- **Verification**: all three are recorded as the prior findings requested.
  - A7: notes the heavy code-grounded author's first turn may exceed 1 min so the
    cold-write lands after the fan-out starts (author-vs-auditor-specific delay);
    exact warm-up plumbing deferred to implementation, flagged as the most
    intricate orchestration. Matches the prior finding's optional note.
  - A8: widens the PSI-residual trigger from "diagram/signature claims only" to
    **"any `design-final.md` claim with no corresponding episode trace"** — exactly
    the prior finding's Proposed fix.
  - A9: pins the auditor's upper-bound stop to **named clauses** (§ Orientation
    anti-padding + § Plain language no-re-teach boundary) rather than the loose
    "tutorial bloat" phrasing — exactly the prior finding's Proposed fix.
- **Verdict**: VERIFIED — all three suggestion refinements recorded as proposed.

#### New-problem scan — no new finding
- The six 07:37Z entries are internally consistent: the A2 entry's forward
  reference "see the A3 entry below" resolves correctly (A2 at research-log.md:116,
  A3 at research-log.md:145). The A1 entry's claim that `design-review.md` keeps
  "the structural 'does the Overview name a reader' half of audience-fit" is
  consistent with the 15:30Z split (research-log.md:709-712). No 07:37Z entry
  introduces a reference to a live workflow path/anchor that does not exist, and
  none contradicts an earlier surviving decision. No new blocker, should-fix, or
  suggestion is warranted.
