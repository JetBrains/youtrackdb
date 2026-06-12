<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate-verification — Track 1, iteration 1

**Role:** reviewer-adversarial (orchestrator-delegated gate verifier) · **Phase:** 3A · **Overall:** PASS

**Scope note (§1.7 opt-out, D6).** Plan carries no `§1.7(b)` workflow-modifying marker; it uses the prose-rule self-application opt-out per the `### Constraints` note. Treated as workflow-modifying for criteria purposes, applied the five prose criteria, resolved every `.claude/**` read against the live tree (no `_workflow/staged-workflow/`). The absent staging marker is the acknowledged D6 posture, not a finding. Track touches no Java/Kotlin, so grep + Read give full reference accuracy — no PSI caveat.

## Verdicts

#### Verify A1: partition is grep-bucket, not line-wrap; hand-edit set is five not three
- **Original issue**: D1 and Plan-of-Work step 4 partitioned the flip into "30 narrow-grep slug sites take the byte-identical paste" vs "three grep-miss sites hand-edited." But 2 of the 30 narrow-grep hits (`episode-format-reference.md`, `step-implementation.md`) are line-wrapped, so the single-line paste skips them — the hand-edit set is five, not three.
- **Fix applied**: D1 Risks/Caveats (track-1.md:30) now reads "**The paste-vs-hand-edit axis is whether the literal is line-wrapped, not which grep bucket the site falls in (A1).** Five sites cannot take the single-line byte-identical paste…" and names all five. Plan-of-Work step 4 (track-1.md:89) "Hand-edit (line-wrapped literal or variant phrasing), five sites" names the same five. Interfaces "Five hand-edit sites" bullet (track-1.md:136) names the same five with anchors.
- **Re-check**:
  - Location: track-1.md D1 (line 30), Plan of Work step 4 (lines 86-89), Interfaces (lines 136-137).
  - Current state: the partition is now keyed on line-wrap. Five hand-edit sites named: `commit-conventions.md:191-194` (narrow-grep miss), `implementer-rules.md:1102-1105` (variant phrasing, narrow-grep miss), `review-workflow-pr/SKILL.md:44-45` (wrapped chat blurb), `episode-format-reference.md:42-47` and `step-implementation.md:1036-1040` (two narrow-grep hits whose literal wraps).
  - Live-tree confirmation: narrow grep `banned-section heading slugs` returns 30 and DOES match `episode-format-reference.md` + `step-implementation.md`; both are line-wrapped (`episode-format-reference.md:44-45` "The four\nbanned-section heading slugs"; `step-implementation.md:1038-1039` "…are\n`## Banned vocabulary`"). The narrow grep does NOT match `commit-conventions.md` or `implementer-rules.md` (grep -c = 0 each), confirming those two are the distinct grep-miss set. Line ranges in the track bracket each blurb correctly.
  - Criteria met: rule coherence (the recipe now produces a complete flip), instruction completeness (an implementer applying one find/replace no longer silently leaves two sites at four-of-five).
- **Regression check**: Checked arithmetic across D1, Plan-of-Work, Interfaces, Acceptance. 30 narrow-grep slug sites = 28 single-line paste + 2 line-wrapped hand-edit; 11 chat blurbs = 10 find/replace + 1 hand-edit (`review-workflow-pr`); 2 narrow-grep-miss hand-edit (`commit-conventions`, `implementer-rules`). Five hand-edit = 2 wrapped slug + 2 grep-miss + 1 wrapped chat. No double-count, no orphan. Clean.
- **Verdict**: VERIFIED

#### Verify A2: conventions.md:568 four-count narrative sentence named in a step
- **Original issue**: `conventions.md:568` ("The four Tier-B section names are stable headings after YTDB-836…") is a bare count phrase between the §1.5 Tier-B row and the governance grep, named in no step and no in-scope note. After the flip the row lists five but :568 still reads "four," leaving §1.5 self-contradictory; a bare count phrase is not guaranteed caught by the enumeration-scoped acceptance check.
- **Fix applied**: Plan-of-Work step 3 (track-1.md:85) now reads "flip the 'four Tier-B section names' count sentence (`:568`) to five (T1/A2)." The `conventions.md` in-scope note (track-1.md:131) names "the 'four Tier-B section names' count sentence (`:568`) flips to five." Acceptance (track-1.md:108) names "the `§1.5` Tier-B row + count sentence" in the Orientation-presence enumeration.
- **Re-check**:
  - Location: track-1.md step 3 (line 85), in-scope note (line 131), acceptance (line 108).
  - Current state: line 568 is explicitly named for a four→five flip in both the step and the in-scope inventory; acceptance now folds "the §1.5 Tier-B row + count sentence" into the presence check.
  - Live-tree confirmation: `grep -n 'four Tier-B'` returns exactly `conventions.md:568`; sentence present verbatim at :568, sitting two lines below the Tier-B table row (:565). The flip target is real and uniquely located.
  - Criteria met: instruction completeness (the §1.5 self-consistency invariant now has an edit site), rule coherence (no residual "four" survives next to a five-item row).
- **Regression check**: Checked that naming :568 does not collide with the :570 rename-enumeration grep (which R1 keeps on the four-string form) — they are distinct sentences (568 = narrative count, 570 = governance grep command). The track correctly keeps :570 on four strings while flipping :568 to five. Clean.
- **Verdict**: VERIFIED

#### Verify A3: review-workflow-pr/SKILL.md double-listed without stating treatments exclusive
- **Original issue**: `review-workflow-pr/SKILL.md` appeared in both the 11-chat-blurb find/replace instruction and the hand-edit set without the two being stated as mutually exclusive — an implementer could apply the pair and then also hand-edit, or neither cleanly.
- **Fix applied**: Plan-of-Work step 4 (track-1.md:88-89) now states "Find/replace pair — 10 of the 11 chat 'AI-tell subset of' blurbs" and separately lists `review-workflow-pr/SKILL.md:44-45` under hand-edit as "the 11th chat blurb, hard-wrapped." Interfaces (track-1.md:136) names it "the 11th chat blurb, not a separate category, A3." D1 (track-1.md:30) reads "10 of the 11 chat blurbs take the find/replace pair."
- **Re-check**:
  - Location: track-1.md step 4 (lines 88-89), Interfaces (line 136), D1 (line 30).
  - Current state: stated once as exclusive — 10 of 11 take the pair, the 11th (`review-workflow-pr`) is hand-edited because its literal is line-wrapped, explicitly "not a separate category."
  - Live-tree confirmation: `grep -l 'AI-tell subset of'` returns 11 files including `review-workflow-pr/SKILL.md`; its list literal is split across lines 44-45, so the single-line find/replace does not match it. The "10 + 1" split is correct.
  - Criteria met: prompt-design soundness (no ambiguous double-treatment), instruction completeness.
- **Regression check**: Confirmed the "10" in step 4 / D1 and the "11" total are consistent everywhere; the lone hand-edited chat member is the same file in all three locations. Clean.
- **Verdict**: VERIFIED

#### Verify A4: ai-tells catalogue Orientation row under-specified
- **Original issue**: "the `ai-tells` catalogue gains an Orientation row" was under-specified — the `## Catalogue lookups` section is a fingerprint→section map, not a four-name closed-set enumeration, and carries no four-count; the instruction did not say where or as what.
- **Fix applied**: Plan-of-Work step 4 (track-1.md:90) now reads "The `ai-tells` catalogue gains a **too-terse-fingerprint → `§ Orientation` row** — it is a fingerprint→section map, not a closed-set four-name enumeration, so this is a new row, not a four→five flip (T2/A4)." Acceptance (track-1.md:108) verifies it "by a different shape — it gains a too-terse-fingerprint → `§ Orientation` row (not a four→five flip), because it is a fingerprint→section map." Interfaces (track-1.md:135) matches.
- **Re-check**:
  - Location: track-1.md step 4 (line 90), acceptance (line 108), Interfaces (line 135).
  - Current state: framed unambiguously as a new fingerprint→section row keyed on too-terse → `§ Orientation`, explicitly distinguished from a four→five flip and from the closed-set enumeration acceptance shape.
  - Live-tree confirmation: `ai-tells/SKILL.md` `## Catalogue lookups` is a 5-bullet fingerprint-category→`house-style.md §`-section map; `grep 'four\|Four'` over the file returns nothing — no count to bump, confirming the "not a flip site" framing is correct and the new-row framing is the right treatment.
  - Criteria met: instruction completeness (byte-level edit now unambiguous), prompt-design soundness.
- **Regression check**: Confirmed the acceptance check does not fold the catalogue into the closed-set Orientation-presence enumeration (which would wrongly demand a four→five flip); it is verified by the separate "different shape" clause. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass)

## Summary

PASS — A1, A2, A3, A4 all VERIFIED. Fixes applied correctly and confirmed against the live tree; the 28 single-line paste / 10 find/replace / 5 hand-edit partition and the 30-narrow-grep / 11-chat / 2-grep-miss inventory are internally consistent across Plan-of-Work, Interfaces, D1, and Acceptance. No regressions; no new findings.
