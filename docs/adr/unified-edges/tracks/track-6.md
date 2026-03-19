# Track 6: Index by vertex support

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

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

- [ ] Step 1: Create `PropertyLinkBagSecondaryIndexDefinition` class
  > Create new class extending `PropertyIndexDefinition` implementing
  > `IndexDefinitionMultiValue`. Key methods:
  > - `processChangeEvent()` — uses `changeEvent.getValue()` (secondaryRid)
  >   instead of `changeEvent.getKey()` (primaryRid) for ADD/REMOVE
  > - `createSingleValue()` — converts secondaryRid to LINK type
  > - `createValue(List)` — iterates LinkBag, extracts `secondaryRid` from
  >   each `RidPair`
  > - `createValue(Object...)` — same extraction for varargs path
  > - `getDocumentValueToIndex()` — reads entity's LinkBag field and extracts
  >   secondaryRid values
  > - `getFieldsToIndex()` — returns `field + " by value"` for DDL round-trip
  > - `toCreateIndexDDL()` — includes `by value` in the DDL string
  > - No-arg constructor for reflection-based deserialization
  >
  > Model on existing `PropertyLinkBagIndexDefinition` — same structure, but
  > extracting `secondaryRid` everywhere the primary extracts `primaryRid`.
  > Unit tests for the definition class in isolation.

- [ ] Step 2: Route `BY VALUE` to secondary index in `IndexDefinitionFactory`
  > Modify the LINKBAG branch in `createSingleFieldIndexDefinition()` (line
  > 238-239) to check the `indexBy` parameter:
  > - `INDEX_BY.VALUE` → create `PropertyLinkBagSecondaryIndexDefinition`
  > - `INDEX_BY.KEY` or `null` → create `PropertyLinkBagIndexDefinition`
  >   (existing behavior, backward compatible)
  >
  > Add integration tests:
  > - `CREATE INDEX ... ON ... (field BY VALUE)` creates secondary definition
  > - `CREATE INDEX ... ON ... (field)` creates primary definition (default)
  > - `CREATE INDEX ... ON ... (field BY KEY)` creates primary definition
  > - DDL round-trip: create index → persist → reload → verify correct type

- [ ] Step 3: End-to-end CRUD and dual-index tests
  > Full integration tests covering:
  > - Create edges, verify secondary index is maintained (add/remove)
  > - Query edges by opposite vertex RID via secondary index
  > - Dual indexes: both primary (BY KEY) and secondary (BY VALUE) on same
  >   field, verify both are updated correctly on edge add/remove
  > - Delete edge → both indexes updated
  > - Bulk operations: multiple edges to same vertex, verify all indexed
  > - Persistence round-trip: create secondary index with data, close/reopen
  >   database, verify index still functional
