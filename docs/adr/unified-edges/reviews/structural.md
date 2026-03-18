# Structural Review

## Iteration 1

### Finding S1 [should-fix] → FIXED
**Location**: All track scope indicators
**Issue**: Scope indicators use "steps" instead of the canonical "leaves" terminology.
**Proposed fix**: Change "~N steps" to "~N leaves" in all track scope lines.
**Resolution**: Accepted — updated Tracks 1–5 scope indicators.

### Finding S2 [should-fix] → FIXED
**Location**: Track 4 description — `MultiCollectionIterator` update
**Issue**: Track 4 says to update `MultiCollectionIterator`'s `instanceof RelationsIteratorAbstract`
check, but `RelationsIteratorAbstract` is deleted in Track 2's atomic commit. The check must be
handled in Track 2 (same atomic commit), not Track 4.
**Proposed fix**: Add `MultiCollectionIterator` to Track 2's atomic deletion scope; remove from Track 4.
**Resolution**: Accepted — moved to Track 2's step 5 atomic commit.

### Finding S3 [should-fix] → FIXED
**Location**: Track 4 description — `GraphRepair` update
**Issue**: Track 4 says to update `GraphRepair` to remove `isStatefulEdge()` calls, but Track 1
already migrates all `isStatefulEdge()` call sites and deletes those methods. GraphRepair can't have
those calls after Track 1.
**Proposed fix**: Add `GraphRepair.java` to Track 1's call site migration scope; remove from Track 4.
**Resolution**: Accepted — GraphRepair explicitly listed in Track 1's description.

### Finding S4 [should-fix] → FIXED (by S2+S3)
**Location**: Track 4 scope indicator
**Issue**: Track 4 claims ~4 leaves but describes 6 distinct concerns.
**Proposed fix**: Adjust scope indicator upward.
**Resolution**: No direct change needed — after S2 and S3 removed MultiCollectionIterator and
GraphRepair from Track 4, the remaining 4 concerns match the ~4 leaves indicator.

### Finding S5 [blocker] → FIXED
**Location**: Track 2 and Track 4 — overlapping `isLightweight()` removal scope
**Issue**: Track 2 step 4 says "Remove `isLightweight()` call sites." Track 4 says "remove the
`isLightweight()` branch" in `EdgeIterator`. These overlap with no deconfliction.
**Proposed fix**: Clarify boundary — Track 2 covers API-level methods and direct call sites; Track 4
covers `EdgeIterator`'s internal branching logic.
**Resolution**: Accepted — Track 2 now explicitly states "EdgeIterator's internal lightweight/stateful
branching logic is simplified separately in Track 4." Track 4 adds a cross-reference note explaining
the boundary.

### Finding S6 [suggestion] → FIXED
**Location**: Decision Record D1 "Implemented in" line
**Issue**: "Implemented in: Tracks 1, 2, 3, 4" is too broad for useful traceability.
**Proposed fix**: Add per-track detail.
**Resolution**: Accepted — changed to "Track 1 (call site migration), Track 2 (type hierarchy
collapse), Track 3 (implementation unification and RidPair guard), Track 4 (schema and lifecycle
cleanup)".

### Finding S7 [suggestion] → REJECTED
**Location**: Track 5 Mermaid diagram arrow label
**Issue**: "change events" label could be more specific.
**Proposed fix**: Change to "notifies on add/remove".
**Resolution**: Rejected — "change events" is a standard pattern name in this codebase. More verbose
labeling adds no clarity.

### Finding S8 [should-fix] → FIXED
**Location**: Track 6 scope indicator
**Issue**: ~5 leaves seems inflated for verification + cleanup. Verification is implicit in running
the test suite, not a separate leaf.
**Proposed fix**: Reduce scope to ~3-4 leaves.
**Resolution**: Accepted — reduced to "~3-4 leaves covering API cleanup and DSL regeneration, test
file updates, full test suite run."

### Finding S9 [should-fix] → FIXED
**Location**: Top-level Component Map Mermaid diagram
**Issue**: Stray `end` keyword at end of `graph TD` block causes rendering error.
**Proposed fix**: Remove the `end` keyword.
**Resolution**: Accepted — removed.

### Finding S10 [should-fix] → REJECTED
**Location**: Track 5 dependency chain
**Issue**: Track 5 ("Index by vertex") doesn't technically depend on Track 4's schema/iteration
cleanup. Could run after Track 3 or independently.
**Proposed fix**: Change dependency to Track 3.
**Resolution**: Rejected — the serial ordering is an intentional code hygiene choice. Building new
index infrastructure atop a codebase still containing legacy lightweight edge paths in EdgeIterator
and schema would be messy. The dependency is reasonable.

### Finding S11 [suggestion] → FIXED
**Location**: Invariants section
**Issue**: "Histogram updates and index updates for 'index by vertex'" mentions histograms, which
are not part of this plan (copy-paste from index histogram feature).
**Proposed fix**: Remove "histogram" reference.
**Resolution**: Accepted — changed to "Index updates for 'index by vertex' must occur inside the
same WAL atomic operation as the LinkBag modification."

### Finding S12 [suggestion] → REJECTED
**Location**: Plan structure — no sub-areas used
**Issue**: Tracks 2 and 4 have internal sequencing that could benefit from sub-area decomposition.
**Proposed fix**: Add sub-areas to Tracks 2 and 4.
**Resolution**: Rejected — current track descriptions are detailed enough for execution. The approach
sections already describe ordered steps with constraints. Adding sub-areas would increase structural
overhead without proportional benefit.

## Iteration 2 (Gate Verification)

- S1: VERIFIED
- S2: VERIFIED
- S3: VERIFIED
- S4: VERIFIED (no action was needed)
- S5: VERIFIED
- S6: VERIFIED
- S7: REJECTED (no action needed)
- S8: VERIFIED
- S9: VERIFIED
- S10: REJECTED (no action needed)
- S11: VERIFIED
- S12: REJECTED (no action needed)
- No new findings.
- **Summary: PASS**
