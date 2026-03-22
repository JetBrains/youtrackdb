# Track 6: Index by vertex support

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [x] Track-level code review (2/3 iterations — passed)

## Base commit
`e0b5351e48`

## Reviews completed
- [x] Technical
- [x] Risk

## Review decisions

The technical and risk reviews revealed that Track 6 is **significantly simpler**
than the plan anticipated:

1. **No SQL grammar changes needed.** The existing `BY KEY`/`BY VALUE` syntax
   (used for maps) already works for LINKBAG fields. `extractMapIndexSpecifier()`
   parses `BY KEY`/`BY VALUE` from the field name string, and the `indexBy`
   parameter is already passed to `createSingleFieldIndexDefinition()` — it's
   just **ignored** for LINKBAG at line 238-239. We reuse it:
   - Default / `BY KEY` → `PropertyLinkBagIndexDefinition` (existing, indexes
     primaryRid = edge RID)
   - `BY VALUE` → `PropertyLinkBagSecondaryIndexDefinition` (new, indexes
     secondaryRid = vertex RID)

2. **No event infrastructure changes needed.** `MultiValueChangeEvent` already
   carries both RIDs: `getKey()` = primaryRid, `getValue()` = secondaryRid.
   The secondary index definition simply uses `changeEvent.getValue()` instead
   of `changeEvent.getKey()`.

3. **WAL atomicity is already guaranteed.** Both primary and secondary index
   updates happen within the same `ClassIndexManager.processMultiValueIndex()`
   loop inside the same atomic operation.

4. **Index serialization** works via `INDEX_DEFINITION_CLASS` stored in the
   index config. The secondary definition class name is stored automatically.
   Need to override `getFieldsToIndex()` and `toCreateIndexDDL()` for DDL
   round-trip.

Plan D4's proposed `BY_VERTEX`/`BY_EDGE` tokens are superseded by the simpler
`BY KEY`/`BY VALUE` reuse.

## Steps

- [x] Step 1: Create `PropertyLinkBagSecondaryIndexDefinition` class
  > **What was done:** Created `PropertyLinkBagSecondaryIndexDefinition` extending
  > `PropertyIndexDefinition` + `IndexDefinitionMultiValue`. Extracts `secondaryRid`
  > (opposite vertex RID) from LinkBag entries instead of `primaryRid` (edge RID).
  > Uses `getValue()` for ADD and `getOldValue()` for REMOVE in `processChangeEvent()`.
  > Overrides `getFieldsToIndex()` → `"field by value"` and `toCreateIndexDDL()` for
  > DDL round-trip. Added null guards, `equals`/`hashCode` override (getClass-based
  > to distinguish from primary), and 23 unit tests in abstract base + embedded variant.
  >
  > **Key files:** `PropertyLinkBagSecondaryIndexDefinition.java` (new),
  > `SchemaPropertyLinkBagSecondaryAbstractIndexDefinition.java` (new),
  > `SchemaPropertyEmbeddedLinkBagSecondaryIndexDefinitionTest.java` (new)

- [x] Step 2: Route `BY VALUE` to secondary index in `IndexDefinitionFactory`
  > **What was done:** Modified LINKBAG branch in `createSingleFieldIndexDefinition()`
  > to check `indexBy == INDEX_BY.VALUE` and create `PropertyLinkBagSecondaryIndexDefinition`.
  > Default/BY KEY path unchanged. Added 3 integration tests in `SQLCreateIndexTest`:
  > BY VALUE creation, BY KEY creation, and persistence round-trip (close/reopen).
  >
  > **What was discovered:** The existing `extractMapIndexSpecifier()` already returns
  > `INDEX_BY.KEY` as default for bare field names, so `null` indexBy never reaches the
  > factory for SQL-parsed indexes. No grammar changes needed — `BY KEY`/`BY VALUE` syntax
  > is already parsed for maps and works identically for LINKBAG.
  >
  > **Key files:** `IndexDefinitionFactory.java` (modified),
  > `SQLCreateIndexTest.java` (modified, +3 tests)

- [x] Step 3: End-to-end CRUD and dual-index tests
  > **What was done:** Created `LinkBagSecondaryIndexTest` with 8 integration tests
  > covering dual primary+secondary index creation, entry removal, mixed/duplicate
  > targets (NOTUNIQUE index deduplicates (key, docRID) pairs), incremental add via
  > change tracking, entity deletion clearing both indexes, persistence round-trip
  > (close/reopen), and transaction rollback. Added class to `EmbeddedTestSuite`.
  >
  > **What was discovered:** NOTUNIQUE indexes deduplicate (key, docRID) pairs —
  > when two LinkBag entries in the same entity have the same secondaryRid, the
  > secondary index stores 1 entry (not 2). This is consistent with how primary
  > indexes work.
  >
  > **Key files:** `LinkBagSecondaryIndexTest.java` (new),
  > `EmbeddedTestSuite.java` (modified)
