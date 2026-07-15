# Track 1 Technical Review

## Iteration 1

### Finding T1 [should-fix] → ACCEPTED
**Location**: Track 1 scope (EdgeImpl deletion) + `EdgeIterator.java:71-87`,
`DatabaseSessionEmbedded.java:1070-1077,3408-3417`
**Issue**: The plan underspecifies the blast radius of EdgeImpl deletion. All
transitive callers of `newLightweightEdgeInternal` must be bridged:
`EdgeIterator.createBidirectionalLink()` vertex branch,
`DatabaseSessionEmbedded.newLightweightEdgeInternal()`,
`addEdgeInternal()` lightweight path, `VertexEntityImpl.addLightWeightEdge()`,
`SQLUpdateItem`, and transaction delegates.
**Proposed fix**: Step decomposition specifies: replace
`newLightweightEdgeInternal` body with throw (covers all transitive callers)
and replace EdgeIterator vertex branch with throw.

### Finding T2 [should-fix] → ACCEPTED
**Location**: `ResultInternal.java:297-303` (`convertPropertyValue`)
**Issue**: Uses `isEdge()` + `isStatefulEdge()` together in conditional
branching. After unification, the lightweight branch is dead — simplify to
always call `convertPropertyValue(result.asEdge())`.
**Proposed fix**: Included in call site migration step.

### Finding T3 [should-fix] → PARTIALLY ACCEPTED
**Location**: `ResultSet.java:353-406`
**Issue**: Beyond typo renames, `statefulEdgeStream()`, `forEachStatefulEdge()`,
`toStatefulEdgeList()` return `StatefulEdge` type; `edgeStream()` internally
calls `asStatefulEdge()`.
**Proposed fix**: Fix `edgeStream()` to call `asEdge()`. Rename typo methods.
`statefulEdgeStream()`/`forEachStatefulEdge()`/`toStatefulEdgeList()` return
`StatefulEdge` type — deferred to Track 2 when `StatefulEdge` is deleted.

### Finding T4 [should-fix] → ACCEPTED
**Location**: `ResultInternal.java:992-1003,1006-1025`
**Issue**: New standalone `isEdge()` must preserve `!cls.isAbstract()` check
from `isStatefulEdge()`.
**Proposed fix**: Documented in step decomposition.

### Finding T5 [suggestion] → NOTED
**Location**: `EntityImpl.java:257-269,272-274`
**Issue**: Confirmation that `EntityImpl.isEdge()` standalone approach is sound.
**Resolution**: No action needed.

### Finding T6 [should-fix] → ACCEPTED
**Location**: `RecordBytes.java:176,199,223`, `EmbeddedEntityImpl.java:60`
**Issue**: `RecordBytes.isStatefulEdge()` and `EmbeddedEntityImpl.isStatefulEdge()`
overrides become orphaned when interface method is removed.
**Proposed fix**: Included in dead method removal step. Per T9,
`asStatefulEdge()`/`asStatefulEdgeOrNull()` retained on `RecordBytes` until
Track 2.

### Finding T7 [should-fix] → ACCEPTED
**Location**: `GraphRepair.java:136,175,224,586-587`
**Issue**: `GraphRepair.java` uses `isStatefulEdge()` and
`asStatefulEdgeOrNull()` but was not listed in the explicit migration plan.
**Proposed fix**: Included in call site migration step.

### Finding T8 [suggestion] → NOTED
**Location**: `ResultInternal.java:125-131` (`toMapValue`)
**Issue**: `edge.isLightweight()` branch becomes dead code after EdgeImpl
deletion, persists until Track 2 removes `isLightweight()`.
**Resolution**: Intentional dead code window; acceptable between tracks.

### Finding T9 [blocker] → ACCEPTED
**Location**: Track 1 constraint — method removal scope
**Issue**: Removing `asStatefulEdge()`/`asStatefulEdgeOrNull()` from `Entity`
would break `Result.asStatefulEdge()` default method which calls
`asEntity().asStatefulEdge()`.
**Proposed fix**: Track 1 removes only `isStatefulEdge()`/`isStateful()` from
non-Edge interfaces. `asStatefulEdge()`/`asStatefulEdgeOrNull()` retained on
all interfaces (Entity, DBRecord, Result, Edge) until Track 2.

### Finding T10 [suggestion] → ACCEPTED
**Location**: `YTDBGraphTraversalDSL.java:60` (`addStateFullEdgeClass`)
**Issue**: Same "StateFull" typo as ResultSet methods.
**Proposed fix**: Included in typo rename step. Rename to `addEdgeClass()`.

### Finding T11 [suggestion] → NOTED
**Location**: `MatchStepUnitTest.java:3336`
**Issue**: Hand-written mock with `isStatefulEdge()` override must be deleted
alongside interface method removal.
**Resolution**: Implementation guidance noted.
