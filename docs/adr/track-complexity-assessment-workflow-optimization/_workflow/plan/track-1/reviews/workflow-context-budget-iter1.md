<!--
MANIFEST
dimension: workflow-context-budget
iter: 1
sev_scale: native (Critical/Recommended/Minor) mapped to blocker/should-fix/suggestion
findings: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
index:
  - id: WB1
    sev: suggestion
    anchor: "### WB1 "
    loc: ".claude/workflow/workflow.md §Final Artifacts; .claude/workflow/planning.md §Tier classification (live targets after promotion)"
    cert: n/a
    basis: "size delta measured live-vs-staged via wc; per-operation read-section growth under the 5K-token Recommended floor"
-->

## Findings

### WB1 [Minor / suggestion] — per-operation read sections grew modestly under the re-key

- **File:** `.claude/workflow/workflow.md` §Final Artifacts (Phase 4) and §Startup Protocol; `.claude/workflow/planning.md` §Tier classification; `.claude/workflow/conventions.md` §1.1 glossary + §1.2 artifact-set (the live runtime targets the staged copies become after Phase-4 promotion).
- **Axis:** instant per-operation consumption.
- **Cost:** net `workflow.md` +32 lines / +2095 chars (51280 → 53375); `conventions.md` +14 lines / +2210 chars (129627 → 131837); `planning.md` +22 lines / load-on-demand. The whole-file numbers overstate the hot-path cost — the actual per-read growth is concentrated in the §Final Artifacts table+prose, the §Tier-classification axis table, and the §1.2 artifact-set matrix, each read at one phase boundary, all well under the 5K-token (~500-line) Recommended floor.
- **Issue:** Track 1 replaces the one `tier=` token (a three-value enum) with a four-field / three-axis model (`design_gate`, `tracks`, `phase1_complete`, per-track `reconciled_tag`). The text that documents the model necessarily grows: §Final Artifacts went from a 3-row tier table to two independent-predicate tables plus the `∃ track ≥ medium` ADR-boundary prose, and §Tier-classification gained the per-track complexity-tag prediction paragraph and the axis table. None of this is always-loaded — `workflow.md`/`conventions.md`/`planning.md` are TOC-filtered and read per role/phase, so the cost is a per-operation peak at the §Startup-Protocol / §Final-Artifacts / Phase-1 boundaries, not a per-turn baseline tax. The growth buys genuine expressiveness (the `design_gate=yes` single-track cell, newly representable) rather than padding, so it is acceptable. Flagged only so the pattern is visible: the four-axis vocabulary is wordier than the enum it replaces, and future re-keys onto these axes should resist re-stating the full predicate tables in more than one section (the §Per-axis artifact set matrix is the single authority; other sections should point at it).
- **Suggestion:** No change required for this track. Going forward, keep the `∃ track ≥ medium` / design-gate predicate tables canonical in `conventions.md` §Per-axis artifact set and cite rather than restate them from `workflow.md` §Final Artifacts and `planning.md` §Tier classification, so the per-operation read does not carry three copies of the same axis matrix.

## Evidence base
