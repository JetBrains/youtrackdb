# Design Mutations Log — Unit Test Coverage

This log was re-created during Phase 2 (autonomous plan review) on
2026-05-09. The original log (if any) was lost in the 2026-05-04
`git clean -fd` incident along with the `reviews/` directory and
other ephemeral working files (see implementation-plan.md §
Operational Notes for the full incident inventory). The design.md
itself was fully recovered to 336 lines with no gaps via agent
transcripts; this log starts fresh from the first post-recovery
mutation.

## Mutation 1 — 2026-05-09 — content-edit (design.md)

**Diff summary**: Phase 2 autonomous plan-review sync after the
inline replan that split the original Track 22 into 22a / 22b / 22c
and amended the headline coverage Goal. Three coordinated edits to
`design.md` propagate the post-replan numbers and labels:
(1) Overview paragraph 1 — amended headline target from
`85% line / 70% branch` to `~82–83% line / ~70–71% branch` with
inline parenthetical pointing at plan §Goals; (2) Overview
numbered item 2 — updated track-count summary from `22 tracks total`
to `24 tracks total` reflecting the 22a/22b/22c split; (3) Class
Design "Used by" annotations — three sites rewritten from
`Track 22 (...)` to `Track 22a (...)` since 22a is the only
sub-track that authors test classes (22b is deletion-only, 22c is
YT-issue + marker rewrite); plus the Workflow → Coverage Measurement
mermaid loop predicate — rewrote `Aggregate ≥ 85% / 70%?` to
`Aggregate coverage gate met? (see plan §Goals)` so the threshold
is delegated to plan §Goals and won't desync on future amendments.

No sections moved, renamed, added, or removed.

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: bounded — Overview + Class Design + Workflow → Coverage Measurement and Progress Tracking): PASS — 0 findings.

**Findings**: none.

**Iterations**: 1 of 3 (PASS).

## Mutation 2 — 2026-05-12 — phase4-creation (design-final.md)

**Diff summary**: Phase 4 production of `design-final.md` as the
durable post-implementation design artifact. New file at
`docs/adr/unit-test-coverage/design-final.md` (top-level, outside
`_workflow/`). Content reflects what was actually built, not what
was planned: aggregate end-state of 81.4% line / 71.1% branch
across 173 packages (from 63.6% / 53.3% / 177 packages baseline),
6,196 production lines deleted in lockstep with their pins,
~35 `*DeadCodeTest.java` shape-pin files on disk, 115
`@Category(SequentialTest)`-annotated test files, 69 YouTrack
issues opened (`YTDB-723..793` with gaps), all `WHEN-FIXED: Track
NN` placeholders rewritten to `YTDB-NNN`.

Eleven `##` sections: Overview, Class Design, Workflow (with three
sub-sections), Coverage Analyzer Script, Test Parallelism
Constraints, Testing Serialization Round-Trips, Testing Storage &
Cache Components, Testing Concurrency Primitives, Testing SQL
Operators, Dead-Code Pinning Convention, WHEN-FIXED Marker
Convention. Three Mermaid diagrams (one classDiagram, two
flowcharts in the Workflow section's first two sub-sections, one
sequenceDiagram in the third). No `design-mechanics-final.md`
companion — the original `design.md` had no mechanics companion,
and the final artifact stays single-file.

**Mechanical checks** (target=design): PASS — 0 findings (after
one iteration trimming `## Overview` from 56 → 40 lines).
**Cold-read** (scope: whole-doc): PASS — 0 blockers, 0 should-fix,
~3 suggestions. Suggestions: (1) Overview's section-enumeration
prose order vs. doc `##` order is not 1:1 (navigability nit);
(2) the achieved-vs-target deviation (0.6 pp below lower line
bound, exceeded branch target) is named in Workflow but not in
the Overview; (3) "falsifiable-regression convention" is used in
Overview before being defined in the WHEN-FIXED Marker Convention
section. All three are recorded but not retried per the
suggestion-tier protocol.

Phase 4 self-standing check: PASS on all three sub-checks —
diagrams implementation-grounded, no leaked working-file
identifiers (the one `Track 22` historical-context mention in the
WHEN-FIXED Marker Convention section quotes the placeholder
syntax that was rewritten, which is load-bearing documentation,
not a live working-file identifier), and YouTrack issue range is
in the allowed-identifiers list.

**Findings**: three suggestion-tier cold-read items recorded
above; not retried.

**Iterations**: 2 of 3 (PASS — iter-1 produced an Overview-length
should-fix that auto-trimmed in iter-2).
