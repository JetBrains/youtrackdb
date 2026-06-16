<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: WB1, sev: suggestion, loc: "prompts/structural-review.md:341", anchor: "### WB1 ", cert: n/a, basis: "structural-review bloat checks now do N targeted per-track-file section reads where develop read one plan section; bounded, targeted, net-favorable (bytes move off the always-loaded plan)"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### WB1 [suggestion] structural-review bloat/presence checks shift from one plan-section read to N per-track section reads

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (lines 304-399, delta-confirmed at patch lines 341-399 and 882-951)
- **Axis:** instant per-operation consumption
- **Cost:** per Phase-2 / State-0 structural pass: was one read of the plan's Architecture Notes section; is now one targeted read of each pending track's `## Decision Log`, `## Invariants & Constraints`, and `## Interfaces and Dependencies` (the DR / invariant / integration-point / superseded-DR length and presence checks). Bounded by track count, sub-5K tokens in any realistic plan.
- **Issue:** D7/D9 moved Decision Records, Invariants, and Integration Points off the always-loaded plan into the track files, so the structural reviewer that used to read one plan section now reads the named section in each pending track file. This is not a defect — the *total* bytes are conserved and the move is net-favorable (it pulls this content out of the plan file that loads at every `/execute-tracks` startup and into on-demand reads at Phase A/B/C, exactly as the prompt's own BLOAT preamble states). The only budget risk is if a future edit lets these become whole-track-file reads instead of section-scoped reads.
- **Suggestion:** none required for this track — the prompt already tags every relocated check `*(track-file: each pending track's ## <Section> ...)*`, which scopes the read to the named `## ` section rather than the whole track file, and the BLOAT preamble already records that the budget moved from always-loaded to on-demand. Keep that section-scoped framing on any future edit (read the named `## ` section per pending track, not the whole track file) so the per-operation peak stays proportional to the checked sections, not the full track bodies.

## Evidence base
