<!-- workflow-sha: 1065c173addca97b35fda8af611eb1e656e3ada2 -->
# Adversarial review — Track 1 (iteration 1)

<!-- MANIFEST -->
```yaml
manifest:
  role: reviewer-adversarial
  phase: 3A
  track: "Track 1: Harden readability-auditor slicing and convergence"
  iteration: 1
  verdict: PASS
  findings: 5
  blockers: 0
  should_fix: 2
  suggestions: 3
  index:
    - {id: A1, sev: suggestion, loc: "track-1.md §Interfaces / D6 scope", anchor: "### A1 ", cert: "Challenge: SCOPE — single-track footprint", basis: "six-file diff, one control-flow protocol; split would fragment the shared partition/convergence/relocation rules"}
    - {id: A2, sev: should-fix, loc: "create-plan/SKILL.md:782 vs :791/:832", anchor: "### A2 ", cert: "Assumption test: D6 relocates 'item 9 params'", basis: "item 9 carries THREE plan/-rooted paths with two meanings; only the params-file home (:782) relocates, the two output_path/draft_path 'plan/ directory' anchors (:791,:832) must stay"}
    - {id: A3, sev: should-fix, loc: "edit-design/SKILL.md:624 (phase4 output_path)", anchor: "### A3 ", cert: "Assumption test: D6 moves 'the comprehension output_path'", basis: "the only design-path comprehension output_path is phase4-creation-only (:624); D6 scopes itself to the Phase-1 authoring loop, so :624 is ambiguously in/out of scope"}
    - {id: A4, sev: suggestion, loc: "track-1.md §Invariants — whole-doc floor / S1 / S4 / determinism / verifiable-count", anchor: "### A4 ", cert: "Violation scenarios: five track invariants", basis: "all five INFEASIBLE-to-THEORETICAL under the stated rules; floor double-check and orchestrator-side state hold"}
    - {id: A5, sev: suggestion, loc: "edit-design/SKILL.md:677-682 — auditor anchors are design-doc-only", anchor: "### A5 ", cert: "Assumption test: anchor-fold hash recomputable by the orchestrator", basis: "orchestrator already reads Overview/Core-Concepts to build the spawn anchors, so it can hash them; the assumption holds"}
```

## Findings

### A1 [suggestion]
**Certificate**: Challenge: SCOPE — single-track footprint (is Concern C / the relocation independent enough to split?)
**Target**: SCOPE — track sizing
**Challenge**: The track bundles three concerns (A: deterministic partition + guard; B: section-keyed settled-state; C: file relocation) into one ~6-file diff. Concern C (D6, the `plan/` → `_workflow/reviews/` move) is the most obviously separable: it is a pure file-location refactor with no behavioral dependency on A or B, and it touches a different anchor in each file (`conventions-execution.md §2.5`, the `edit-design` Step 6 resume glob, `create-plan` item-9 params home). A reviewer could argue C should be its own track for a cleaner review boundary.
**Evidence**: The six in-scope files (verified to exist: `edit-design/SKILL.md` 1154L, `readability-auditor.md` 108L, `conventions-execution.md` 757L, `create-plan/SKILL.md` 1471L, `design-document-rules.md` 1040L, `readability-feedback/SKILL.md` 94L) overlap heavily: A and C both edit `edit-design` Step 4/6; A, B, and C all touch `create-plan` Step 4b item 9. Splitting C out would force the same two files to be re-opened in a second PR, and C's new params home is where A's new `slice_count`/`total_lines` params files and B's settled-state scaffolding land (`track-1.md` D6 final sentence). The sizing is six files against the ~12-file merge floor / ~20–25 split ceiling (`planning.md §Track descriptions`), well inside the maximize-bundle band, and the track file carries an explicit maximize-bundle justification (`## Interfaces` "Inter-track dependencies: none").
**Survival test**: YES. Splitting C out would re-open `edit-design` and `create-plan` twice and strand A/B's new files in the old `plan/` home until C lands, paying two review cycles for one coherent control-flow change. The single-track decision holds.
**Proposed fix**: None required. Bundling is the correct call; the track file already states the maximize-bundle rationale.

### A2 [should-fix]
**Certificate**: Assumption test: D6's "relocate `create-plan` Step 4b item 9 params" is a single unambiguous edit
**Target**: D6 / Plan-of-Work step 4
**Challenge**: `create-plan` Step 4b item 9 carries **three** `plan/`-rooted paths with **two** distinct meanings, and only one is a D6 relocate target. The decomposer or implementer reading "point ... `create-plan` Step 4b item 9 params ... at `_workflow/reviews/`" (Plan step 4) could over-apply the move to the two that must stay.
**Evidence**: (1) `create-plan/SKILL.md:782` — "per-spawn parameters in a params file under `_workflow/plan/`" — this **is** the D6 relocate target (a params file = review scaffolding). (2) `:791` — author `output_path` "set to the `plan/` directory the track files land under" — this points at where `track-N.md` files live and must **not** move; moving it would write the authored track files into `reviews/`. (3) `:832-834` — absorption-check `draft_path` "set to the `plan/` *directory* ... reads every `plan/track-N.md`" — same, must **not** move. The track's D6 risk bullet correctly excludes `track-N.md` artifacts ("`plan/` then holds only `track-N.md`"), but the Plan-of-Work step-4 instruction names only "item 9 params" generically and does not flag that item 9 has two `plan/ directory` anchors that are inputs-to-the-author, not review files.
**Survival test**: WEAK — the decision (move only review scaffolding) is right, but the realization instruction under-specifies which of item 9's three `plan/` references move, inviting an implementer to break the author/absorption draft-output wiring.
**Proposed fix**: In Plan-of-Work step 4, replace "`create-plan` Step 4b item 9 params" with an explicit list: relocate only the **per-spawn params-file home** (`:782`), and call out that the author `output_path` (`:791`) and the absorption-check `draft_path` (`:832`) keep their `plan/`-directory value because they address the `track-N.md` artifacts, not review files. Equivalently, add this exclusion to D6's Risks/Caveats.

### A3 [should-fix]
**Certificate**: Assumption test: "the comprehension `output_path`" named in Plan-of-Work step 4 is a Phase-1 authoring-loop file D6 moves
**Target**: D6 scope boundary
**Challenge**: D6 scopes itself to "every Phase-1 authoring-loop per-spawn params file ... and every review output file." But the **only** comprehension-gate `output_path` on the design path is `phase4-creation`-only (`edit-design/SKILL.md:617-624`: "Inject `output_path` only for `phase4-creation` ... `<abs path under _workflow/plan/ for the cold-read output>`"). `phase1-creation` and every interactive kind omit `output_path` and return inline (`:627-628`). So the file Plan-of-Work step 4 calls "the comprehension `output_path`" is a **Phase-4** design-final cold-read output, not a Phase-1 authoring-loop file — yet D6's own scope sentence says "Execution-phase review files (Phase 2/3 ...) keep their existing track-anchored home — this change touches only the Phase-1 authoring loop." Phase 4 is neither Phase-1-authoring nor Phase-2/3-execution, so the rule text does not cleanly say whether `:624` moves.
**Evidence**: `edit-design/SKILL.md:617` ("Inject `output_path` only for `phase4-creation`"), `:624` (the `_workflow/plan/`-rooted path), `:627` (every other kind omits it). The track's D6 Risks/Caveats addresses Phase 2/3 review files but is silent on the Phase-4 `phase4-creation` comprehension output, which is the single concrete `output_path` literal the Step-4 relocation edit would encounter in `edit-design`.
**Survival test**: WEAK. Leaving `:624` on `_workflow/plan/` while the params files around it move to `_workflow/reviews/` would split the design-path review scaffolding across two directories — partially defeating D6's "`plan/` holds only `track-N.md`" goal, since a `phase4-creation` run would still drop its cold-read output into `plan/`. Moving it is almost certainly the intent, but the rule text does not say so.
**Proposed fix**: Have D6 (or Plan step 4) state explicitly whether the `phase4-creation` comprehension `output_path` (`:624`) follows the move. The consistent choice is to move it (it is plan-scoped authoring-cycle review output, and §2.5's generalized home already covers "Phase-1 plan-scoped review scaffolding" — extend the wording to "Phase-1 and Phase-4 plan-scoped review scaffolding," or re-justify why Phase 4 keeps `plan/`).

### A4 [suggestion]
**Certificate**: Violation scenarios — the five track invariants (whole-doc floor, S1, S4, deterministic-partition, verifiable-count)
**Target**: Invariant (all five in `## Invariants & Constraints`) + I6
**Challenge**: I attempted a constructible violation for each.
**Evidence**:
- **Whole-doc floor** (no single slice over ~300 lines): INFEASIBLE under the stated rule. A ~200-line window forces ≥2 slices on any doc >~250 lines (`readability-feedback/SKILL.md:33` confirms the ported source), and the agent-side guard (`slice_count==1 AND total_lines>~300`) re-checks it independently. To violate, both the orchestrator self-check **and** the guard must fail to fire; the guard is computable from the two new params, so a collapse is detectable from inside the auditor (D2). No single-actor failure violates the floor.
- **S1 (auditor reads no log / gets no settled-state)**: INFEASIBLE-to-THEORETICAL. The auditor's allow-list is `Read`+`Grep`, no log path is passed, and the new params (`slice_count`, `total_lines`, `range`) are slicing metadata constant across the fan-out (`readability-auditor.md:27-29`, `:77-81`). The settled-state lives entirely orchestrator-side (D3). A violation would require the orchestrator to inject the settled-state into a params file — but D3 forbids that by construction and no edit adds such a field. THEORETICAL only if a later author misreads "two new params" as license to add a third; not constructible from this track's edits.
- **S4 (one prose-axis owner per surface)**: INFEASIBLE on the design path. The auditor is the sole prose-axis owner (`edit-design/SKILL.md:475`, `readability-auditor.md:49-56`); the de-warmed comprehension gate runs no AI-tell axis. This track adds no second prose owner; it only hardens the auditor's slicing. No violation construction exists within scope.
- **Deterministic-partition**: INFEASIBLE. The partition is a pure function of total line count, the ~200-line window, and the ~6 cap (D1) — all deterministic inputs. Two runs on byte-identical input produce the same slice set. A violation would need a non-deterministic input in the rule; none is named.
- **Verifiable-count**: INFEASIBLE as stated, with one caveat — the self-check is the orchestrator's own assertion (D1 Risks/Caveats acknowledges it is "not visible to the auditor"). The agent-side guard is the independent second detector, so a silent collapse is caught downstream. The invariant survives because the two checks are independent.
**Survival test**: All five survive. The floor's two-place enforcement (orchestrator partition + agent guard) and the orchestrator-side-only settled-state are genuinely robust against the violation constructions I could build.
**Proposed fix**: None. The invariants are well-defended; this finding records the survival certificates so the gate has them on file.

### A5 [suggestion]
**Certificate**: Assumption test: the orchestrator can recompute the anchor-folded section hash (D4) without reading anything it does not already hold
**Target**: D4 / D5 assumption (realization)
**Challenge**: D4 has the orchestrator fold the standing anchors (`## Overview` + `## Core Concepts` on the design path; Component Map + each track's `## Purpose` on the track path) into each section's content hash. This silently assumes the orchestrator already has those anchors in hand each round to hash them.
**Evidence**: It does. The orchestrator builds the per-spawn params naming the anchors the auditor reads (`edit-design/SKILL.md:680-681` — "the standing anchors (the `## Overview` and `## Core Concepts` of `design.md`)"; `create-plan/SKILL.md:823-824` — "the plan Component Map and each track's `## Purpose / Big Picture`"). To pass anchor identities into the spawn it must read them, so hashing them is free. The Core-Concepts conditional-existence (folds in only anchors that exist) matches `design-document-rules.md:542-607` (Core Concepts mandatory only on multi-Part designs). The track-path anchor byte-stability assumption (D5 Risks/Caveats: items 1–8 settle the Component Map before item 9's loop) is confirmed at `create-plan/SKILL.md:764-776` (item 9 runs "after items 1-8 produce the track shape").
**Survival test**: HOLDS. The orchestrator reads the anchors to build spawns regardless, so recomputing the hash adds no new read and no new state on the agent. The D5 byte-stability claim is backed by the item-1-through-8-then-item-9 ordering in live `create-plan`.
**Proposed fix**: None. The assumption is grounded; recorded for the evidence base.

## Evidence base

#### Challenge: SCOPE — single-track, six-file footprint
- **Chosen approach**: One track bundling Concern A (partition + guard), Concern B (settled-state), Concern C (relocation) into one ~6-file reviewable diff; maximize-bundle sizing (`track-1.md §Interfaces`).
- **Best rejected alternative**: Split Concern C (D6 relocation) into its own track — it is a pure file-location refactor with no behavioral coupling to A/B.
- **Counterargument trace**:
  1. C touches `conventions-execution.md §2.5`, the `edit-design` Step 6 resume glob, and `create-plan` item-9 params home — anchors A and B do not need.
  2. A separate C track would give a smaller, single-purpose review surface.
  3. But A and C both edit `edit-design` Step 4/6, and A/B/C all edit `create-plan` Step 4b item 9, so a split re-opens the same two files in a second PR; and A's `slice_count`/`total_lines` params files plus B's settled-state scaffolding land in C's new `_workflow/reviews/` home (D6 final sentence), so C-before-A/B is the only safe order — i.e. they are coupled by file home.
- **Codebase evidence**: Six files verified present; overlap confirmed at `edit-design/SKILL.md` Step 4 (line 446) + Step 6 (line 754) for both A and C; `create-plan/SKILL.md:764-887` item 9 for A, B, and C. Sizing 6 files << ~12-file merge floor / ~20–25 split ceiling (`planning.md §Track descriptions`).
- **Survival test**: YES — bundling avoids two review cycles on the same files and avoids a stranded-files intermediate state.

#### Assumption test: D6 relocates "item 9 params" — exactly which `plan/` references move?
- **Claim**: "point ... `create-plan` Step 4b item 9 params ... at `_workflow/reviews/`" (Plan step 4) is a single unambiguous edit.
- **Stress scenario**: An implementer applies the move to every `plan/`-rooted path in item 9.
- **Code evidence**: `create-plan/SKILL.md:782` (params-file home — MOVES), `:791` (author `output_path` = the `plan/` *directory the track files land under* — must NOT move), `:832-834` (absorption `draft_path` = the `plan/` *directory* it reads `track-N.md` from — must NOT move). Three paths, two meanings; the instruction names only "params."
- **Verdict**: FRAGILE — the decision is right but the realization under-specifies the boundary, risking the author/absorption draft-output wiring.

#### Assumption test: "the comprehension `output_path`" is a Phase-1 authoring-loop file
- **Claim**: D6's "the comprehension `output_path`" (Plan step 4) is in the Phase-1 authoring loop D6 moves.
- **Stress scenario**: An implementer maps D6's "touches only the Phase-1 authoring loop" boundary against the only comprehension `output_path` literal in `edit-design`.
- **Code evidence**: `edit-design/SKILL.md:617-628` — `output_path` is injected **only** for `phase4-creation` (Phase 4), `_workflow/plan/`-rooted at `:624`; `phase1-creation` and interactive kinds omit it. D6 says it touches "only the Phase-1 authoring loop" and that "Phase 2/3" files stay put — Phase 4 falls in neither bucket.
- **Verdict**: FRAGILE — the rule text leaves the Phase-4 comprehension output's home undecided; consistent intent is to move it (extend §2.5's generalized home to name Phase 4 too).

#### Violation scenario: whole-doc floor
- **Invariant claim**: the partition never emits a single whole-doc slice for a doc over ~300 lines.
- **Violation construction**: orchestrator collapses fan-out to one slice on a 700-line `design.md` (the exact YTDB-1158 failure). To stay violated, the agent-side guard (`slice_count==1 AND total_lines>~300`, fed by the two new params) must also fail to fire.
- **Feasibility**: INFEASIBLE — the guard is computable from the params and re-checks the floor from inside the auditor (D2); a single-actor collapse is detected, not silent.

#### Violation scenario: S1 (auditor reads no log / no settled-state)
- **Invariant claim**: the auditor reads no research log and receives no settled-state.
- **Violation construction**: a params file injects the settled-state or a log path.
- **Code evidence**: allow-list `Read`+`Grep`, new params are slicing metadata constant across the fan-out (`readability-auditor.md:27-29`, `:77-81`); settled-state is orchestrator-side only (D3).
- **Feasibility**: INFEASIBLE from this track's edits; THEORETICAL only if a future edit adds a state-bearing param, which D3 forbids.

#### Violation scenario: S4 (one prose-axis owner per surface)
- **Invariant claim**: the prose AI-tell axis has exactly one owner per surface — the auditor.
- **Violation construction**: a second prose-axis owner is introduced.
- **Code evidence**: auditor is sole owner (`edit-design/SKILL.md:475`, `readability-auditor.md:49-56`); de-warmed comprehension gate runs no AI-tell axis. This track adds no second owner.
- **Feasibility**: INFEASIBLE within scope.

#### Violation scenario: deterministic-partition obligation
- **Invariant claim**: two runs on the same document produce the same slice set.
- **Violation construction**: a non-deterministic input in the partition rule.
- **Code evidence**: D1 names only total line count, the ~200-line window, the ~6 cap — all deterministic.
- **Feasibility**: INFEASIBLE — no non-deterministic input exists in the rule.

#### Violation scenario: verifiable-count obligation
- **Invariant claim**: orchestrator computes the expected slice count and self-checks `slices_spawned == expected_slice_count`.
- **Violation construction**: orchestrator silently spawns the wrong count.
- **Code evidence**: the self-check is the orchestrator's own assertion (D1 Risks/Caveats), backstopped by the independent agent-side guard.
- **Feasibility**: INFEASIBLE as a silent failure — the guard catches the collapse case downstream.

#### Assumption test: the orchestrator can recompute the anchor-folded hash
- **Claim**: D4's orchestrator-side anchor-folded hash needs no read or state the orchestrator does not already hold.
- **Stress scenario**: the orchestrator lacks the anchor text to hash.
- **Code evidence**: it reads the anchors to build spawns (`edit-design/SKILL.md:680-681`; `create-plan/SKILL.md:823-824`); Core-Concepts conditional matches `design-document-rules.md:542-607`; track-path anchor stability backed by item-1–8-before-item-9 ordering (`create-plan/SKILL.md:764-776`).
- **Verdict**: HOLDS.
