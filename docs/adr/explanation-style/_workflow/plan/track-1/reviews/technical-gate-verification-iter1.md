<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Technical gate verification — Track 1 (iteration 1)

- **role**: reviewer-technical (gate verifier)
- **phase**: 3A
- **track**: Track 1: Conventions opt-out, Orientation rule, and the atomic subset sync
- **review type**: technical
- **overall**: PASS

§1.7 opt-out posture (D6) applied: plan uses the prose-rule opt-out, not the workflow-modifying marker; `.claude/**` reads resolved against the LIVE tree; the absent staging marker is the acknowledged D6 deviation, not a finding. Track touches no Java — grep + Read give full reference accuracy.

#### Verify T1: §1.5 `:568` "four Tier-B section names" count not in the flip roster
- **Original issue**: the §1.5 count sentence at `conventions.md:568` ("The **four** Tier-B section names are stable headings") sits between the Tier-B table row (flipped to five) and the `:570` governance grep, but the track's conventions.md in-scope item named only the table row and the grep, leaving an intra-§1.5 four-vs-five drift `review-workflow-consistency` would flag.
- **Fix applied**: the `:568` count flip was named in three places in `track-1.md` — Plan of Work step 3 ("flip the 'four Tier-B section names' count sentence (`:568`) to five (T1/A2)"), the Interfaces conventions.md item ("the 'four Tier-B section names' count sentence (`:568`) flips to five"), and the Acceptance presence-check list ("the `§1.5` Tier-B row + count sentence").
- **Re-check**:
  - Track-file location: `track-1.md` step 3 (Plan of Work), conventions.md item (`## Interfaces and Dependencies`), Acceptance bullet 1 (`## Validation and Acceptance`).
  - Source state: live `conventions.md:568` still reads "four" (correct — live edits land in Phase B, not at gate-verify time); the source is unchanged and the track now schedules the flip.
  - Current state: all three roster/step/acceptance surfaces now call out `:568` explicitly; the gap is closed.
  - Criteria met: completeness of the flip roster (no §1.5 count word left at four-of-five); intra-file consistency the atomic-flip invariant requires.
- **Regression check**: checked whether the new `:568` flip interferes with the `:570` governance grep (the grep matches the four literal section-name strings, not the word "four"; `:568` carries no banned-section literal, so it is not a grep-match site — the 54-file count is undisturbed) and whether "four → five" at `:568` contradicts D1's "semantic, not numeric" rewording (`:568` says "section names", ban-neutral, so a plain numeric bump to "five" is correct and distinct from the 30-blurb "four banned-section slugs" reword). Clean.
- **Verdict**: VERIFIED

#### Verify T2: acceptance conflated the `ai-tells` catalogue with closed-set enumerations
- **Original issue**: `ai-tells/SKILL.md:19-27` is a fingerprint-category → house-style-section map, not a closed-set four-name subset enumeration; listing it under "every closed-set enumeration names `## Orientation`" conflated two site kinds.
- **Fix applied**: separated in three places — Acceptance bullet 1 ("The `ai-tells` catalogue is verified by a different shape — it gains a too-terse-fingerprint → `§ Orientation` row (not a four→five flip), because it is a fingerprint→section map, not a closed-set enumeration (T2/A4)"), Plan of Work step 4 ("gains a **too-terse-fingerprint → `§ Orientation` row** … so this is a new row, not a four→five flip (T2/A4)"), and the Interfaces ai-tells bullet ("catalogue gains a too-terse-fingerprint → `§ Orientation` row (`:19-27`; a new row, not a four→five flip, T2/A4)").
- **Re-check**:
  - Track-file location: Acceptance bullet 1, Plan of Work step 4, Interfaces ai-tells bullet.
  - Source state: live `ai-tells/SKILL.md:19-27` confirmed a fingerprint→section catalogue that also cites `§ Structural rules` and `§ Punctuation and typography` (outside the four-name subset) — never a closed-set enumeration.
  - Current state: the catalogue is now verified by its own row-presence shape, excluded from the closed-set-enumeration class. The acceptance assertion is honest about which sites carry a countable subset enumeration.
  - Criteria met: precise classification of flip sites vs reference catalogues; `review-workflow-consistency` acceptance bullet no longer over-claims.
- **Regression check**: checked that the acceptance bullet's closed-set presence-check list (28 single-line + 2 line-wrapped blurbs, 11 chat blurbs, the two narrow-grep-miss sites, the §1.5 Tier-B row + count sentence) no longer includes the `ai-tells` catalogue, and that step 4 still schedules an Orientation row for it. Consistent across all three surfaces. Clean.
- **Verdict**: VERIFIED

#### Verify T3: D2 code-comment restatement had no home in a table cell
- **Original issue**: §1.5 Tier-B membership is a Markdown table row whose "Sections that apply" cell is a comma-separated `§`-name list; D2's multi-sentence code-comment restatement ("no out-of-file assumptions; gloss the project-specific entity") cannot live in that cell, and the track's "to the `§1.5` Tier-B row" was underspecified for decomposition.
- **Fix applied**: the split was named in Plan of Work step 3 ("place the multi-sentence code-comment restatement … in §1.5 *prose adjacent to the table*, not inside the table cell (T3)") and the Interfaces conventions.md item ("the code-comment restatement lands in §1.5 prose adjacent to the table (not the cell, T3)").
- **Re-check**:
  - Track-file location: Plan of Work step 3, Interfaces conventions.md item.
  - Source state: live `conventions.md:561-566` confirmed the Tier-B row is a table cell holding a comma-separated `§`-name list (`:565`), with prose context immediately below at `:568`.
  - Current state: decomposition now places the restatement in §1.5 prose adjacent to the table and adds only `§ Orientation` to the cell's section list; the under-specification is resolved.
  - Criteria met: the step author has an unambiguous home for the multi-sentence prose; the table cell stays a flat `§`-name list.
- **Regression check**: checked D2's "**Implemented in**: this track (`§1.5` Tier-B row + the hook `tier_b_body`)" against the new "prose adjacent to the table" instruction — D2's pointer names the §1.5 region coarsely (which section), not a literal "inside the cell" claim, and the authoritative decomposition specs (step 3 + Interfaces item) carry the precise placement. No contradiction. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass; all three prior findings VERIFIED, no regression surfaced)

## Summary

PASS — T1, T2, T3 all VERIFIED in `track-1.md` (and the plan-level inventory in `implementation-plan.md` is consistent); no regression introduced by the fixes.
