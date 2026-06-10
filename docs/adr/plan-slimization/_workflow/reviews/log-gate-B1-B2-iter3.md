<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: A67, sev: suggestion, loc: "research-log.md:969-972 (B2 Why, access diagnosis)", anchor: "### A67 ", cert: C1, basis: "the diagnosis-reword half of A62's fix did not land: the Why keeps the phase-only attribution and the 'create-plan orchestrator' role-name while the ripple correctly attributes the spawn to planner; alignment, not wiring — the ripple's edit instruction is complete"}
  - {id: A68, sev: suggestion, loc: "research-log.md:988-990 (B2 ripple, wiring-item marker set)", anchor: "### A68 ", cert: C2, basis: "'the §2.5 TOC row and section markers' names no marker set: the all-markers reading extends §Verdict-producer unconditionally (making the entry's own contingency clause dead text) and hands planner the irrelevant Coverage S5; A62's '(and its subsection rows as applicable)' qualifier was dropped in application"}
verdicts:
  - {id: A62, verdict: PARTIAL}
  - {id: A63, verdict: VERIFIED}
  - {id: A64, verdict: VERIFIED}
  - {id: A65, verdict: VERIFIED}
  - {id: A66, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 6, breaks: 0, holds: 4}
cert_index:
  - {id: C1, verdict: FRAGILE, anchor: "#### C1 "}
  - {id: C2, verdict: FRAGILE, anchor: "#### C2 "}
  - {id: C3, verdict: HOLDS, anchor: "#### C3 "}
  - {id: C4, verdict: INFEASIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: HOLDS, anchor: "#### C5 "}
  - {id: C6, verdict: HOLDS, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

# Log-adversarial gate — B1 + B2 batch, iteration 3

Verification plus whole-batch re-challenge (D15) of the revised 15:05Z (B1)
and 15:07Z (B2) entries. Four of the five iteration-2 fixes are VERIFIED;
A62 is PARTIAL — the load-bearing wiring clause is faithful and complete,
but the proposed fix's diagnosis-reword clause did not land, a residue
carried at suggestion weight (A67). The re-challenge of all fix-added
material yields one further suggestion on B2 (A68) and nothing on B1: B1's
mechanism, its enumeration, and its provenance hygiene now survive every
challenge I can ground in real text, and B2's wiring instruction is correct
on both annotation axes. The batch has converged; zero blockers. Count
validation: `grep -cE '^### [A-Z]+[0-9]+ '` over this file returns 2.

## Verdicts

**A62 — PARTIAL.** The load-bearing clause landed faithfully: the ripple
(research-log.md:987-993) demands the §2.5 extension on BOTH annotation
axes (phases +`1`, roles +`planner`, named as "the role that owns every
third-scope spawn"), correctly notes the row already carries
`reviewer-adversarial`, and strikes the explicit-read-instruction
alternative as non-viable standalone under §1.8(e) subset validation and
the §1.8(f) reader-side row match. All four claims re-verify:
conventions-execution.md:13/:474 carry `reviewer-adversarial` but no
`planner` and phases `2,3A,3B,3C,4` with no `1`; conventions.md §1.8(e)
makes a suffix claiming phase `1` against that target a CI subset error;
§1.8(f)'s two-axis row match stops a protocol-following reader regardless
of where a read instruction came from. The proposed fix's first clause
("reword the diagnosis to both axes") did not land: the Why (:969-972)
keeps the phase-only attribution and the "`create-plan` orchestrator"
role-name. The blocker's operative risk — an implementer wiring one of two
parties — is closed by the ripple, which is the edit instruction, so the
residue is carried as A67 [suggestion] rather than a re-blocked finding.

**A63 — VERIFIED.** The deferral (:996-1001) names the contingency: if the
sub-question resolves to the variant ("this branch's own iteration≥2
practice de facto"), the §Verdict-producer row needs the same two-axis
extension. Correct against conventions-execution.md:598 — that row's roles
lack `planner` and its phases are `2,3A,3C`, so both axes are exactly what
the contingency would require.

**A64 — VERIFIED.** B2's lead (:955-958) names both Phase-4 consumers —
Part 7's `adr.md` fold in `full`/`lite` and B1's PR-description verdict
summary in `minimal` — and "the review files die in the Phase 4 cleanup
without feeding either" preserves the load-bearing half (the log, never
the review files, is the verdict carrier). Consistent with B1's post-fix
mechanism (:906-909).

**A65 — VERIFIED.** B1's Part-7 ripple item (:936-940) now names the TL;DR
(design.md:933-936) and the §Edge cases first bullet (design.md:998-1001).
Both spans re-resolve to the two remaining every-tier fold assertions
("folds the research log's resolved adversarial verdicts into `adr.md` in
every tier"; "The `adr.md` fold runs in every tier"), and a grep over
Part 7 finds no third.

**A66 — VERIFIED.** The lens pin gained its design destination (:944-947):
Part 3 §Domain priming plus Part 1's matched-categories sentence, with the
semantics restated (centrally-matched categories plus explicit user
additions; `minimal` lens-free unless the user adds one). Both destinations
exist (design.md:419-436, :248-250) and carry exactly the Gate-1-no
ambiguity the pin resolves.

## Findings

### A67 [suggestion]
**Certificate**: C1 (Assumption test: the A62 application realigns the
whole entry)
**Target**: B2's `Why` access diagnosis (research-log.md:969-972) — the
half of A62's proposed fix that did not land.
**Challenge**: A62's proposed fix opened with "Reword the diagnosis to
both axes." The applied fix reworded the ripple only. The Why still
attributes the §2.5 exclusion to phases alone and still names the spawning
party "the `create-plan` orchestrator" — the §1.8(a) role-name A62's
evidence corrected: the /create-plan agent maps to `planner`, while
`orchestrator` is the /execute-tracks driver and already sits in §2.5's
roles list. Read alone, the Why's named party would be reached by a
phase-only extension, while the ripple insists on roles +`planner` for a
differently-named party; a reader reconciling the two halves must redo
A62's §1.8(a) lookup. No execution path goes wrong, because the ripple is
the edit instruction and it is complete, so this is entry-internal
alignment, not a wiring gap.
**Evidence**: research-log.md:969-972 vs :988-991; conventions.md
§1.8(a) ("planner — /create-plan agent (Phases 0, 1)"; "orchestrator —
/execute-tracks session-level driver"); iter-2 A62 proposed-fix text.
**Proposed fix**: One clause in the Why: "...neither the third-scope
reviewer nor the spawning planner may read it (and a phase-only extension
would still exclude the planner on the role axis)."

### A68 [suggestion]
**Certificate**: C2 (Assumption test: "section markers" resolves to a
determinate edit set)
**Target**: B2's ripple, the wiring item's marker set
(research-log.md:988-990), added by the A62 fix.
**Challenge**: "The §2.5 TOC row and section markers extend their phases
with `1` AND their roles with `planner`" does not say which markers. §2.5
carries four subsection markers: §Manifest-plus-sections file (:483),
§Anchored addressing (S4/S6) (:546), §Verdict-producer manifest variant
(:598), and §Coverage (S5) (:631). The all-markers reading extends the
§Verdict-producer row unconditionally, which makes the entry's own
deferral clause (:996-1001, extension only if the variant is adopted)
dead text, and hands `planner` the Coverage S5 rule, which carries no
third-scope duty. The subset reading is right but unstated. A62's
proposed fix carried the qualifier "(and its subsection rows as
applicable)"; the application dropped it.
**Evidence**: conventions-execution.md:474/:483/:546/:598/:631 (the
parent marker and four subsection markers); research-log.md:988-990 vs
:996-1001; iter-2 A62 proposed-fix text.
**Proposed fix**: Restore the qualifier or name the set: the §2.5 parent
marker plus §Manifest-plus-sections and §Anchored addressing (S4/S6);
§Verdict-producer per the deferral's contingency; §Coverage (S5)
excluded.

## Evidence base

**Assumption tests**

#### C1 Assumption test: the A62 application realigns the whole entry
- **Claim**: The applied A62 fix makes B2's access story coherent end to
  end.
- **Stress scenario**: Read the Why alone, then the ripple alone. The
  ripple stands complete — both axes, `planner` named as the spawn owner,
  instruction-only alternative struck under §1.8(e)/(f), all verified
  against conventions-execution.md:13/:474 and conventions.md §1.8. The
  Why keeps the iteration-1 phase-only attribution and the "`create-plan`
  orchestrator" role-name, so the entry's two halves name the same party
  differently and diagnose the same exclusion on different axis counts.
- **Code evidence**: research-log.md:969-972, :988-991;
  conventions.md:1138-1139 (role-enum lines for `orchestrator` and
  `planner`).
- **Verdict**: FRAGILE — the wiring clause closes the blocker risk; the
  diagnosis clause of A62's fix is unapplied. Grounds the A62 PARTIAL
  verdict. → A67

#### C2 Assumption test: "section markers" resolves to a determinate edit set
- **Claim**: An implementer executing the wiring item knows which §2.5
  annotation markers to extend.
- **Stress scenario**: Implement literally. "All §2.5 markers" extends
  §Verdict-producer (:598) now, pre-empting the deferral that makes its
  extension contingent on the variant resolution, and extends §Coverage
  (S5) (:631), inviting the planner into a follow-or-exempt rule with no
  third-scope relevance (a small context-budget cost). "Some markers"
  honors the deferral but leaves the subset unstated; the needed set is
  the parent marker (:474) plus §Manifest-plus-sections (:483, the file
  shape the reviewer writes) and §Anchored addressing (:546, the count
  grep the planner validates).
- **Code evidence**: conventions-execution.md:474-631 marker annotations;
  research-log.md:988-990 vs :996-1001.
- **Verdict**: FRAGILE — one restored qualifier or a named set resolves
  it. → A68

#### C3 Assumption test: phases +`1` covers every third-scope spawn
- **Claim**: Extending §2.5's phases with `1` (not `0,1`) reaches both the
  Phase 0→1 gate and the D15 batch gates.
- **Stress scenario**: A gate party self-identifying as phase `0` at the
  boundary would fail the §1.8(f) match against a row extended with `1`
  only — silently, the same failure class A62 named.
- **Code evidence**: design.md:254 (D3 places the tier decision at
  create-plan Step 4); design.md:421-423 and :440-441 (domain priming and
  D14 model triage key off the **confirmed** tier, so the gate cannot run
  before tier confirmation); conventions.md §1.8(b) (phase 0 = interactive
  exploration, phase 1 = plan + design authoring). The gate's earliest
  possible run sits inside Step 4 after tier confirmation — Planning's
  first act, phase `1` — and the D15 batch gates run during Phase-1 review
  holds by construction. The implemented scoped section pins the
  reviewer's phase in its bootstrap, as the existing design-scoped section
  pins "Your phase: 1."
- **Verdict**: HOLDS — `1` is the coherent token; no finding.

#### C5 Assumption test: the four non-blocker fixes are citation-accurate as applied
- **Claim**: The A63-A66 applications resolve to real anchors that say
  what the entries claim.
- **Stress scenario**: Re-resolve each. A63: the §Verdict-producer row
  (conventions-execution.md:598) lacks `planner` and phase `1`, so "the
  same two-axis extension" is the correct contingency. A64: B2's lead
  matches B1's post-fix mechanism (:906-909). A65: design.md:933-936 and
  :998-1001 are exactly the two remaining every-tier fold assertions in
  Part 7; grep finds no third. A66: §Domain priming (design.md:419-436)
  and the matched-categories sentence (:248-250) exist and state the
  tier-confirmation lens semantics the pin sharpens. Naming:
  track-review.md:641 carries `<type>-iter<N>.md` in
  file-when-handed-a-path mode. Provenance: the 08:40Z forward marker
  (research-log.md:485-487) states B1's narrowed scope including the
  PR-description summary.
- **Code evidence**: as listed per item.
- **Verdict**: HOLDS — grounds the four VERIFIED verdicts.

**Invariant / violation scenarios**

#### C4 Violation scenario: tier-in-force at Phase 4 mis-times a mid-flight tier change
- **Invariant claim**: B1's D12 clause ("a `minimal`→`lite` upgrade
  happens mid-flight, before Phase 4, so the fold applies per the tier in
  force at Phase 4") never strands a verdict trail.
- **Violation construction**:
  1. Attempt (i), an upgrade during Phase 4 itself: unreachable — tier
     upgrades ride the inline-replan ESCALATE path, which runs in Phase
     3A/3C only (design.md:266-267; conventions.md §1.8(b) phase note),
     so the tier is fixed before Phase 4 begins.
  2. Attempt (ii), a user-driven late downgrade to `minimal`: Gate 1
     stays no (downgrades move Gate 2 only, and Part 1 makes downgrades
     non-automatic), so B1's low-stakes acceptance transfers and the
     PR-description summary still lands at Phase 4.
- **Feasibility**: INFEASIBLE for (i); (ii) is constructible but produces
  no stranded trail. No finding — the trace strengthens B1.

**Decision challenges**

#### C6 Challenge: B1's ripple altitude — design Parts only, no implementation surfaces
- **Chosen approach**: B1's ripple routes design.md Parts 1/2/3/7 plus
  D10/D12 and names no Phase-4 implementation surface
  (create-final-design.md, workflow.md § Final Artifacts, conventions.md
  §1.8(b)'s per-phase commit-count parenthetical), even though the shed
  changes `minimal`'s Phase-4 step set.
- **Best rejected alternative**: Enumerate implementation surfaces in the
  ledger, as B2 does for conventions-execution.md §2.5.
- **Counterargument trace**:
  1. B2 names §2.5 because the gate mechanism itself lives there and an
     access miss fails silently at the Phase 0→1 boundary — no later
     derivation step would surface it (A59/A62 history).
  2. B1's fold-step surfaces are derived: Step 4b derives the
     implementation plan from design.md Part 7, which B1's ripple does
     rewrite, so those surfaces are enumerated at plan derivation with
     the corrected design as source.
  3. Checked in the same pass: the `reviewer-adversarial` role-enum
     description (conventions.md:1147, "Phase 1 design adversarial +
     Phase 3A track adversarial review") goes stale under ratified D6
     (the design scope goes moot, the third scope arrives). That is D6's
     implementation footprint, not the batch's, and §1.8(f) matches
     tokens, not descriptions, so the stale prose bars nobody.
- **Codebase evidence**: research-log.md:936-949 (B1 ripple);
  design.md:919-1016 (Part 7 as the derivation source);
  conventions.md:1147.
- **Survival test**: YES — the altitude split is principled
  (mechanism-resident surfaces in the ledger, derived surfaces at Step
  4b); no finding.
