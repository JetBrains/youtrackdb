# Track 11: Replace hash table with hash-accelerated linear scan

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/3 complete)
- [ ] Track-level code review

## Base commit
`d247142621`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1+2 (merged): Rewrite serialization and deserialization — remove hash table, add 4-byte hash prefix
  - [x] Context: warning
  > **What was done:** Merged Steps 1-2 into a single commit because Step 1
  > alone cannot pass tests (serialization format change breaks deserialization).
  > Replaced linear probing hash table with hash-accelerated linear scan: each
  > property entry is now prefixed with a 4-byte MurmurHash3 hash. Removed all
  > hash table code (buildHashTable, fibonacciSlotIndex, computeHash8,
  > HashTableResult, slot arrays, linear/hash-table mode branching). Added
  > skipNameAndTypeAndValue() helper for efficient entry skipping during
  > hash-accelerated partial deserialization and field lookup. Deleted
  > RecordSerializerBinaryV2HashTableTest.java (tests removed methods). Updated
  > RecordSerializerBinaryV2RoundTripTest.java to replace hash-table-specific
  > format verification tests with hash-prefix verification test. Net: -1,072 lines.
  >
  > **What was discovered:** A complete rewrite of the V2 file caused a subtle
  > bug where database authentication failed (SecurityAccessException for all
  > DB-dependent tests). The root cause was never fully isolated — the byte
  > format was identical, but something in the rewritten code path broke entity
  > deserialization during DB open. The fix was to apply changes incrementally
  > to the existing file rather than a full rewrite: modify the 5 main methods
  > (serialize, deserialize, deserializePartial, deserializeField, getFieldNames)
  > in-place, then remove dead code. This incremental approach worked immediately.
  >
  > **What changed from the plan:** Steps 1 and 2 merged into a single commit.
  > Step 3 (test updates) was partially absorbed — hash-table-specific tests in
  > RoundTripTest were already updated, and HashTableTest was already deleted.
  > Remaining Step 3 work: update PartialTest comments and add new hash-prefix-
  > specific tests.
  >
  > **Key files:**
  > - `RecordSerializerBinaryV2.java` (modified)
  > - `RecordSerializerBinaryV2HashTableTest.java` (deleted)
  > - `RecordSerializerBinaryV2RoundTripTest.java` (modified)

- [ ] Step 3: Update unit tests — remove hash table tests, add hash-accelerated tests
  Delete or rewrite `RecordSerializerBinaryV2HashTableTest.java` — all
  `buildHashTable_*`, `fibonacciSlotIndex_*`, and hash-table-specific tests
  are obsolete. Replace with tests for the new format:

  - Hash prefix correctness: serialize an entity, verify 4-byte hash prefix
    matches `MurmurHash3.hash32WithSeed(name.getBytes(UTF_8), 0, len, 0)`.
  - Hash-accelerated partial deserialization: verify correct field retrieval
    for entities with 5, 13, 20, 50 properties.
  - getFieldNames with hash prefix: verify field names are correctly extracted.
  - Edge cases: single property, empty entity, very long property names,
    schema-aware properties, mixed schema-aware/schema-less.

  Update `RecordSerializerBinaryV2RoundTripTest.java` and
  `RecordSerializerBinaryV2PartialTest.java` — remove any assertions that
  reference hash table structure (slot arrays, seed, log2Capacity) and
  update property count tier tests (no more linear/hash-table distinction).

  **Note:** Much of this work was done in Step 1+2. Remaining work is
  updating PartialTest comments and adding new hash-prefix-specific tests.

  **Key files**: `RecordSerializerBinaryV2RoundTripTest.java`,
  `RecordSerializerBinaryV2PartialTest.java`
