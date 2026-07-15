# Track 4 Risk Review

## Summary

Track 4 has manageable risk. The primary concern is step ordering to avoid
breaking compilation. RidPair guard is on a hot path but adds negligible
overhead (single `equals()` check). No blockers after assessment.

## Findings

### Finding R1 [should-fix — addressed]
**Location**: RidPair compact constructor guard
**Issue**: Implementation feasibility of guard in record syntax.
**Resolution**: Java records support compact constructors with validation.
Confirmed feasible.

### Finding R2 [should-fix — deferred to Track 5]
**Location**: `SQLGraphNavigationFunction.propertiesForV2VNavigation()`
**Issue**: Uses `EdgeType.LIGHTWEIGHT` which returns empty after unification,
potentially breaking SQL navigation optimization.
**Resolution**: Track 4 does not modify EdgeType or schema logic. This is
Track 5's scope (schema cleanup). Track 4 preserves existing behavior.

### Finding R3 [should-fix — addressed via step ordering]
**Location**: `newLightweightEdge()` removal sequence
**Issue**: Call sites in VertexEntityImpl must be removed first.
**Resolution**: Step ordering: Vertex API unification → DatabaseSession cleanup.

### Finding R4 [should-fix — addressed]
**Location**: `createLink()` 4-param overload cleanup
**Issue**: Dead 4-param method after lightweight removal.
**Resolution**: Deleted in Vertex API unification step, before RidPair guard.

### Finding R5 [should-fix — addressed]
**Location**: RidPair guard exception propagation
**Issue**: Guard throws in LinkBag iteration; exception must propagate cleanly.
**Resolution**: `IllegalStateException` is unchecked, propagates naturally
through iteration. Explicit test added for guard behavior.

### Finding R6 [suggestion — accepted]
**Location**: `ofSingle()` factory removal
**Issue**: Only test usage remains.
**Resolution**: Bundled with RidPair guard step.

### Finding R7 [should-fix — addressed]
**Location**: Coverage verification
**Issue**: Renamed code may appear covered but new guard logic needs explicit
tests.
**Resolution**: Coverage gate script handles this. RidPair guard step includes
explicit test for the throw path.

## Gate: PASS (0 blockers)
