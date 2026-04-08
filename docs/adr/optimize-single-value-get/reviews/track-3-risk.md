# Track 3 Risk Review

## Finding R1 [should-fix]
**Location**: Track 3 engine wiring, `BTree.buildSearchKey()`, `IndexMultiValuKeySerializer.serialize()`
**Issue**: When `get(null)` is called on a composite UNIQUE index (e.g., 2-field on `(name, surname)`), `convertToCompositeKey(null)` → `CompositeKey(null)` (1 element). `buildSearchKey()` pads to `CompositeKey(null, Long.MIN_VALUE, Long.MIN_VALUE)`. The serializer tries `serializeKeyToByteBuffer(buffer, STRING, Long.MIN_VALUE)` for the surname slot → `ClassCastException`. Test `SnapshotIsolationIndexesNullKeyTest.unique_compositeIndex_nullFieldsAreNotNullKey()` exercises this path. Likelihood: high. Impact: high.
**Proposed fix**: Guard `getVisible()` behind `key != null`. Keep old `iterateEntriesBetween` + `visibilityFilter` + null-key-filter path for `key == null`. This is safe since `get(null)` returns at most 1 entry — stream overhead is negligible.

## Finding R2 [should-fix]
**Location**: Track 3 engine wiring, `V1IndexEngine.get()` return type
**Issue**: Current `get()` returns a lazy stream backed by BTree cursor. New implementation returns `Stream.of(rid)`/`Stream.empty()` (stateless). Need to verify no caller depends on lazy/closeable stream semantics. Likelihood: low. Impact: medium.
**Proposed fix**: Grep all callers of `V1IndexEngine.get()` and `doGetIndexValues` before implementing to confirm none depend on lazy stream or close behavior.

## Finding R3 [suggestion]
**Location**: Track 3 test coverage
**Issue**: Test scope overlaps with Track 2. Main gap is integration-level testing and `SnapshotIsolationIndexesNullKeyTest` coverage.
**Proposed fix**: Focus Track 3 tests on engine-level wiring, explicitly run null-key test class.

## Finding R4 [suggestion]
**Location**: Track 3 scope, `remove()` and `put()` methods
**Issue**: These also use `iterateEntriesBetween` + `findAny()` with same overhead. Out of scope per plan's non-goals.
**Proposed fix**: No action. Note for future optimization.

## Finding R5 [suggestion]
**Location**: Track 3 performance, `BTree.getVisible()` lambdas
**Issue**: Two capturing lambdas for `executeOptimisticStorageRead()` are unavoidable and match existing `get()` pattern. Not a regression.
**Proposed fix**: Acknowledge in plan that these match the baseline.
