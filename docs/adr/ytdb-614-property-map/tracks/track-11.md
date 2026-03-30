# Track 11: Replace hash table with hash-accelerated linear scan

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (2/3 iterations)

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

- [x] Step 3: Update unit tests — remove hash table tests, add hash-accelerated tests
  - [x] Context: info
  > **What was done:** Updated both V2 test files to remove all hash table
  > terminology (method names, comments, section headers). Renamed 30+ methods
  > from `*_linearMode`/`*_hashTableMode` to property-count-based names.
  > Updated class javadocs to describe hash-accelerated linear scan. Added 10
  > new test methods: boundary tests (single-property partial/field, empty-entity
  > field), 20-property partial deserialization, unicode partial lookup,
  > schema-aware hash prefix verification with direct byte inspection, and mixed
  > schema-aware/schema-less fieldNames test. Review fixes added missing
  > session.rollback() calls, fixed collision test comment, and reorganized
  > sections. Final test count: 90 (was 85 pre-step). Coverage: 85.8% line /
  > 78.7% branch.
  >
  > **Key files:**
  > - `RecordSerializerBinaryV2PartialTest.java` (modified)
  > - `RecordSerializerBinaryV2RoundTripTest.java` (modified)
