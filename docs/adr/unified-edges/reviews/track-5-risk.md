# Track 5 Risk Review

## Summary

Track 5 has low critical-path exposure — primarily dead code removal and
simplification. The major risk is the `EdgeType` enum / SQL navigation
interaction (R7/blocker), which will break V2V traversal if not addressed.
All changes are deletions/simplifications with no serialization format
changes, giving a clean rollback story.

## Findings

### Finding R1 [should-fix]
**Location**: `EdgeIterator.java` (lines 107-139)
**Issue**: Plan describes iteration unification work that's already done.
The vertex-detection guard (lines 125-132) is a correct safety mechanism
that should be retained.
**Proposed fix**: Clarify that `EdgeIterator` needs only comment updates,
not structural changes. Keep the vertex-detection guard.

### Finding R2 [should-fix]
**Location**: `SchemaClassEmbedded.setAbstractInternal()` (lines 534-586)
**Issue**: No edge-specific enforcement exists in `setAbstract()`. The plan
asserts a constraint that was never implemented.
**Proposed fix**: Remove from scope.

### Finding R3 [should-fix]
**Location**: `SQLGraphNavigationFunction.java`, `VertexEntityImpl.EdgeType`
**Issue**: Unmentioned `EdgeType` enum and its callers need cleanup. Same
root cause as R7.
**Proposed fix**: Include explicit step to rework EdgeType filtering.

### Finding R4 [should-fix]
**Location**: `SQLOptimizeDatabaseStatement.optimizeEdges()` (lines 77-155)
**Issue**: `OPTIMIZE DATABASE LWEDGES` would destroy valid edge records after
unification. Data corruption risk.
**Proposed fix**: Remove or throw `UnsupportedOperationException`.

### Finding R5 [suggestion]
**Location**: Multiple files
**Issue**: Stale "lightweight" comments in `PropertyLinkBagIndexDefinition`,
`VertexEntityImpl`, `VertexFromLinkBagIterator`, `SQLGraphNavigationFunction`.
**Proposed fix**: Include comment cleanup pass in relevant steps.

### Finding R6 [suggestion]
**Location**: Test files
**Issue**: `LightWeightEdgesTest` and `CreateLightWeightEdgesSQLTest` use
`createEdgeClass()` not `createLightweightEdgeClass()` — names misleading.
**Proposed fix**: Rename during schema cleanup or defer to Track 7.

### Finding R7 [blocker]
**Location**: `SQLGraphNavigationFunction.propertiesForV2VNavigation()` line 133
**Issue**: Uses `EdgeType.LIGHTWEIGHT` which filters to abstract-only edge
classes. After `createLightweightEdgeClass()` removal, no edge classes will
be abstract, so V2V navigation will always return empty property names —
breaking SQL `out()`/`in()`/`both()` vertex-to-vertex traversal. This is
a performance regression and potential correctness bug on a hot path.
**Proposed fix**: Remove EdgeType filtering entirely — all edges are now
record-based. Both V2E and V2V should use the same property names.

### Finding R8 [suggestion]
**Location**: Rollback story
**Issue**: All changes are deletions/simplifications with no on-disk format
changes. Each step can be independently reverted.
**Proposed fix**: No action needed.
