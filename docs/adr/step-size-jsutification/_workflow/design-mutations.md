# Design Mutations Log

Append-only record of every `design.md` mutation run through the
`edit-design` discipline. Not stamped (per `conventions.md §1.6(f)`).

## Mutation 1 — 2026-06-05 — content-edit (design.md)

**Diff summary**: Revised the closed reason set in §"The under-fill justification clause" from four entries to two. Folded "remaining work is all `high`" and "end of track" together with a new packing case (the only remaining low/medium unit would trip the ~14 overblown line if merged) into a single first reason, "No mergeable low/medium work fits"; kept "Heavy-iteration carve-out" as the second. Removed "an inter-step dependency forces sequencing" as a valid reason — interdependent low/medium steps are merged into one with the dependency becoming intra-step ordering, so a dependency never forces a step to stay small — and added it to the explicitly-excluded list alongside "unrelated." Updated the roster-line example reason accordingly, and added a §"Decomposition decision model" edge-case bullet covering the packing case and noting the "merge more" loop does not apply there.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS

**Findings**:
- none

**Iterations**: 1 of 3 (PASS)
