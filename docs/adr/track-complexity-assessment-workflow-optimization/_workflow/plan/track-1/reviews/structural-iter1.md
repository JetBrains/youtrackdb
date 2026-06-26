<!-- MANIFEST
review_type: structural
phase: 2
tier: full
iteration: 1
verdict: PASS
findings: 4
blockers: 0
should_fix: 2
suggestions: 2
evidence_base:
  certs: 0
index:
  - id: S1
    sev: should-fix
    anchor: "#s1-should-fix"
    loc: "plan/track-1.md §Decision Log #### D10 (lines 61-96)"
    cert: ""
    basis: "DR length"
  - id: S2
    sev: should-fix
    anchor: "#s2-should-fix"
    loc: "plan/track-2.md §Decision Log #### D7 (lines 154-193)"
    cert: ""
    basis: "DR length"
  - id: S3
    sev: suggestion
    anchor: "#s3-suggestion"
    loc: "plan/track-1.md §Decision Log #### D8a (lines 98-124); plan/track-2.md §Decision Log #### D8b (lines 195-225)"
    cert: ""
    basis: "DR length (borderline)"
  - id: S4
    sev: suggestion
    anchor: "#s4-suggestion"
    loc: "implementation-plan.md §Checklist Scope lines (Track 1 line 71, Track 2 line 82)"
    cert: ""
    basis: "Scope-indicator coverage vs track in-scope list"
-->

# Structural review — iteration 1

PASS. No blockers. The two-track plan is structurally executable: zero file
straddle (no `.claude/**` file appears in both tracks' in-scope lists), correct
foundation→consumer ordering (Track 2 `**Depends on:** Track 1`, Track 1 has no
upstream), every design seed D-record has a track home (D8 split cleanly into
D8a/T1 + D8b/T2; all others 1:1), no superseded DRs retained, no
`- [ ] Step:` items or `(provisional)` markers, full DR field completeness
(5 fields × 3 DRs in T1, × 7 in T2), and a complete `full`-tier design document
(Overview + 10 Mermaid flowcharts, each paired with prose, all reasonably
sized, no external images, dedicated sections for the resume-collision and
ledger-durability complex parts). The four findings are all `mechanical`
bloat / scope-format items.

## Findings

### S1 [should-fix]
**Location**: `plan/track-1.md` §Decision Log, `#### D10: Phase-ledger schema delta and resume disambiguation` (body lines 61-96, ~36 lines)
**Issue**: The D10 body runs ~36 lines, over the ~30-line DR cap. The four-bullet form is naturally a 10-20 line block; D10 absorbed long-form material: an embedded four-field schema sub-list inside `**Rationale**` (lines 77-82) that duplicates the design's Data model "Phase-ledger schema delta" table, plus a `**Risks/Caveats**` paragraph (lines 83-94) layering three distinct caveats (old-ledger absent-`design_gate` handling, torn-append safety, track-scoped read) into one bullet.
**Proposed fix**: Trim D10 back to the four-bullet form. Replace the four-field sub-list with a one-line pointer to the design Data model table (the `**Full design**` line already cites §"Phase-ledger schema delta"). Split the three-caveat Risks block into one short bullet stating the load-bearing risk (the design+single↔crash resume collision the marker resolves) and relocate the torn-append / track-scoped-read / old-ledger detail to a prose passage in this same track's `## Decision Log` (per D10 bloat-destination), or drop it as duplicative of the design seed and the `## Invariants & Constraints` section, which already states torn-append safety and track-scoped read as invariants.
**Classification**: mechanical
**Justification**: "All BLOAT findings are `mechanical` by construction — DR length: move long-form material to the matching track section."

### S2 [should-fix]
**Location**: `plan/track-2.md` §Decision Log, `#### D7: Bugs/concurrency ownership is by cognitive mode, not location or symptom` (body lines 154-193, ~40 lines)
**Issue**: The D7 body runs ~40 lines, the longest DR in either track and well over the ~30-line cap. `**Rationale**` carries a three-item numbered routing sub-list (the synchronized-block / concurrent-leak / data-race sub-cases) plus a "mirrors the test-side template" line, and `**Risks/Caveats**` carries the symmetric-tiebreak rule, the triage-backstop mechanics, and the finding-prefix decision — three separate sub-topics in one bullet.
**Proposed fix**: Trim D7 to the four-bullet form. The routing sub-cases and the triage backstop are decision substance, not worked examples, so relocate them to a prose passage in this same track's `## Decision Log` (D7's own record body), leaving the four bullets to state the boundary principle ("ownership by cognitive mode; location and symptom never transfer it"), the rejected alternatives, the symmetric-tiebreak risk, and the implementing track. The `**Full design**` line already points at design §"Bugs / concurrency ownership" (Part 6), which carries the full sub-case walk-through, so the sub-list can compress to a pointer.
**Classification**: mechanical
**Justification**: "All BLOAT findings are `mechanical` by construction — DR length: move long-form material to the matching track section."

### S3 [suggestion]
**Location**: `plan/track-1.md` §Decision Log `#### D8a` (body lines 98-124, ~33 lines) and `plan/track-2.md` §Decision Log `#### D8b` (body lines 195-225, ~33 lines)
**Issue**: Both halves of the split design-D8 record sit at ~33 lines, marginally over the ~30-line cap (within rounding of the soft `~30`, so borderline rather than clearly over like S1/S2). Each carries a long `**Alternatives considered**` paragraph restating the two artifact tables design D8 rejected and a long `**Rationale**` re-deriving the artifact-to-axis mapping the design Part 4 already states.
**Proposed fix**: Optional. If trimmed, compress the rejected-alternatives prose to one bullet ("the two design-D8-rejected tables, see design §Artifact derivation") since the `**Full design**` line already cites Part 4, and state the rationale as the one-line axis tie (`design.md` ⟺ design gate / plan ⟺ track count > 1 for D8a; `adr` ⟺ ∃ track ≥ medium for D8b). No content is lost — the design seed carries the full derivation.
**Classification**: mechanical
**Justification**: "All BLOAT findings are `mechanical` by construction — DR length: move long-form material to the matching track section."

### S4 [suggestion]
**Location**: `implementation-plan.md` §Checklist — Track 1 `**Scope:**` line (line 71) and Track 2 `**Scope:**` line (line 82)
**Issue**: Each track's plan-file `**Scope:**` coverage list names one fewer file than the track file's `## Interfaces and Dependencies` in-scope list, so the strategic signal and the authoritative scope tell slightly different stories. Track 1's Scope names 12 files and says `~12 files`, but the track in-scope list has 13 — `.claude/workflow/implementation-review.md` (the Phase-2 pass selector) is in the track but absent from the Scope line and the Component Map. Track 2's Scope names 19 files and says `~19 files`, but the track in-scope list has 20 — `.claude/workflow/review-iteration.md` (the canonical finding-prefix owner table) is in the track but absent from the Scope line and the Component Map. Both `~N` figures are within `~` tolerance, so the count is not the defect; the missing coverage-list entry is. Both omitted files are fully accounted for inside their track files, so execution is not impaired — this is a coherence gap, not a scoping error.
**Proposed fix**: Add `implementation-review.md` to Track 1's Scope coverage list (and bump `~12 files`→`~13 files`), and add `review-iteration.md` to Track 2's Scope coverage list (and bump `~19 files`→`~20 files`). Optionally surface both files in the Component Map's annotated bullets (the Phase-1 artifact-gates bullet for Track 1; the reviewer-roster bullet for Track 2), since the Component Map likewise omits them.
**Classification**: mechanical
**Justification**: "Scope-indicator format issues ... — `mechanical`" — the fix is a single unambiguous edit (name the file, bump the approximate count) that does not change plan intent.

## Evidence base

certs: 0 — structural review reads no codebase and produces no certificates.
All findings are grounded in the plan, track, and design files under review.
