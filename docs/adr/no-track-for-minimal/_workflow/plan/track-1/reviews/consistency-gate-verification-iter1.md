<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: CR1, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

(none)

## Verification certificates

#### Verify CR1: Track 2 reader inventory missed two §1.7(b)-marker consumers
- **Original issue**: Track 2's reader inventory omitted `prompts/dimensional-review-gate-check.md` and `prompts/review-gate-verification.md`. Both carry the standalone §1.7(b) staged-read block that reads the workflow-modifying marker from the plan's `### Constraints`. After D4 (marker moves to the ledger) and D5 (`### Constraints` removed from the thinned plan), those two prompts would still point their reader at a location the marker no longer occupies, silently reading a stale or absent fact.
- **Fix applied**: four additions to `track-2.md` — (1) the two prompts appended to the D4 Risks/Caveats reader inventory; (2) added to Plan-of-Work step 2's marker re-point; (3) inserted into the `## Interfaces and Dependencies` in-scope list after `adversarial-review.md`; (4) a new `## Context and Orientation` consumer bullet describing both as carrying the standalone §1.7(b) staged-read block with the marker read re-pointed to the ledger.
- **Re-check**:
  - Search/trace performed: grep over the live `.claude/**` workflow tree (`grep -rl 'carries the canonical'` for marker readers; `grep -rlE '1\.7\(b\)|### Constraints'` for the broader marker surface) plus per-location presence greps inside `track-2.md`. No staged mirror exists (`ls .../staged-workflow` empty), so the live files are authoritative. This is bash/markdown machinery with no Java symbols, so PSI does not apply and grep is exact for the bullet/heading targets — no reference-accuracy caveat attaches.
  - Code location: `docs/adr/no-track-for-minimal/_workflow/plan/track-2.md` — D4 inventory lines 49-50; Plan-of-Work step 2 lines 162-165; in-scope list lines 230-231; Context enumeration lines 136-140. Both `dimensional-review-gate-check` and `review-gate-verification` return a presence count of 1 in each of the four locations.
  - Current state: the two prompts each carry the identical standalone §1.7(b) staged-read block (`dimensional-review-gate-check.md:40`, `review-gate-verification.md:41`), confirming they are genuine marker readers. The exhaustiveness scan returns exactly nine `'carries the canonical'` marker-readers — `consistency-review`, `technical/risk/adversarial-review`, `step-implementation`, `track-code-review`, `structural-review`, plus the two now-added `dimensional-review-gate-check` and `review-gate-verification` — and Track 2's inventory now names all of them (with `implementer-rules`, surfaced by the broader `### Constraints` scan, also already in scope). No marker reader remains outside Track 2's inventory.
- **Regression check**: the fix added only list/bullet entries; it introduced no contradiction. The D4 inventory, the in-scope file list, and the Context enumeration now name the same set of nine marker-reader consumers consistently; no duplicate entries. The remaining `### Constraints` hits in `conventions.md` (the §1.7 marker-home definition) and `create-plan/SKILL.md:789` (the plan-template emitter) are Track-1 definition/authoring concerns, correctly out of Track 2's reader scope. `track-1.md` is undisturbed (git shows only `track-2.md` modified): it still owns the conventions §1.7 marker-home definition while Track 2 re-points readers, so the definition-vs-reader division holds and the two added files are correctly Track-2 reader concerns. Checked the in-scope list, D4 inventory, Context enumeration, track-1.md ownership, and the unbumped `~13 files` Scope line (ratified user decision, not a finding) — all clean.
- **Verdict**: VERIFIED
