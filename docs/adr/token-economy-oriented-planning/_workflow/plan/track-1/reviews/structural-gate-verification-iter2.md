<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
verdicts:
  - {id: S1, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Structural gate verification — Track 1, iteration 2

Re-check of the single should-fix finding S1 from iteration 1, plus a
regression scan of the five CR1 enumeration-broadening edits. No codebase
read; markdown matches are definitive, so no PSI and no reference-accuracy
caveat applies.

## Verdicts

#### Verify S1: design.md line-129 still names the pre-CR1 narrow byte-identical set
- **Original issue**: `design.md` line 129 (the §"Advisory enforcement" Edge cases bullet) names a narrow byte-identical set — three paraphrases (technical, risk, adversarial review prompts) plus two summary sites (§1.1 glossary, §1.2 plan summary) — while the plan/track invariant S2 was broadened during the CR1 fix to the full SYNC set.
- **Fix applied**: record-and-defer. `design.md` is frozen after Phase 1, so its line-129 enumeration correction is owed to the Phase 4 `design-final.md` reconciliation, not to a Phase 2 plan/track edit. The plan and track halves already carry the full SYNC set (applied during the CR1 fix), so no plan/track edit was owed for S1.
- **Re-check**:
  - Design location: `design.md` line 129 — confirmed still reads "The three sizing-rule paraphrases (the technical, risk, and adversarial review prompts) and the two summary sites (the §1.1 glossary, the §1.2 plan summary) stay byte-identical." This is the pre-CR1 narrow set; it omits the consistency-review prompt and structural-review.md's own Track-terminology bullet.
  - Frozen-design check: the `<!-- workflow-sha: ... -->` stamp on `implementation-plan.md` and `track-1.md` (both `a91341...a3f9`) marks the Phase 1 freeze; `design.md` carries no post-freeze plan/track-driven edits and is correctly left untouched.
  - Authoritative SYNC source (live `prompts/structural-review.md` lines 56-68): the full synchronized set is the §1.1 glossary, §1.2 planning-rule summary, the create-plan Step 4 rule, structural-review.md's own Track-terminology bullet, and the Track-terminology bullet in technical / adversarial / risk / consistency review prompts ("four separate files"). The five-review-prompt phrasing in the plan/track (technical, risk, adversarial, consistency, structural-review.md's own bullet) maps to those four files plus structural-review.md — exact, no off-by-one, no phantom.
  - Plan/track full-set carriers, all confirmed correct:
    - Plan §Constraints S2 (lines 36-39) — full set incl. create-plan Step 4 and all five review prompts.
    - Plan Invariant S2 (lines 166-174) — full set, same enumeration.
    - Track §Plan of Work invariants (lines 135-142) — full set.
    - Track §Validation and Acceptance (lines 176-181) — full set.
    - Track §Interfaces out-of-scope set (lines 206-215) — full set, named as the authoritative enumeration Phase A pins against.
  - Criteria met: the structural review's consistency / contradiction criteria are satisfied — the plan/track halves state the same broadened invariant and match the live SYNC comment. The frozen-design lag is a legitimate Phase 4 deferral, not a Phase 2 plan/track defect.
- **Regression check**: a frozen-design enumeration lag is not a STILL OPEN basis at Phase 2; the design-final.md author reconciles it. Checked the plan and track halves for any contradiction the deferral might leave — none. Clean.
- **Verdict**: VERIFIED (resolution correct; the `design.md` line-129 enumeration correction is deferred to the Phase 4 `design-final.md` reconciliation).

## Regression scan of the five CR1 edits

Re-read the five enumeration-broadening locations and cross-checked them
against the rest of the plan and track.

- **Internal consistency of the five locations.** All five now state the
  identical full SYNC set: §1.1 glossary, §1.2 plan summary, create-plan
  Step 4 rule, and the five review prompts (technical, risk, adversarial,
  consistency, structural-review.md's own Track-terminology bullet). One
  story, no drift between the §Constraints / §Invariant-S2 / §Validation /
  §Interfaces statements.
- **No contradiction with other claims.** Goals (no enumeration), Component
  Map (structural-review.md edited only in TRACK SIZING; its Track-terminology
  bullet stays byte-identical — consistent with S2), D1-D5, S1, and S3 carry
  no competing enumeration. Integration Points' "closed two-reason under-fill
  `— size:` set is untouched" matches track Validation line 172. No
  contradiction surfaced.
- **No phantom reference.** Every named site — §1.1, §1.2, create-plan Step 4,
  and the five review prompts — exists and is named in the live SYNC comment.
- **No marker creep.** No `- [ ] Step:` items and no `*(provisional)*` markers
  in either file (grep confirmed). The `> **Scope:**` line on the Track 1
  checklist entry is intact (`~3 files covering ...`, plan line 214).
- **S3 co-ship intact.** The producer/consumer pairing (planning.md producer
  half + structural-review.md consumer half ship together) is unaffected by
  the enumeration broadening; the broadening touched only the S2 byte-identical
  set, not the S3 single-track constraint.

The CR1 fix solved the under-enumeration without shifting any problem. No new
finding.

## Findings

(none)

## Evidence base

(none — this review reads no codebase; all checks are markdown matches over
the plan, track, live `structural-review.md` SYNC comment, and the frozen
`design.md`.)

## Summary

PASS. S1 VERIFIED — the record-and-defer resolution is correct: the plan and
track halves carry the full SYNC set, and the only outstanding correction is
the frozen `design.md` line-129 enumeration, legitimately deferred to the
Phase 4 `design-final.md` reconciliation. The regression scan of the five CR1
edits surfaced no new finding and no fix-shifted problem. No remaining
blockers.
