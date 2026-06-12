<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Structural review — explanation-style (iter 1)

Plan-internal structural review (Phase 2, `reviewer-plan`). No codebase
read. `full`-tier, so the DESIGN DOCUMENT block ran. `design.md` is
frozen (Phase-1 freeze), so any design-side fix is recorded only and
deferred to the Phase-4 `design-final.md` reconciliation.

## Findings

### S1 [suggestion]
**Location**: `design.md` throughout (Overview line 34, Enforcement surface map lines 79/90/94, and §"Subset sync across ~50 sites" lines 249/251/273) vs. the plan/track files, which carry `~54` everywhere (plan lines 22, 50, 59, 69, 71, 73-75, 133, 140-141; track-1.md lines 5, 9, 27-30, 68, 75, 86, 121, 138).
**Issue**: The design document fixes the subset-enumeration site count at `~50`; the plan and both track files carry the refined `~54` (the inventory now itemizes 30 blurbs + 11 chat blurbs + 3 canonical + hook + 2 tests + 2 governance greps + `ai-tells` catalogue + the two narrow-grep-miss sites at `commit-conventions.md` / `implementer-rules.md`). This is a design↔plan numeric inconsistency: the live plan moved the count up after the design froze, and the design still reads `~50`. The plan is internally consistent at `~54`; the only stale figure is on the frozen design side. The two plan/track references to the frozen design section title — `design.md §"Subset sync across ~50 sites"` (plan line 77, track-1 line 32) — correctly retain `~50` because they cite the frozen heading verbatim; those are valid anchors, not part of this inconsistency.
**Proposed fix**: Recorded only — defer to Phase 4. When `design-final.md` is authored, update the design's site count from `~50` to `~54` (Overview line 34, Enforcement surface map prose + the `B50` node label, and the §"Subset sync" heading + body), and update any plan/track reference that cites the section title verbatim to track the renamed heading at that time. Do not edit the frozen `design.md` now.
**Classification**: design-decision
**Justification**: DESIGN DOCUMENT — "Is the design document consistent with the Architecture Notes (Component Map, Decision Records) and track descriptions in the implementation plan?"; design↔plan inconsistency whose only fix edits the design is a `design-decision` per §Classification rules ("Design document gaps").

## Evidence base

No certificates. This review reads no codebase; all findings are
plan-internal structural observations against the workflow rules in
`planning.md §Track descriptions` / §Per-section budget and
`conventions.md §1.2`.
