# Track 5 Technical Review

## Summary

Track 5's two primary targets (EdgeIterator branching, edge deletion
simplification) are misidentified — those components were already unified
by Tracks 1-3. The actual remaining cleanup targets are
`VertexEntityImpl.EdgeType` enum, `SQLGraphNavigationFunction`,
`SQLOptimizeDatabaseStatement.optimizeEdges()`, and `GraphRepair`.
The `createLightweightEdgeClass()` removal is straightforward and
correctly identified.

## Findings

### Finding T5-1 [should-fix]
**Location**: Track 5 description, "Remove the `setAbstract()` enforcement"
**Issue**: No `setAbstract()` enforcement exists that prevents concrete edge
classes. `SchemaClassEmbedded.setAbstractInternal()` is completely generic
with no edge-class-specific guard.
**Proposed fix**: Remove from Track 5 scope — it's a no-op.

### Finding T5-2 [blocker]
**Location**: Track 5, "Unify edge iteration in `EdgeIterator`"
**Issue**: `EdgeIterator` has no lightweight/stateful branching — already
unified by Track 3. The real targets are `VertexEntityImpl.EdgeType` enum
(LIGHTWEIGHT, STATEFUL, BOTH) and `SQLGraphNavigationFunction` which uses
`EdgeType.LIGHTWEIGHT` for V2V navigation and `EdgeType.STATEFUL` for V2E.
`getAllPossibleEdgePropertyNames()` filters classes by `isAbstract()` status
based on EdgeType.
**Proposed fix**: Delete `EdgeType` enum, simplify
`getAllPossibleEdgePropertyNames()`, update SQL navigation callers.

### Finding T5-3 [should-fix]
**Location**: Track 5, "Simplify edge deletion"
**Issue**: No lightweight-specific deletion paths exist in `VertexEntityImpl`.
Deletion is already unified.
**Proposed fix**: Remove from scope. Replace with `GraphRepair.java`
lightweight repair path cleanup and `SQLOptimizeDatabaseStatement` removal.

### Finding T5-4 [should-fix]
**Location**: `SQLOptimizeDatabaseStatement.optimizeEdges()` (lines 77-155)
**Issue**: `OPTIMIZE DATABASE LWEDGES` converts stateful edges to lightweight
by replacing edge RIDs with vertex RIDs and deleting edge records. After
unification, this creates the exact state `RidPair.validateEdgePair()` rejects.
**Proposed fix**: Make `optimizeEdges()` throw `UnsupportedOperationException`.

### Finding T5-5 [should-fix]
**Location**: `VertexEntityImpl.createLink()` line 682
**Issue**: Single-arg `bag.add(foundId.getIdentity())` creates
`RidPair(rid, rid)` — a lightweight-style pair that would fail
`validateEdgePair()`.
**Proposed fix**: Determine correct secondary RID for the pre-existing entry.

### Finding T5-6 [should-fix]
**Location**: `GraphRepair.java` line 545-546
**Issue**: Lightweight edge repair path ("VERTEX -> LIGHTWEIGHT EDGE") handles
records loaded from a LinkBag that turn out to be vertices. After unification,
this represents corruption, not a valid lightweight edge.
**Proposed fix**: Remove or replace with corruption detection.

### Finding T5-7 [suggestion]
**Location**: Comments in `PropertyLinkBagIndexDefinition`, `SQLGraphNavigationFunction`
**Issue**: Stale lightweight edge references in comments.
**Proposed fix**: Update comments during relevant steps.

### Finding T5-8 [suggestion]
**Location**: `LightWeightEdgesTest.java`, `CreateLightWeightEdgesSQLTest.java`
**Issue**: Both tests use `createEdgeClass()` not `createLightweightEdgeClass()`.
Names are misleading. Consider renaming rather than deferring deletion to Track 7.
**Proposed fix**: Rename during schema cleanup step.

### Finding T5-9 [suggestion]
**Location**: `Schema.createEdgeClass()` Javadoc
**Issue**: "Creates a non-abstract new edge class" — "non-abstract" qualifier
only meaningful in contrast with removed `createLightweightEdgeClass()`.
**Proposed fix**: Update Javadoc to remove "non-abstract" qualifier.
