# Track 11: Replace hash table with hash-accelerated linear scan

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Base commit

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [ ] Step 1: Rewrite serialization — remove hash table, add 4-byte hash prefix per entry
  Remove `buildHashTable()`, `HashTableResult`, `serializeHashTableMode()`,
  `serializeLinearMode()`, and all hash table constants (`EMPTY_HASH8`,
  `EMPTY_OFFSET`, `FIBONACCI_CONSTANT`, `SLOT_SIZE`, `MAX_LOG2_CAPACITY`,
  `LINEAR_MODE_THRESHOLD`). Remove utility methods: `computeHash8()`,
  `computeLog2Capacity()`, `fibonacciSlotIndex()`, `readAndValidateLog2Capacity()`,
  `validateSlotOffset()`.

  Replace with a single serialization path in `serializeEntity()`:
  - For each property: compute `MurmurHash3.hash32WithSeed(nameBytes, 0, len, 0)`,
    write 4 bytes LE, then write name-encoding + type + value (via existing
    `serializePropertyEntry()`).
  - Reuse Track 10's `preEncodedName` optimization: encode UTF-8 once for
    schema-less properties, use for both hashing and name writing.
  - Keep `MAX_KV_REGION_SIZE` (still limits entry region to 64 KB).

  This step will break all deserialization and tests — they are fixed in
  subsequent steps. The code must compile but tests will fail.

  **Key files**: `RecordSerializerBinaryV2.java`

- [ ] Step 2: Rewrite all deserialization paths — full, partial, field, getFieldNames
  Update all four deserialization entry points to handle the new format:

  - **Full deserialization** (`deserialize`): Remove `deserializeLinearMode` /
    `deserializeHashTableModeFull` distinction. Single loop: for each entry,
    skip 4-byte hash, then call existing `deserializeEntry()`.

  - **Partial deserialization** (`deserializePartial`): Remove
    `deserializePartialLinear` / `deserializePartialHashTable` distinction.
    Single loop: for each entry, read 4-byte hash as int. Pre-compute hash of
    each requested field name. On hash mismatch: skip name-encoding (read
    varint, skip bytes for schema-less; read varint for schema-aware), skip
    type byte, read value-size varint, skip value bytes. On hash match: read
    name, verify string equality (collision guard), deserialize value.

  - **`deserializeField()`**: Remove `deserializeFieldLinear` /
    `deserializeFieldHashTable` distinction. Same hash-first rejection as
    partial deserialization, but returns `BinaryField` on match.

  - **`getFieldNames()`**: Remove `getFieldNamesLinear` /
    `getFieldNamesHashTable` distinction. Single loop: skip 4-byte hash,
    read name, skip type + value.

  All existing round-trip tests should pass after this step.

  **Key files**: `RecordSerializerBinaryV2.java`

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

  **Key files**: `RecordSerializerBinaryV2HashTableTest.java`,
  `RecordSerializerBinaryV2RoundTripTest.java`,
  `RecordSerializerBinaryV2PartialTest.java`
