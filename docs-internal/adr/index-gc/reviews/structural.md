# Structural Review — Index BTree Tombstone GC

## Iteration 1

### Finding S1 [should-fix] — RESOLVED
**Location**: Track 1, Scope line
**Issue**: "formatting" is not a meaningful scope work item.
**Fix applied**: Changed to `~3-4 steps covering BTree GC filtering methods, put() loop integration, and AbstractStorage snapshot query helper`.

### Finding S2 [should-fix] — RESOLVED
**Location**: Track 2, Scope line
**Issue**: 7 test scenarios in 3-4 steps needed clearer bundling signal.
**Fix applied**: Changed to `~3-4 steps covering test infrastructure setup, tombstone removal/preservation tests, marker demotion/snapshot preservation tests, and tree size/edge case tests`.

### Finding S3 [suggestion] — RESOLVED
**Location**: Architecture Notes, D2
**Issue**: Missing demotion-only rebuild cost note in Risks/Caveats.
**Fix applied**: Added sentence about demotion-only page write before inevitable split.

### Finding S4 [suggestion] — RESOLVED
**Location**: Design document, Snapshot Query section
**Issue**: indexId resolved from engineName not explicitly stated.
**Fix applied**: Added note that indexId is resolved internally via indexEngineNameMap.

### Finding S5 [suggestion] — RESOLVED
**Location**: Track 3 description
**Issue**: Missing clarification on storage type for durability test.
**Fix applied**: Added constraint note about in-memory vs disk storage.

### Finding S6 [should-fix] — RESOLVED
**Location**: Decision Records D3, D4, D5
**Issue**: Missing references to validation tracks (Track 2, Track 3).
**Fix applied**: Added `**Validated in**: Track 2, Track 3` to D3, D4, D5.

### Finding S7 [suggestion] — RESOLVED
**Location**: Design document, Tree Size Accounting
**Issue**: Replacement rows document existing behavior but not labeled as such.
**Fix applied**: Added footnote clarifying unchanged behavior rows.

## Result: PASS
No blockers. All findings resolved.
