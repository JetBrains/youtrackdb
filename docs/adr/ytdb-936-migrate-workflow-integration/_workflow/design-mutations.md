# Design mutation log

## Mutation 1 — 2026-05-21 — content-edit (design.md)

**Diff summary**: Reword the `cd ../develop && /migrate-workflow <branch>` instruction in the Three-resolution-gate Edge cases entry and the Session-end residue section to mark `../develop` as a convention rather than a tool contract — the `migrate-workflow` skill enforces "user is on the develop branch in some worktree", not a sibling-directory layout. Same mutation rephrases the TL;DR opening lines of `## Three-resolution gate` and `## Session-end residue` to drop content-word overlap with the headings (fragmented-header lint, both pre-existing from Phase 1).

**Mechanical checks** (target=design): PASS — 0 findings on re-run after the TL;DR rephrases (initial run surfaced 2 pre-existing should-fix fragmented-header findings on lines 70 and 131, both resolved in the same iteration).
**Cold-read** (scope: bounded): PASS — 0 findings. Cold reader confirms the dropped numeral "three" in the TL;DR is recovered by the heading and the enumerated option list; the conversation-memory contract in Session-end residue survives the rewrite; the "user does not invoke the skill in-place" intent is preserved at the Edge cases site.

**Findings**: none.

**Iterations**: 2 of 3 (PASS).
