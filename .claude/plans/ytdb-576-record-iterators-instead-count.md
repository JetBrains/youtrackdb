# YTDB-576: Replace O(n) Record Count with Iterators / Approximate Count

## Context

`PaginatedCollectionV2.getEntries()` performs an O(n) full position-map scan to count records.
This is exposed through `storage.count()` / `session.countCollectionElements()` / `session.countClass()`.

YTDB-574/575 already added O(1) alternatives:
- `collection.getApproximateRecordsCount()` — volatile field read
- `storage.getApproximateRecordsCount(collectionId)` — delegates to collection
- `session.getApproximateCollectionCount(collectionId|name)` — delegates to storage
- `session.approximateCountClass(className, isPolymorphic)` — schema-level
- `cls.approximateCount(session, isPolymorphic)` — on SchemaClassInternal

Additionally, `session.browseCollection(name).hasNext()` provides an O(1) emptiness check.

**18 call sites** remain that use O(n) count but don't need exact values. 4 sites must keep
exact count (SQL `COUNT(*)`, Gremlin `count()`, database compare, script API).

Each step below = 1 session = 1 commit. Workflow per session:
1. Make code changes
2. Write/update tests for changed behavior
3. Run tests: `./mvnw -pl core clean test`
4. Run code-reviewer sub-agent; iterate until satisfied
5. Mark the step checkbox as `[x]` and include this plan file in the commit
6. Commit: `YTDB-576: <step description>`

---

## Progress

- [x] Step 1 — Progress/logging/load-balancing: approximate count (10 sites)
- [x] Step 2 — Emptiness checks: iterators (4 sites)
- [ ] Step 3 — DDL safety checks: approximate count (3 sites + bug fix)
- [ ] Step 4 — Storage countRecords: approximate sum (1 site)

---

## Step 1: Replace O(n) count with approximate count in progress/logging/load-balancing (10 sites)

All these sites use count only for progress bars, logging totals, or heuristic selection.
Approximate count is ideal — no accuracy requirement.

| # | File | Line | Change |
|---|---|---|---|
| 1 | `core/.../db/tool/GraphRepair.java` | 120 | `graph.countClass(edgeClass.getName())` → `graph.approximateCountClass(edgeClass.getName())` |
| 2 | `core/.../db/tool/GraphRepair.java` | 322 | `session.countClass(vertexClass.getName())` → `session.approximateCountClass(vertexClass.getName())` |
| 3 | `core/.../db/tool/BonsaiTreeRepair.java` | 34 | `db.countClass(edgeClass.getName())` → `db.approximateCountClass(edgeClass.getName())` |
| 4 | `core/.../sql/parser/SQLOptimizeDatabaseStatement.java` | 79 | `db.countClass("E")` → `db.approximateCountClass("E")` |
| 5 | `core/.../db/tool/DatabaseRecordWalker.java` | 59 | `session.countCollectionElements(collectionId)` → `session.getApproximateCollectionCount(collectionId)` |
| 6 | `core/.../db/tool/DatabaseExport.java` | 188 | `session.countCollectionElements(collectionName)` → `session.getApproximateCollectionCount(collectionName)` |
| 7 | `core/.../db/tool/CheckIndexTool.java` | 97 | `session.countCollectionElements(collectionId)` → `session.getApproximateCollectionCount(collectionId)` |
| 8 | `core/.../index/IndexAbstract.java` | 366 | `storage.count(session, storage.getCollectionIdByName(collection))` → `storage.getApproximateRecordsCount(storage.getCollectionIdByName(collection))` |
| 9 | `core/.../sql/executor/FetchFromStorageMetadataStep.java` | 64 | `collection.getEntries(atomicOperation)` → `collection.getApproximateRecordsCount()` |
| 10 | `core/.../schema/clusterselection/BalancedCollectionSelectionStrategy.java` | 50 | `session.countCollectionElements(collection)` → `session.getApproximateCollectionCount(collection)` |

**Note for #8**: The `doFillIndex` method also removes the need for a transaction around the count
since `getApproximateRecordsCount` doesn't require one. The `session.begin()`/`session.rollback()`
block (lines 363-370) wrapping the count loop can be simplified.

**Note for #9**: Property name stays `"entries"` — semantically it's the record count displayed
in `LIST CLUSTERS` metadata. The approximate value is acceptable for informational display.

**Tests**: Existing tests cover end-to-end behavior:
- `GraphRecoveringTest` (3 tests) — GraphRepair
- `CheckIndexToolTest` (2 tests) — CheckIndexTool
- `DbImportExportTest` and related — DatabaseExport
- Index rebuild tests in `tests/` module — IndexAbstract.doFillIndex

Run: `./mvnw -pl core clean test` then `./mvnw -pl tests clean test`

---

## Step 2: Replace O(n) emptiness checks with iterators (4 sites)

These sites do a full O(n) scan just to check `count > 0` or `count == 0`.

### 2.1 — `IndexAbstract.addCollection` (line 582)

**Current** (holds exclusive lock, has `collectionName` parameter):
```java
var collectionId = session.getCollectionIdByName(collectionName);
if (session.countCollectionElements(collectionId) > 0) {
  throw new IndexException("Collection " + collectionName + " is not empty...");
}
```

**New** — use iterator emptiness check (exact, O(1) amortized — reads first page only):
```java
try (var iter = session.browseCollection(collectionName)) {
  if (iter.hasNext()) {
    throw new IndexException("Collection " + collectionName + " is not empty...");
  }
}
```
Remove the now-unused `collectionId` variable.

### 2.2 — `IndexAbstract.removeCollection` (line 602)

Same pattern as 2.1. Replace `countCollectionElements(collectionId) > 0` with
`browseCollection(collectionName).hasNext()` in try-with-resources.

### 2.3 — `IndexAbstract.indexCollection` (line 846-851)

**Current** — starts a transaction just to get count, then uses it as a guard:
```java
var collectionId = session.getCollectionIdByName(collectionName);
session.begin();
var collectionCount = session.countCollectionElements(collectionId);
session.rollback();
if (collectionCount > 0) { ... browseCollection ... }
```

**New** — use `storage.getApproximateRecordsCount()` as guard (O(1), no tx needed):
```java
var collectionId = session.getCollectionIdByName(collectionName);
if (storage.getApproximateRecordsCount(collectionId) > 0) { ... browseCollection ... }
```
Remove `session.begin()` / `session.rollback()`. This is safe: if approximate says 0 but
collection has records (extremely unlikely), the iterator inside would catch them. But since
the approximate counter tracks creates/deletes atomically, this race is near-impossible in
single-threaded index rebuild context.

### 2.4 — `SchemaClassEmbedded.tryDropCollection` (line 588-596)

**Current**:
```java
if (session.computeInTxInternal(
    tx -> session.countCollectionElements(collectionId)) == 0) {
  session.dropCollectionInternal(collectionId);
}
```

**New** — use iterator emptiness check within the transaction:
```java
var collectionName = session.getCollectionNameById(collectionId);
// collectionName is already known to equal this.name (checked on line 589)
if (session.computeInTxInternal(tx -> {
    try (var iter = session.browseCollection(collectionName)) {
      return !iter.hasNext();
    }
  })) {
  session.dropCollectionInternal(collectionId);
}
```
Note: the `collectionName` is already computed on line 589 — refactor to share the variable.

**Tests needed**: Add tests to verify:
- `addCollection` with non-empty collection throws `IndexException`
- `removeCollection` with non-empty collection throws `IndexException`
- `indexCollection` correctly indexes non-empty collections and skips empty ones
- `tryDropCollection` drops empty collection, preserves non-empty one

Run: `./mvnw -pl core clean test` then `./mvnw -pl tests clean test`

---

## Step 3: Replace O(n) count with approximate count in DDL safety checks (3 sites)

These guard DROP/TRUNCATE CLASS operations. Using approximate count is safe:
- If approximate > 0 → class definitely non-empty → correctly blocks unsafe DDL
- If approximate == 0 but actual > 0 (extremely rare) → allows DDL the user requested → low-risk

### 3.1 — `SQLDropClassStatement.java` (line 45)

```java
// Before:
if (!unsafe && session.computeInTxInternal(tx -> clazz.count(session)) > 0) {
// After:
if (!unsafe && session.computeInTxInternal(tx -> clazz.approximateCount(session)) > 0) {
```

### 3.2 — `SQLTruncateClassStatement.java` (line 38)

```java
// Before:
final var recs = session.computeInTxInternal(tx -> clazz.count(session, polymorphic));
// After:
final var recs = session.computeInTxInternal(tx -> clazz.approximateCount(session, polymorphic));
```

### 3.3 — `SQLTruncateClassStatement.java` (line 55) — also fix pre-existing bug

```java
// Before (BUG: uses `clazz` instead of `subclass`):
var subclassRecs = session.computeInTxInternal(tx -> clazz.count(session));
// After (fix bug + use approximate):
var subclassRecs = session.computeInTxInternal(
    tx -> ((SchemaClassInternal) subclass).approximateCount(session));
```
The bug: the loop iterates `subclasses` but checks count on `clazz` (parent) every time.
Fix by using `subclass` and cast to `SchemaClassInternal` for the `approximateCount` method.

**Tests needed**: Add tests to verify:
- `DROP CLASS` on non-empty vertex/edge class without UNSAFE still throws
- `TRUNCATE CLASS` on non-empty vertex/edge class without UNSAFE still throws
- Bug fix: TRUNCATE CLASS POLYMORPHIC checks subclass counts correctly

Run: `./mvnw -pl core clean test` then `./mvnw -pl tests clean test`

---

## Step 4: Replace O(n) countRecords with approximate sum (1 site)

### 4.1 — `AbstractStorage.countRecords()` (lines 491-503)

**Current**:
```java
public long countRecords(DatabaseSessionEmbedded session) {
  long tot = 0;
  var transaction = session.getActiveTransaction();
  var atomicOperation = transaction.getAtomicOperation();
  for (var c : getCollectionInstances()) {
    if (c != null) {
      tot += c.getEntries(atomicOperation) - c.getTombstonesCount();
    }
  }
  return tot;
}
```

**New** — use approximate count (no transaction needed):
```java
public long countRecords(DatabaseSessionEmbedded session) {
  long tot = 0;
  for (var c : getCollectionInstances()) {
    if (c != null) {
      tot += c.getApproximateRecordsCount();
    }
  }
  return tot;
}
```

This method is marked `@SuppressWarnings("unused")` in the `Storage` interface and has only
one caller: test `SchemaTest.checkTotalRecords()` which asserts `> 0`. Safe to use approximate.

**Tests**: Existing `SchemaTest.checkTotalRecords()` continues to pass. Add a targeted test
verifying `countRecords()` returns a positive value for a database with records.

Run: `./mvnw -pl core clean test`

---

## Sites NOT Changed (require exact count)

| File | Line | Reason |
|---|---|---|
| `CountFromClassStep.java` | 44 | SQL `COUNT(*)` — user-facing, must be exact |
| `YTDBClassCountStep.java` | 42 | Gremlin `count()` — user-facing, must be exact |
| `DatabaseCompare.java` | 637-638 | Database comparison — correctness requires exact match |
| `ScriptDocumentDatabaseWrapper.java` | 249 | Script API — user-facing |

---

## Verification Per Step

```bash
# Unit tests for core module
./mvnw -pl core clean test

# Integration tests if touching storage/index code (Steps 1, 2, 4)
./mvnw -pl core clean verify -P ci-integration-tests

# Tests module for end-to-end (all steps)
./mvnw -pl tests clean test

# Coverage gate
./mvnw clean package -P coverage
python3 .github/scripts/coverage-gate.py \
  --line-threshold 85 --branch-threshold 70 \
  --compare-branch origin/develop --coverage-dir .coverage/reports
```
