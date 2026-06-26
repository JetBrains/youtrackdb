<!-- MANIFEST
review_type: structural-gate-verification
phase: 2
tier: full
iteration: 2
overall: PASS
findings: 0
evidence_base:
  certs: 0
verdicts:
  - id: S1
    verdict: VERIFIED
    loc: "plan/track-1.md §Decision Log #### D10 (lines 61-85)"
  - id: S2
    verdict: VERIFIED
    loc: "plan/track-2.md §Decision Log #### D7 (lines 154-180)"
  - id: S3
    verdict: VERIFIED
    loc: "plan/track-1.md #### D8a (lines 87-107); plan/track-2.md #### D8b (lines 182-205)"
  - id: S4
    verdict: VERIFIED
    loc: "implementation-plan.md §Checklist Scope lines (Track 1 line 73, Track 2 line 85); Component Map bullets (lines 44-48, 58-62)"
-->

# Structural gate verification — iteration 2

PASS. All four iter-1 structural findings (S1–S4, all `mechanical`, all ACCEPTED)
are VERIFIED: each fix landed as described, every load-bearing claim survives in
the trimmed DR or in the same track's `## Invariants & Constraints` / design seed
the trim points to, and no trim introduced a contradiction with another DR, the
Plan-of-Work steps, or the design's Part 4 / Part 6 axis model. The S4 Scope-line
counts now match each track's in-scope-list cardinality (Track 1 = 13, Track 2 =
20). The re-scan of all ten DRs surfaced no DR clearly over the ~30-line cap that
was not already S1–S4: D6 (32 lines) and D5 (30 lines) are borderline ~30–32
items the iter-1 review saw and did not flag, within tolerance. No new finding.

#### Verify S1: D10 DR length / four-field schema + three-caveat bloat
- **Original issue**: D10 ran ~36 lines; `**Rationale**` embedded a four-field
  schema sub-list duplicating the design Data model table, and `**Risks/Caveats**`
  layered three caveats (absent-`design_gate` handling, torn-append safety,
  track-scoped read) into one bullet.
- **Fix applied**: trimmed to ~25 lines. The schema sub-list is replaced by an
  inline naming of the four fields with a pointer to the design Data model table;
  the Risks block is compressed to the load-bearing resume-collision risk plus the
  absent-`design_gate` posture, with torn-append safety and the track-scoped read
  relocated to a pointer to `## Invariants & Constraints`.
- **Re-check**:
  - Plan / track file / design location: `plan/track-1.md` §Decision Log, `#### D10`
    (lines 61-85, body = 25 lines).
  - Current state: `**Rationale**` names "the four fields the design Data model
    table specifies (`design_gate`, the plan-presence / track-count signal, the
    Phase-1-complete marker, and the per-track reconciled-tag home)" (lines 70-73)
    — no sub-list. `**Risks/Caveats**` (lines 77-83) states the resume-collision
    risk resolved by the Phase-1-complete marker, the old-ledger absent-`design_gate`
    posture, and "Torn-append safety and the track-scoped per-track-tag read are
    stated as invariants in `## Invariants & Constraints`."
  - Criteria met: DR-length cap (now 25 ≤ ~30); no duplication of the design Data
    model table; single-topic bullets.
- **Regression check**: confirmed the two relocated caveats have a home — torn
  append is stated at `## Invariants & Constraints` lines 433-435, the track-scoped
  read at lines 430-432; both also appear in `## Validation and Acceptance` (lines
  299-301, 310-312). The resume-collision risk the trimmed bullet keeps is
  consistent with D1's Risks/Caveats (lines 51-57) and the Step-1c routing step
  (Plan-of-Work (2), lines 229-244). Checked the design seed pointer (`design.md`
  §"Phase-ledger schema delta", §"Resume routing" Part 5) — pointer intact at line
  85. Clean.
- **Verdict**: VERIFIED

#### Verify S2: D7 DR length / routing sub-list + three-topic caveats bloat
- **Original issue**: D7 ran ~40 lines (longest DR); `**Rationale**` carried a
  three-item numbered routing sub-list plus a "mirrors the test side" line, and
  `**Risks/Caveats**` carried the symmetric tiebreak, the triage backstop, and the
  prefix decision in one bullet.
- **Fix applied**: trimmed to ~27 lines. The routing sub-list is compressed to a
  one-line naming of the three sub-cases plus a pointer to design Part 6 "Bugs /
  concurrency ownership" which carries the full walk-through; the caveats keep the
  symmetric tiebreak, the triage backstop, and the prefix note.
- **Re-check**:
  - Plan / track file / design location: `plan/track-2.md` §Decision Log, `#### D7`
    (lines 154-180, body = 27 lines).
  - Current state: `**Rationale**` (lines 161-170) names the three sub-cases inline
    ("a logic bug in a `synchronized` block, a leak on a concurrent path, a data
    race") and points to design §"Bugs / concurrency ownership" (Part 6) for the
    full walk-through. `**Risks/Caveats**` (lines 171-178) keeps the symmetric
    tiebreak, the triage backstop (always-on `review-bugs`, `review-concurrency` on
    `concurrency` only, the one-line triage-gap note), and the prefix decision
    (`BC` retired, `TB`/`TC` kept).
  - Criteria met: DR-length cap (now 27 ≤ ~30); decision substance retained (the
    routing principle, not just an example); pointer to the full design walk-through.
- **Regression check**: the three sub-cases are derivative of the cognitive-mode
  principle stated in the same `**Rationale**` (`review-concurrency` = multi-thread
  reasoning, `review-bugs` = single-threaded sequential reasoning), so the
  one-line compression loses no routing rule — the principle resolves them. The
  triage backstop and symmetric tiebreak echo `## Invariants & Constraints` (lines
  544-548) and `## Validation and Acceptance` (lines 409-412); consistent. Checked
  the `**Full design**` pointer (line 180, design Part 6 + Data model) — intact.
  Clean.
- **Verdict**: VERIFIED

#### Verify S3: D8a / D8b borderline DR length
- **Original issue**: D8a and D8b each ran ~33 lines (borderline over ~30); each
  carried a long rejected-alternatives paragraph restating the two design-D8 tables
  and a long rationale re-deriving the artifact-to-axis mapping.
- **Fix applied**: both trimmed to ~27 lines by compressing the rejected-alternatives
  prose to a pointer to design §"Artifact derivation" and tightening the rationale
  to the axis tie.
- **Re-check**:
  - Plan / track file / design location: `plan/track-1.md` `#### D8a` (lines 87-107,
    body = 21 lines) and `plan/track-2.md` `#### D8b` (lines 182-205, body = 24
    lines).
  - Current state: D8a `**Alternatives considered**` (lines 88-94) names the two
    rejected tables and cites "see design §Artifact derivation", scoping itself to
    the `design.md`/plan half; `**Rationale**` (lines 95-100) states the axis tie
    (`design.md` iff `design_gate=yes`, plan iff track count > 1). D8b
    `**Alternatives considered**` (lines 183-189) names the two rejected tables and
    cites design §"Artifact derivation"; `**Rationale**` (lines 192-196) states the
    axis tie (`adr` iff ∃ track ≥ medium). Both `**Full design**` lines cite Part 4.
  - Criteria met: both now ≤ ~30; the rejected alternatives, the boundary split
    (D8a owns design/plan, D8b owns adr), and the implementing track are all stated;
    full derivation lives in the design seed the pointer cites.
- **Regression check**: the D8a/D8b split boundary is preserved and non-overlapping
  — D8a's note (lines 108-114) and D8b's note (lines 206-208) cross-reference each
  other's ownership correctly. The axis ties match design Part 4 and the carrier
  tables (`workflow.md` §"Final Artifacts", `conventions.md` per-axis artifact set
  per Track 1 Plan-of-Work (4) lines 260-273; `create-final-design.md` per Track 2
  Plan-of-Work (5) lines 367-371). No contradiction with the Part 4 axis model.
  Clean.
- **Verdict**: VERIFIED

#### Verify S4: Scope-line coverage vs track in-scope list
- **Original issue**: Track 1's plan-file `**Scope:**` named 12 files (`~12 files`)
  but the track in-scope list had 13 (`implementation-review.md` omitted from the
  Scope line and the Component Map). Track 2's Scope named 19 (`~19 files`) but the
  in-scope list had 20 (`review-iteration.md` omitted from both).
- **Fix applied**: Track 1 Scope now `~13 files` and names `implementation-review.md`;
  Track 2 Scope now `~20 files` and names `review-iteration.md`; the Component Map's
  Phase-1-artifact-gates bullet (Track 1) and reviewer-roster bullet (Track 2) now
  mention the two files.
- **Re-check**:
  - Plan / track file / design location: `implementation-plan.md` Checklist Track 1
    Scope (lines 73-77) and Track 2 Scope (lines 85-90); Component Map bullets
    (Track 1 artifact-gates lines 44-48, Track 2 roster lines 58-62).
  - Current state: Track 1 Scope reads "`~13 files` … and `implementation-review.md`
    (the Phase-2 pass selector)." Track 2 Scope reads "`~20 files` … `review-iteration.md`
    (the finding-prefix owner table) …". Component Map Track-1 bullet (line 47)
    names "the `implementation-review.md` Phase-2 pass selector"; Track-2 roster
    bullet (line 61) names "the `review-iteration.md` finding-prefix owner table".
  - Criteria met: Scope coverage list now matches the in-scope-list cardinality —
    counted Track 1 in-scope list = 13 (lines 330-365), Track 2 in-scope list = 20
    (lines 437-474, counting the three new + three removed agent files); both `~N`
    figures now exact.
- **Regression check**: re-counted both in-scope lists against the new figures —
  13 and 20, exact match, no double-count or omission introduced. The two added
  Component-Map mentions do not duplicate or contradict the Mermaid node labels
  (the `AX` node already covers Phase-1 artifact gates; `ROST` covers the roster).
  No other Scope/Component-Map pair drifted. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass; the re-scan of all ten DRs surfaced no new
clearly-over-cap or contradiction finding.)

## Evidence base

certs: 0 — structural gate verification reads no codebase and produces no
certificates. All verdicts are grounded in the plan, track, and design files
under review.
