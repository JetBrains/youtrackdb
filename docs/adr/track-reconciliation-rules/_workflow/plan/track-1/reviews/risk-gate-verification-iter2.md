<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: R1, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Risk gate-verification — iteration 2

#### Verify R1: restate-set grep scoped to a single file misses a second Phase-C-loading file's standalone cap-3 assertion
- **Original issue**: The restate set was scoped to `track-code-review.md` alone, but `code-review-protocol.md:53` (`Max 3 iterations per level.`) is a second Phase-C-loading file carrying a standalone cap-3 assertion the new uncapped track-level policy contradicts, with no carve-out pointer — shipping self-contradictory live text (the failure mode D2.1 guards against, left unguarded for this second file).
- **Fix applied**: The track broadened from one file to the Phase-C-loading file set:
  - `## Context and Orientation` now inventories `code-review-protocol.md` and distinguishes the standalone preamble (≈53) from the §Iteration-protocol pointer (≈97) that inherits the §Limits carve-out.
  - `## Plan of Work` added edit 7 (restate the standalone cap-3 assertion at `code-review-protocol.md:53`, defer per-level to each level's protocol) plus a "Restate authority is the Phase-C-loading file set, not one file" note with a tree-wide grep over six files and a triage rule.
  - `## Interfaces and Dependencies` in-scope set now lists `code-review-protocol.md` (and `design-decision-escalation.md`).
  - `## Validation and Acceptance` + `## Invariants & Constraints` broadened the "no cap-3-keyed site describing the Phase-C track-level loop" criterion to the Phase-C-loading file set.
- **Re-check**:
  - Track-file locations: §Context and Orientation lines 231-237; §Plan of Work edit 7 (335-343) + restate-authority note (352-370); §Interfaces in-scope (421-426); §Validation (387-394); §Invariants (458-465).
  - Live-file confirmation: `code-review-protocol.md:53` carries the standalone `Max 3 iterations per level.` preamble that does NOT defer to `review-iteration.md`; `:97` is the §Iteration-protocol pointer that DOES defer — the track describes both exactly. The tree-wide grep in edit's restate-authority note reproduces against the live files.
  - Criteria met: the second Phase-C-loading home now carries a restate (edit 7) and is named in the acceptance/invariant set; D2.1's "announce the override at every canonical home" principle is extended to it. R1's exact contradiction is closed.
- **Regression check**: Checked whether broadening pulled in step-level / Phase-2 / Phase-3A cap-3 assertions that must stay live.
  - `design-decision-escalation.md:60` (step-level `up to 3 per step`) is a distinct line from `:62` (Phase-C); the track scopes edit 8 to `:62` only and explicitly preserves `:60` (track lines 242, 350). Clean.
  - `review-iteration.md` §Limits keeps cap-3-then-escalate as the stated default for Phases 2 / 3A / 3B and adds only a Phase-C carve-out (edit 6). Clean.
  - `code-review-protocol.md:53` preamble covers both levels; edit 7's restate keeps step-level + Phase-2/3A on `review-iteration.md` §Limits cap-3 while uncapping only the Phase-C track-level loop. Clean.
  - The borderline `finding-synthesis-recipe.md:414` `(2 of 3 used)` is correctly flagged by the restate-authority note as shared step-level pacing guidance, reworded only if it reads as a Phase-C bound — it reads as generic split-pacing, so leaving it is sound. Clean.
  - Out-of-scope list (lines 428-433) and acceptance/invariants explicitly hold step-level / Phase-2 / Phase-3A cap-3 live. No over-broadening.
- **Verdict**: VERIFIED

## Findings

(none)

## Summary

PASS — R1 VERIFIED, 0 new findings. The fix extends the restate scope to the Phase-C-loading file set, closing the second standalone cap-3 home (`code-review-protocol.md:53`) without uncapping any step-level, Phase-2, or Phase-3A assertion that must stay live.
