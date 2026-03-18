# Track 1 Technical Review — Iteration 1

## Review Scope
Track 1: Migrate call sites to unified edge API

## Findings

### Finding T1 [should-fix]
**Location**: Track 1 audit checklist — missing call sites
**Issue**: The audit checklist omits several call sites that also use `isStatefulEdge()`/`asStatefulEdge()`/`isStateful()`:
- `CastToEdgeStep.java` (lines 28-30): `isStatefulEdge()` + `asStatefulEdge()`
- `FetchEdgesToVerticesStep.java` (line 111-112): `isStateful()` + `asStatefulEdge()`
- `FetchEdgesFromToVerticesStep.java` (line 164-165): `isStateful()` + `asStatefulEdge()`
- `EdgeIterator.java` (lines 56-57, 88-90): `isStatefulEdge()` + `asStatefulEdge()`
- `RecordBytes.java` (lines 176, 199, 223): `isStatefulEdge()` + `asStatefulEdge()` + `asStatefulEdgeOrNull()`
- `SQLUpdateItem.java` (lines 265, 270): `isStateful()` + `asStatefulEdge()`
- `ResultInternal.toMapValue()` (line 129): `asStatefulEdge().getIdentity()`
**Proposed fix**: Include these in the migration. EdgeIterator changes are deferred to Track 4 (iteration simplification). RecordBytes implementations just return false/throw — they need method signature changes when old methods are deleted (Step 3). The rest are call site migrations for Step 1.

### Finding T2 [suggestion]
**Location**: Plan constraint about `EntityImpl.isEdge()` needing `!cls.isAbstract()` guard
**Issue**: The plan says `EntityImpl.isEdge()` needs a `!cls.isAbstract()` guard, but the current `EntityImpl.isStatefulEdge()` does NOT have this guard (it only checks `type.isEdgeType()`). Only `ResultInternal.isStatefulEdge()` has the `!cls.isAbstract()` check. Since `EntityImpl` represents actual record instances (not RID-only references), the abstract check is unnecessary at this level — an EntityImpl of an abstract class shouldn't exist at runtime.
**Proposed fix**: `EntityImpl.isEdge()` should replicate the exact logic of current `isStatefulEdge()` (no abstract guard). The `!cls.isAbstract()` guard is only needed in `ResultInternal.isEdge()` where the identifiable may be an unloaded RID.

### Finding T3 [suggestion]
**Location**: `ResultInternal.toMapValue()` static method (line 125-129)
**Issue**: The `toMapValue()` method has an `Edge` case that calls `edge.isLightweight()` and `edge.asStatefulEdge().getIdentity()`. This is a call site that should be migrated. After unification, all edges have identity, so the lightweight/stateful branching can be simplified.
**Proposed fix**: Migrate in Step 1. After unification the Edge case can just call `edge.getIdentity()` (all edges are stateful), but for now during Step 1, change to use `asEdge()` / unified path since `isLightweight()` removal is Track 2.

### Finding T4 [suggestion]
**Location**: `ResultInternal.convertPropertyValue()` — `Result` case (lines 296-303)
**Issue**: This code checks `result.isStatefulEdge()` and calls `result.asStatefulEdge()`. Should be migrated.
**Proposed fix**: Migrate to use `isEdge()` / `asEdge()` in Step 1.

## Verdict
**Pass** — No blockers found. The track's approach is sound and the code structure matches expectations. The additional call sites (Finding T1) must be included in the migration steps but don't change the overall approach.
