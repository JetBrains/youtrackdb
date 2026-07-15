# Track 4 Technical Review

## Summary

Track 4 is technically feasible. Two claimed blockers (T1, T8) are dismissed
after codebase verification. Four should-fix findings inform step decomposition.

## Findings

### Finding T1 [dismissed — not a blocker]
**Location**: RidPair guard design
**Issue**: Reviewer claimed Java records can't have custom constructors.
**Resolution**: Java records support compact constructors. The guard
(`if (primaryRid.equals(secondaryRid)) throw ...`) works in a compact
constructor. Step ordering (delete `ofSingle()` and all lightweight creation
paths before adding guard) prevents false positives.

### Finding T2 [should-fix]
**Location**: `Vertex.java` — 7 edge creation methods
**Issue**: `Vertex` already has `addEdge(Vertex, String)` and
`addEdge(Vertex, SchemaClass)`. The plan says "collapse into single
`addEdge()`" but doesn't specify merge strategy.
**Resolution**: Delete `addStateFulEdge` (3 variants) and `addLightWeightEdge`
(2 variants). Add `addEdge(Vertex to)` (no-label, renamed from
`addStateFulEdge(Vertex to)`). Result: 3 `addEdge()` overloads.

### Finding T3 [should-fix]
**Location**: `VertexEntityImpl.createLink()` 4-param overload
**Issue**: 4-param creates `RidPair(to, to)` which would violate the guard.
**Resolution**: Delete 4-param overload when deleting `addLightWeightEdge()`,
before adding the RidPair guard. The 4-param is only called from lightweight
paths.

### Finding T4 [should-fix]
**Location**: `DatabaseSessionEmbedded.newLightweightEdge()` deletion order
**Issue**: Call sites in `VertexEntityImpl.addLightWeightEdge()` must be
removed before `newLightweightEdge()` is deleted.
**Resolution**: Step ordering: Vertex API unification → DatabaseSession
cleanup.

### Finding T5 [suggestion — accepted]
**Location**: Step count estimate
**Issue**: ~5 steps seems right; `createLink()` decision needs clarification.
**Resolution**: `createLink()` 4-param deletion is bundled with Vertex API
unification step.

### Finding T6 [should-fix]
**Location**: `RidPair.ofSingle()` and test cleanup
**Issue**: `VertexFromLinkBagIteratorTest.testOfSingleCreatesLightweightPair()`
must be updated.
**Resolution**: Test updated to verify the guard throws. Bundled with RidPair
guard step.

### Finding T7 [suggestion — accepted]
**Location**: `YTDBStatefulEdgeImpl` rename scope
**Issue**: 6+ call sites across Gremlin layer must be updated.
**Resolution**: Included in rename step scope.

### Finding T8 [dismissed — not a blocker]
**Location**: `YTDBStatefulEdge` public API interface
**Issue**: Reviewer claimed mismatch between renamed impl and existing interface.
**Resolution**: Track 4 renames implementation only. The interface
`YTDBStatefulEdge` is public API, renamed in Track 7 (API cleanup). The
renamed impl still implements the interface correctly.

## Gate: PASS (0 blockers after assessment)
