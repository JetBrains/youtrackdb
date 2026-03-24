# Track 4: RecordSerializerBinaryV2 — hash map serialization format

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
- [ ] Track-level code review

## Base commit
`3dfc5c533c`

## Reviews completed
- [x] Technical (9 findings: 4 blocker, 3 should-fix, 2 suggestion — all resolved)
- [x] Risk (12 findings: 2 blocker, 7 should-fix, 3 suggestion — all resolved)
- [x] Adversarial (10 findings: 3 blocker, 4 should-fix, 3 suggestion — all resolved)

## Key review decisions
- **Empty slot sentinel**: Use 0xFF/0xFFFF instead of 0x00/0x0000 (T3/R2/A2)
- **Hash formula**: Fibonacci hashing consistently everywhere (A1)
- **Comparator**: V2 returns BinaryComparatorV0 as stub; Track 5 replaces (T6/R9)
- **Seed search**: Iterative loop, max capacity 1024, throw if exceeded (A3/R1)
- **64 KB overflow**: Throw exception if KV region exceeds 64 KB (T2/R10)
- **Corruption detection**: Offset bounds checking in deserialize (A9)
- **Serialization order**: KV entries in entity iteration order (A7)
- **Hash prefix (D3)**: Keep 1-byte hash8 — low cost, corruption detection (A5)
- **Linear mode (D4)**: Keep for ≤2 properties — space savings justify it (A6)

## Steps

- [x] Step 1: Hash table core utilities — Fibonacci hash, capacity computation, seed search
  > **What was done:** Created `RecordSerializerBinaryV2` class implementing
  > `EntitySerializer` with static utility methods: `fibonacciIndex()`,
  > `computeLog2Capacity()`, `findPerfectHashSeed()`, `computeHash8()`, and
  > sentinel constants. All interface methods are stubs (UnsupportedOperationException)
  > except `getComparator()` which returns `BinaryComparatorV0` per review decision.
  > 31 tests in `RecordSerializerBinaryV2HashTableTest`.
  >
  > **What was discovered:** The initial `computeHash8` implementation had an
  > incorrect sentinel collision guard checking offset==0 instead of offset==0xFFFF.
  > Code review caught this — simplified to a single-arg method since the 0xFFFF
  > sentinel offset is reserved and never assigned to real entries, making collision
  > impossible. Also simplified seed search cleanup from per-element re-hashing to
  > `Arrays.fill()`.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (new),
  > `RecordSerializerBinaryV2HashTableTest.java` (new)

- [ ] Step 2: V2 serialization and full deserialization (round-trip)
  Implement the core serialize/deserialize methods in `RecordSerializerBinaryV2`
  implementing `EntitySerializer`:

  **Serialization** (`serialize`, `serializeWithClassName`):
  - Write class name (varint len + UTF-8, or 0 for no class)
  - Write property count (varint)
  - If count ≤ 2: linear mode — for each property write name-encoding + type +
    value-size + value-bytes (same layout as V1)
  - If count > 2: hash table mode —
    1. Collect property names (resolve from GlobalProperty for schema-aware)
    2. Call `findPerfectHashSeed()` to get seed and log2Capacity
    3. Write seed (4 bytes LE) + log2Capacity (1 byte)
    4. Reserve slot array space (capacity × 3 bytes, filled with 0xFF/0xFFFF)
    5. Write KV entries sequentially in entity iteration order:
       entry = name-encoding + type byte + value-size varint + value-bytes
    6. Backpatch slot array: for each entry, compute `fibonacciIndex(hash, log2Cap)`
       → write hash8 + offset (relative to KV region base)
  - Throw if KV region exceeds 64 KB (2-byte offset limit)
  - `serializeValue`/`deserializeValue`: delegate to HelperClasses (same type
    encoding as V1). Embedded entities serialized recursively using V2 format.

  **Full deserialization** (`deserialize`, `deserializeWithClassName`):
  - Read class name
  - Read property count
  - If count ≤ 2: linear mode — read entries sequentially
  - If count > 2: hash table mode — skip seed + log2Capacity + slot array, then
    read KV entries linearly (full deserialization doesn't need the hash table)
  - For each entry: read name-encoding (schema-aware: negative varint → resolve
    from GlobalProperty; schema-less: varint len + UTF-8), read type, read value

  **Tests**: Round-trip tests for all property types (integers, strings, dates,
  decimals, binary, links, embedded entities, collections, maps, null values).
  Both linear mode (0-2 properties) and hash table mode (5+ properties).
  Schema-aware and schema-less properties. Mixed mode (some schema, some not).
  Empty entity. Entity with embedded sub-entities.

- [ ] Step 3: V2 partial deserialization, field lookup, and field names
  Implement the O(1) field access methods that make V2 valuable:

  **`deserializePartial(fields)`**:
  - Read class name, property count
  - If count ≤ 2: linear scan (same as V1)
  - If count > 2: for each requested field name:
    1. Compute `hash32WithSeed(fieldNameBytes, seed)`
    2. Compute `fibonacciIndex(hash, log2Cap)` → slot index
    3. Read slot: if hash8 == 0xFF and offset == 0xFFFF → field not found
    4. Bounds check: offset < KV region size (corruption detection per A9)
    5. Follow offset to KV entry, read name-encoding, verify key matches
    6. If key matches: read type + value. If not: field not found (corruption).

  **`deserializeField(fieldName)`**:
  - Same hash lookup as deserializePartial but returns `BinaryField` wrapping
    the raw bytes (type + BytesContainer positioned at value bytes + Collate).
  - Used by BinaryComparator for index-level field comparison.

  **`getFieldNames()`**:
  - If count ≤ 2: read names linearly
  - If count > 2: iterate slot array, skip empty slots (0xFF/0xFFFF), follow
    offsets to read property names from KV entries.

  **Tests**: Partial deserialization with 5+ properties (hash mode), requesting
  subset of fields. Non-existent field returns null. Field at offset 0 (first
  entry). deserializeField for all 13 binary-comparable types. getFieldNames
  for schema-aware, schema-less, and mixed entities. Offset bounds violation
  detection (synthetic corrupted bytes).

- [ ] Step 4: V2 registration in RecordSerializerBinary and backward compatibility
  Wire V2 into the version dispatch system and verify backward compatibility:

  **Registration** (RecordSerializerBinary modifications):
  - `init()`: allocate `new EntitySerializer[2]`, register V1 at [0], V2 at [1]
  - `CURRENT_RECORD_VERSION = 1` — new records written as V2
  - `getNumberOfSupportedVersions()` returns 2

  **Comparator stub**:
  - `RecordSerializerBinaryV2.getComparator()` returns `new BinaryComparatorV0()`
  - Track 5 will replace this with BinaryComparatorV1 for O(1) field lookup

  **Tests**:
  - **Backward compatibility**: Serialize entity as V1 (version byte 0), then
    deserialize via RecordSerializerBinary dispatcher — must still work after
    V2 is registered.
  - **Mixed version**: Create V1 record, create V2 record, deserialize both
    in same session. Verify both produce identical entity content.
  - **Parameterized tests**: Track 2's `EntitySchemalessBinarySerializationTest`
    automatically runs against V2 (version 1) since it iterates
    `getNumberOfSupportedVersions()`. All 12 partial deserialization contract
    tests must pass.
  - **Version byte verification**: Serialize with V2, check first byte is 1.
    Serialize with V1, check first byte is 0.
